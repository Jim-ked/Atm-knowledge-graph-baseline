package org.atmkg.infra.source.jdbc;

import com.fasterxml.jackson.databind.JsonNode;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.regex.Pattern;
import org.atmkg.core.model.SourceRecord;
import org.atmkg.core.spi.SourceAdapter;
import org.atmkg.infra.source.config.ConfiguredSource;

/**
 * 新增 JDBC 表/view 不改本类：在 {@code config/sources.yaml} 的 jdbc source.objects 下增加 object，
 * 填 table 或 view（二选一）、keyFields 和可选 watermarkField；账号名/密码通过 usernameEnv/passwordEnv
 * 指向环境变量。新增业务字段再去 {@code mapping/字段映射.xlsx}。
 *
 * <p>只有参数化 SQL 生成、通用标识符校验、ResultSet 类型读取或流式资源生命周期变化才写 Java。拼接用户
 * SQL、加航空字段/数据库厂商业务分支会破坏安全和通用边界。读取失败先查 driver/url/env/SELECT 权限；
 * “缺少配置字段”查 ResultSet 列名；增量问题查 watermarkField 类型和索引。无 hard DELETE/JOIN/持久化游标。
 */
public final class JdbcSourceAdapter implements SourceAdapter {
    private static final String ADAPTER = "jdbc";
    private static final String SYNTHETIC_SOURCE_KEY = "__sourceKey";
    private static final Pattern QUALIFIED_IDENTIFIER = Pattern.compile(
            "[\\p{L}_][\\p{L}\\p{N}_$]*(?:\\.[\\p{L}_][\\p{L}\\p{N}_$]*)*");
    private static final Pattern COLUMN_IDENTIFIER = Pattern.compile("[\\p{L}_][\\p{L}\\p{N}_$]*");

    private final String sourceId;
    private final String url;
    private final String username;
    private final String password;
    private final int fetchSize;
    private final Map<String, ObjectSpec> objects;

    public JdbcSourceAdapter(ConfiguredSource source) {
        this(source, System.getenv());
    }

    JdbcSourceAdapter(ConfiguredSource source, Map<String, String> environment) {
        if (source == null) throw new IllegalArgumentException("source 不能为空");
        if (!ADAPTER.equalsIgnoreCase(source.getAdapter())) {
            throw new IllegalArgumentException("JdbcSourceAdapter 不能读取 adapter=" + source.getAdapter());
        }
        JsonNode config = source.getConfig();
        this.sourceId = source.getSourceId();
        this.url = requiredText(config, "url", sourceId);
        String driver = requiredText(config, "driver", sourceId);
        this.username = requiredEnvironment(environment, requiredText(config, "usernameEnv", sourceId));
        this.password = requiredEnvironment(environment, requiredText(config, "passwordEnv", sourceId));
        this.fetchSize = optionalPositiveInt(config, "fetchSize", 500, sourceId);
        this.objects = parseObjects(config.get("objects"));
        loadDriver(driver);
    }

    @Override
    public Iterable<SourceRecord> readAll(String objectName) {
        ObjectSpec spec = requireObject(objectName);
        String sql = "SELECT * FROM " + spec.relation + orderBy(spec);
        return records(objectName, spec, sql, statement -> {}, 0, true);
    }

    @Override
    public Optional<SourceRecord> readByKey(String objectName, String sourceKey) {
        if (sourceKey == null || sourceKey.isBlank()) throw new IllegalArgumentException("sourceKey 不能为空");
        ObjectSpec spec = requireObject(objectName);
        List<String> values = decodeSourceKey(sourceKey, spec.keyFields.size());
        StringBuilder sql = new StringBuilder("SELECT * FROM ").append(spec.relation).append(" WHERE ");
        for (int i = 0; i < spec.keyFields.size(); i++) {
            if (i > 0) sql.append(" AND ");
            sql.append(spec.keyFields.get(i)).append(" = ?");
        }
        JdbcIterator iterator = query(objectName, spec, sql.toString(), statement -> {
            for (int i = 0; i < values.size(); i++) statement.setString(i + 1, values.get(i));
        }, 2, false);
        try {
            if (!iterator.hasNext()) return Optional.empty();
            SourceRecord record = iterator.next();
            if (!record.getSourceKey().equals(sourceKey)) {
                throw new IllegalStateException("JDBC 返回记录的 sourceKey 与查询键不一致：" + sourceKey);
            }
            if (iterator.hasNext()) {
                throw new IllegalStateException("JDBC 业务键不是唯一记录：" + sourceId + "/" + objectName + "/" + sourceKey);
            }
            return Optional.of(record);
        } finally {
            iterator.close();
        }
    }

    @Override
    public Iterable<SourceRecord> scanChangedSince(String objectName, Instant since) {
        if (since == null) throw new IllegalArgumentException("since 不能为空");
        ObjectSpec spec = requireObject(objectName);
        if (spec.watermarkField == null) {
            throw new IllegalStateException(objectName + " 未配置 watermarkField，不能执行 scanChangedSince");
        }
        String sql = "SELECT * FROM " + spec.relation + " WHERE " + spec.watermarkField + " > ?" + orderBy(spec);
        return records(objectName, spec, sql,
                statement -> statement.setTimestamp(1, Timestamp.from(since)), 0, true);
    }

    private Iterable<SourceRecord> records(String objectName, ObjectSpec spec, String sql,
                                           StatementBinder binder, int maxRows, boolean rejectDuplicates) {
        return () -> query(objectName, spec, sql, binder, maxRows, rejectDuplicates);
    }

    private JdbcIterator query(String objectName, ObjectSpec spec, String sql,
                               StatementBinder binder, int maxRows, boolean rejectDuplicates) {
        Connection connection = null;
        PreparedStatement statement = null;
        try {
            Properties properties = new Properties();
            properties.setProperty("user", username);
            properties.setProperty("password", password);
            connection = DriverManager.getConnection(url, properties);
            statement = connection.prepareStatement(sql);
            statement.setFetchSize(fetchSize);
            if (maxRows > 0) statement.setMaxRows(maxRows);
            binder.bind(statement);
            ResultSet resultSet = statement.executeQuery();
            return new JdbcIterator(connection, statement, resultSet, sourceId, objectName, spec, rejectDuplicates);
        } catch (SQLException ex) {
            close(statement);
            close(connection);
            throw new IllegalStateException("JDBC 读取失败：" + sourceId + "/" + objectName, ex);
        }
    }

    private ObjectSpec requireObject(String objectName) {
        if (objectName == null || objectName.isBlank()) throw new IllegalArgumentException("objectName 不能为空");
        ObjectSpec spec = objects.get(objectName);
        if (spec == null) throw new IllegalArgumentException("未配置 JDBC sourceObject：" + objectName);
        return spec;
    }

    private Map<String, ObjectSpec> parseObjects(JsonNode objectsNode) {
        if (objectsNode == null || !objectsNode.isObject()) {
            throw new IllegalArgumentException("JDBC 数据源必须配置 objects 对象");
        }
        Map<String, ObjectSpec> parsed = new LinkedHashMap<>();
        objectsNode.fields().forEachRemaining(entry ->
                parsed.put(entry.getKey(), ObjectSpec.parse(entry.getKey(), entry.getValue())));
        if (parsed.isEmpty()) throw new IllegalArgumentException("JDBC 数据源 objects 不能为空");
        return Map.copyOf(parsed);
    }

    private static String orderBy(ObjectSpec spec) {
        return " ORDER BY " + String.join(", ", spec.keyFields);
    }

    private static String sourceKey(Map<String, Object> fields, List<String> keyFields, String scope) {
        List<String> values = new ArrayList<>();
        for (String field : keyFields) {
            Object value = fields.get(field);
            String text = value == null ? "" : String.valueOf(value).trim();
            if (text.isBlank()) throw new IllegalStateException("sourceKey 字段为空：" + field + " @ " + scope);
            values.add(escapeKey(text));
        }
        return String.join("|", values);
    }

    private static String escapeKey(String value) {
        return value.replace("\\", "\\\\").replace("|", "\\|");
    }

    private static List<String> decodeSourceKey(String sourceKey, int expectedParts) {
        List<String> parts = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean escaped = false;
        for (int i = 0; i < sourceKey.length(); i++) {
            char character = sourceKey.charAt(i);
            if (escaped) {
                if (character != '\\' && character != '|') {
                    throw new IllegalArgumentException("sourceKey 包含未知转义：\\" + character);
                }
                current.append(character);
                escaped = false;
            } else if (character == '\\') {
                escaped = true;
            } else if (character == '|') {
                parts.add(current.toString());
                current.setLength(0);
            } else {
                current.append(character);
            }
        }
        if (escaped) throw new IllegalArgumentException("sourceKey 转义不完整：" + sourceKey);
        parts.add(current.toString());
        if (parts.size() != expectedParts || parts.stream().anyMatch(String::isBlank)) {
            throw new IllegalArgumentException("sourceKey 与 keyFields 数量不匹配：" + sourceKey);
        }
        return List.copyOf(parts);
    }

    private static void loadDriver(String driver) {
        try {
            Class.forName(driver);
        } catch (ClassNotFoundException ex) {
            throw new IllegalArgumentException("找不到 JDBC driver：" + driver, ex);
        }
    }

    private static String requiredEnvironment(Map<String, String> environment, String name) {
        Objects.requireNonNull(environment, "environment");
        String value = environment.get(name);
        if (value == null || value.isBlank()) throw new IllegalStateException("缺少必需环境变量：" + name);
        return value;
    }

    private static String requiredText(JsonNode node, String field, String scope) {
        JsonNode value = node.get(field);
        if (value == null || !value.isTextual() || value.textValue().isBlank()) {
            throw new IllegalArgumentException(scope + "." + field + " 必须是非空字符串");
        }
        return value.textValue().trim();
    }

    private static int optionalPositiveInt(JsonNode node, String field, int fallback, String scope) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) return fallback;
        if (!value.isIntegralNumber() || !value.canConvertToInt() || value.intValue() < 1) {
            throw new IllegalArgumentException(scope + "." + field + " 必须是正整数");
        }
        return value.intValue();
    }

    private static String identifier(JsonNode node, String field, String scope, Pattern pattern) {
        String value = requiredText(node, field, scope);
        if (!pattern.matcher(value).matches()) {
            throw new IllegalArgumentException(scope + "." + field + " 不是受支持的 SQL 标识符：" + value);
        }
        return value;
    }

    private static void close(AutoCloseable closeable) {
        if (closeable == null) return;
        try {
            closeable.close();
        } catch (Exception ignored) {
            // Preserve the original JDBC failure.
        }
    }

    @FunctionalInterface
    private interface StatementBinder {
        void bind(PreparedStatement statement) throws SQLException;
    }

    private static final class ObjectSpec {
        private final String relation;
        private final List<String> keyFields;
        private final String watermarkField;

        private ObjectSpec(String relation, List<String> keyFields, String watermarkField) {
            this.relation = relation;
            this.keyFields = keyFields;
            this.watermarkField = watermarkField;
        }

        private static ObjectSpec parse(String objectName, JsonNode node) {
            if (node == null || !node.isObject()) throw new IllegalArgumentException(objectName + " 必须是对象");
            boolean hasTable = node.hasNonNull("table");
            boolean hasView = node.hasNonNull("view");
            if (hasTable == hasView) throw new IllegalArgumentException(objectName + " 必须且只能配置 table 或 view");
            String field = hasTable ? "table" : "view";
            String relation = identifier(node, field, objectName, QUALIFIED_IDENTIFIER);
            JsonNode keys = node.get("keyFields");
            if (keys == null || !keys.isArray() || keys.isEmpty()) {
                throw new IllegalArgumentException(objectName + ".keyFields 必须是非空字符串数组");
            }
            Set<String> unique = new LinkedHashSet<>();
            for (JsonNode key : keys) {
                if (!key.isTextual() || key.textValue().isBlank()) {
                    throw new IllegalArgumentException(objectName + ".keyFields 只能包含非空字符串");
                }
                String name = key.textValue().trim();
                if (!COLUMN_IDENTIFIER.matcher(name).matches()) {
                    throw new IllegalArgumentException(objectName + ".keyFields 不是受支持的 SQL 标识符：" + name);
                }
                if (!unique.add(name)) throw new IllegalArgumentException(objectName + ".keyFields 包含重复字段：" + name);
            }
            String watermark = null;
            if (node.hasNonNull("watermarkField")) {
                watermark = identifier(node, "watermarkField", objectName, COLUMN_IDENTIFIER);
            }
            return new ObjectSpec(relation, List.copyOf(unique), watermark);
        }
    }

    private static final class JdbcIterator implements Iterator<SourceRecord>, AutoCloseable {
        private final Connection connection;
        private final PreparedStatement statement;
        private final ResultSet resultSet;
        private final String sourceId;
        private final String objectName;
        private final ObjectSpec spec;
        private final List<String> columns;
        private final Map<String, Integer> columnIndexes;
        private final boolean rejectDuplicates;
        private boolean loaded;
        private boolean available;
        private boolean closed;
        private String previousKey;

        private JdbcIterator(Connection connection, PreparedStatement statement, ResultSet resultSet,
                             String sourceId, String objectName, ObjectSpec spec, boolean rejectDuplicates)
                throws SQLException {
            this.connection = connection;
            this.statement = statement;
            this.resultSet = resultSet;
            this.sourceId = sourceId;
            this.objectName = objectName;
            this.spec = spec;
            this.rejectDuplicates = rejectDuplicates;
            this.columns = new ArrayList<>();
            this.columnIndexes = new LinkedHashMap<>();
            ResultSetMetaData metadata = resultSet.getMetaData();
            for (int index = 1; index <= metadata.getColumnCount(); index++) {
                String label = metadata.getColumnLabel(index);
                if (label == null || label.isBlank()) label = metadata.getColumnName(index);
                if (label == null || label.isBlank() || columnIndexes.putIfAbsent(label, index) != null) {
                    close();
                    throw new IllegalStateException("JDBC 结果字段名为空或重复：" + sourceId + "/" + objectName);
                }
                columns.add(label);
            }
            for (String key : spec.keyFields) requireColumn(key);
            if (spec.watermarkField != null) requireColumn(spec.watermarkField);
        }

        @Override
        public boolean hasNext() {
            if (closed) return false;
            if (loaded) return available;
            try {
                available = resultSet.next();
                loaded = true;
                if (!available) close();
                return available;
            } catch (SQLException ex) {
                close();
                throw new IllegalStateException("JDBC 结果遍历失败：" + sourceId + "/" + objectName, ex);
            }
        }

        @Override
        public SourceRecord next() {
            if (!hasNext()) throw new NoSuchElementException();
            loaded = false;
            try {
                Map<String, Object> fields = new LinkedHashMap<>();
                for (int index = 0; index < columns.size(); index++) {
                    fields.put(columns.get(index), resultSet.getObject(index + 1));
                }
                String key = sourceKey(fields, spec.keyFields, sourceId + "/" + objectName);
                if (rejectDuplicates && key.equals(previousKey)) {
                    close();
                    throw new IllegalStateException("JDBC sourceObject 出现重复 sourceKey："
                            + sourceId + "/" + objectName + "/" + key);
                }
                previousKey = key;
                fields.put(SYNTHETIC_SOURCE_KEY, key);
                Instant timestamp = null;
                if (spec.watermarkField != null) {
                    Timestamp value = resultSet.getTimestamp(columnIndexes.get(spec.watermarkField));
                    timestamp = value == null ? null : value.toInstant();
                }
                return new SourceRecord(sourceId, objectName, key, fields, timestamp);
            } catch (SQLException ex) {
                close();
                throw new IllegalStateException("JDBC 记录读取失败：" + sourceId + "/" + objectName, ex);
            }
        }

        private void requireColumn(String name) {
            if (!columnIndexes.containsKey(name)) {
                close();
                throw new IllegalStateException("JDBC 结果缺少配置字段：" + sourceId + "/" + objectName + "/" + name);
            }
        }

        @Override
        public void close() {
            if (closed) return;
            closed = true;
            JdbcSourceAdapter.close(resultSet);
            JdbcSourceAdapter.close(statement);
            JdbcSourceAdapter.close(connection);
        }
    }
}
