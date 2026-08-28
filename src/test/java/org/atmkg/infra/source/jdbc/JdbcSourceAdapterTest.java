package org.atmkg.infra.source.jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Driver;
import java.sql.DriverManager;
import java.sql.DriverPropertyInfo;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.logging.Logger;
import javax.sql.rowset.CachedRowSet;
import javax.sql.rowset.RowSetMetaDataImpl;
import javax.sql.rowset.RowSetProvider;
import org.atmkg.core.model.SourceRecord;
import org.atmkg.infra.source.config.ConfiguredSource;
import org.atmkg.infra.source.config.SourceConfig;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class JdbcSourceAdapterTest {
    private static final FakeDriver DRIVER = new FakeDriver();
    private static final Instant FIRST_CHANGED = Instant.parse("2026-01-01T00:00:00Z");
    private static final Instant SECOND_CHANGED = Instant.parse("2026-01-02T00:00:00Z");

    @TempDir Path temp;

    @BeforeAll
    static void registerDriver() throws SQLException {
        DriverManager.registerDriver(DRIVER);
    }

    @AfterAll
    static void deregisterDriver() throws SQLException {
        DriverManager.deregisterDriver(DRIVER);
    }

    @AfterEach
    void resetDriver() {
        DRIVER.executions.clear();
        DRIVER.rejectSetReadOnly = false;
        DRIVER.watermarkJdbcType = Types.TIMESTAMP;
        DRIVER.connectionCloseCount = 0;
        DRIVER.statementCloseCount = 0;
    }

    @Test
    void readsWithoutCallingSetReadOnlyWhenDriverRejectsIt() throws Exception {
        DRIVER.rejectSetReadOnly = true;
        JdbcSourceAdapter adapter = adapter("""
                objects:
                  route-row:
                    table: ATM_SCHEMA.ROUTE_ROWS
                    keyFields: [route_id, sequence_no]
                    watermarkField: modified_at
                """);

        List<SourceRecord> records = list(adapter.readAll("route-row"));

        assertEquals(2, records.size());
        assertEquals("SELECT * FROM ATM_SCHEMA.ROUTE_ROWS ORDER BY route_id, sequence_no",
                DRIVER.executions.get(0).sql());
    }

    @Test
    void readsRowsWithStableEscapedKeysAndEnvironmentCredentials() throws Exception {
        JdbcSourceAdapter adapter = adapter("""
                objects:
                  route-row:
                    table: route_rows
                    keyFields: [route_id, sequence_no]
                    watermarkField: modified_at
                """);

        List<SourceRecord> records = list(adapter.readAll("route-row"));

        assertEquals(List.of("R\\|1|2", "R\\\\2|3"),
                records.stream().map(SourceRecord::getSourceKey).toList());
        assertEquals("alpha", records.get(0).getFields().get("MixedCaseCaption"));
        assertFalse(records.get(0).getFields().containsKey("mixedcasecaption"));
        assertEquals("R\\|1|2", records.get(0).getFields().get("__sourceKey"));
        assertEquals(FIRST_CHANGED, records.get(0).getSourceTimestamp());
        Execution execution = DRIVER.executions.get(0);
        assertEquals("SELECT * FROM route_rows ORDER BY route_id, sequence_no", execution.sql());
        assertVendorNeutral(execution.sql());
        assertEquals("reader", execution.properties().getProperty("user"));
        assertEquals("secret", execution.properties().getProperty("password"));
    }

    @Test
    void rowReadAllRemainsLazyStreamingAndClosesResourcesAtExhaustion() throws Exception {
        JdbcSourceAdapter adapter = adapter("""
                objects:
                  route-row:
                    table: route_rows
                    keyFields: [route_id, sequence_no]
                """);

        Iterable<SourceRecord> records = adapter.readAll("route-row");
        assertTrue(DRIVER.executions.isEmpty());

        Iterator<SourceRecord> iterator = records.iterator();
        assertEquals(1, DRIVER.executions.size());
        assertEquals(0, DRIVER.connectionCloseCount);
        iterator.next();
        assertEquals(0, DRIVER.connectionCloseCount);
        iterator.next();
        assertFalse(iterator.hasNext());
        assertEquals(1, DRIVER.statementCloseCount);
        assertEquals(1, DRIVER.connectionCloseCount);
    }

    @Test
    void groupFirstFullReadUsesSharedCompositionRules() throws Exception {
        JdbcSourceAdapter adapter = adapter("""
                objects:
                  route-first:
                    table: compose_rows
                    keyFields: [route_id, sequence_no]
                    recordMode: group_first
                    groupBy: [route_id]
                    orderBy: sequence_no
                """);

        List<SourceRecord> records = list(adapter.readAll("route-first"));

        assertEquals(List.of("R1|1", "R2|1"),
                records.stream().map(SourceRecord::getSourceKey).toList());
        assertEquals("p1", records.get(0).getFields().get("MixedCaseCaption"));
    }

    @Test
    void adjacentNextFullReadCreatesCurrentAndNextLogicalRows() throws Exception {
        JdbcSourceAdapter adapter = adapter("""
                objects:
                  route-adjacent:
                    table: compose_rows
                    keyFields: [route_id, sequence_no]
                    recordMode: adjacent_next
                    groupBy: [route_id]
                    orderBy: sequence_no
                """);

        List<SourceRecord> records = list(adapter.readAll("route-adjacent"));

        assertEquals(List.of("R1|1", "R1|2"),
                records.stream().map(SourceRecord::getSourceKey).toList());
        assertEquals("p1", records.get(0).getFields().get("MixedCaseCaption"));
        @SuppressWarnings("unchecked")
        Map<String, Object> next = (Map<String, Object>) records.get(0).getFields().get("next");
        assertEquals("p2", next.get("MixedCaseCaption"));
        assertEquals("R1|2", next.get("__sourceKey"));
    }

    @Test
    void nonRowReadByKeyMatchesComposedLogicalRecord() throws Exception {
        JdbcSourceAdapter adapter = adapter("""
                objects:
                  route-adjacent:
                    table: compose_rows
                    keyFields: [route_id, sequence_no]
                    recordMode: adjacent_next
                    groupBy: [route_id]
                    orderBy: sequence_no
                """);

        SourceRecord record = adapter.readByKey("route-adjacent", "R1|1").orElseThrow();

        @SuppressWarnings("unchecked")
        Map<String, Object> next = (Map<String, Object>) record.getFields().get("next");
        assertEquals("p2", next.get("MixedCaseCaption"));
        assertEquals("SELECT * FROM compose_rows ORDER BY route_id, sequence_no",
                DRIVER.executions.get(0).sql());
    }

    @Test
    void nonRowWithWatermarkIsRejectedDuringAdapterInitialization() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> adapter("""
                objects:
                  route-adjacent:
                    table: compose_rows
                    keyFields: [route_id, sequence_no]
                    recordMode: adjacent_next
                    groupBy: [route_id]
                    orderBy: sequence_no
                    watermarkField: modified_at
                """));

        assertTrue(error.getMessage().contains(
                "JDBC 非 row 组合模式当前不支持 watermark 增量，请使用 row 模式或去除 watermarkField 后执行 full sync。"));
        assertTrue(DRIVER.executions.isEmpty());
    }

    @Test
    void readByKeyAndChangedScanUsePreparedStatementParameters() throws Exception {
        JdbcSourceAdapter adapter = adapter("""
                objects:
                  route-row:
                    view: current_route_rows
                    keyFields: [route_id, sequence_no]
                    watermarkField: modified_at
                """);

        SourceRecord selected = adapter.readByKey("route-row", "R\\|1|2").orElseThrow();
        List<SourceRecord> changed = list(adapter.scanChangedSince("route-row", FIRST_CHANGED));

        assertEquals("alpha", selected.getFields().get("MixedCaseCaption"));
        assertEquals(List.of("R\\\\2|3"), changed.stream().map(SourceRecord::getSourceKey).toList());
        Execution byKey = DRIVER.executions.get(0);
        assertEquals("SELECT * FROM current_route_rows WHERE route_id = ? AND sequence_no = ?", byKey.sql());
        assertVendorNeutral(byKey.sql());
        assertEquals(List.of("R|1", "2"), byKey.parameters());
        assertFalse(byKey.sql().contains("R|1"));
        Execution scan = DRIVER.executions.get(1);
        assertEquals("SELECT * FROM current_route_rows WHERE modified_at > ? ORDER BY route_id, sequence_no",
                scan.sql());
        assertVendorNeutral(scan.sql());
        assertEquals(List.of(Timestamp.from(FIRST_CHANGED)), scan.parameters());
    }

    @ParameterizedTest
    @ValueSource(ints = {Types.DATE, Types.TIMESTAMP})
    void bindsDateAndTimestampWatermarksAsPreparedStatementTimestamps(int watermarkJdbcType) throws Exception {
        DRIVER.watermarkJdbcType = watermarkJdbcType;
        JdbcSourceAdapter adapter = adapter("""
                objects:
                  route-row:
                    table: ATM_SCHEMA.ROUTE_ROWS
                    keyFields: [route_id, sequence_no]
                    watermarkField: modified_at
                """);

        list(adapter.scanChangedSince("route-row", FIRST_CHANGED));

        Execution execution = DRIVER.executions.get(0);
        assertInstanceOf(Timestamp.class, execution.parameters().get(0));
        assertEquals(Timestamp.from(FIRST_CHANGED), execution.parameters().get(0));
        assertVendorNeutral(execution.sql());
    }

    @Test
    void rejectsUnsafeIdentifiersAndRequiresWatermarkForChangedScan() throws Exception {
        IllegalArgumentException unsafe = assertThrows(IllegalArgumentException.class, () -> adapter("""
                objects:
                  route-row:
                    table: route_rows; DROP TABLE route_rows
                    keyFields: [route_id]
                """));
        assertTrue(unsafe.getMessage().contains("table"));

        IllegalArgumentException unsafeGroup = assertThrows(IllegalArgumentException.class, () -> adapter("""
                objects:
                  route-row:
                    table: route_rows
                    keyFields: [route_id]
                    recordMode: adjacent_next
                    groupBy: [route_id;drop]
                    orderBy: sequence_no
                """));
        assertTrue(unsafeGroup.getMessage().contains("groupBy"));

        JdbcSourceAdapter withoutWatermark = adapter("""
                objects:
                  route-row:
                    table: route_rows
                    keyFields: [route_id]
                """);
        IllegalStateException missing = assertThrows(IllegalStateException.class,
                () -> withoutWatermark.scanChangedSince("route-row", FIRST_CHANGED));
        assertTrue(missing.getMessage().contains("watermarkField"));
        assertThrows(IllegalArgumentException.class,
                () -> withoutWatermark.readByKey("route-row", "R\\x"));
        assertTrue(DRIVER.executions.isEmpty());
    }

    @Test
    void fieldPathsUsesMetadataOnlyAndReturnsAdjacentLogicalPaths() throws Exception {
        JdbcSourceAdapter adapter = adapter("""
                objects:
                  route-adjacent:
                    table: compose_rows
                    keyFields: [route_id, sequence_no]
                    recordMode: adjacent_next
                    groupBy: [route_id]
                    orderBy: sequence_no
                """);
        assertEquals(List.of("route_id", "sequence_no", "MixedCaseCaption", "modified_at",
                "current.route_id", "next.route_id", "current.sequence_no", "next.sequence_no",
                "current.MixedCaseCaption", "next.MixedCaseCaption", "current.modified_at", "next.modified_at"),
                adapter.fieldPaths("route-adjacent"));
        assertEquals("SELECT * FROM compose_rows WHERE 1 = 0", DRIVER.executions.get(0).sql());
    }

    private JdbcSourceAdapter adapter(String objectConfiguration) throws Exception {
        Path config = temp.resolve("sources.yaml");
        Files.writeString(config, """
                sources:
                  - sourceId: jdbc-main
                    adapter: jdbc
                    driver: org.atmkg.infra.source.jdbc.JdbcSourceAdapterTest$FakeDriver
                    url: jdbc:atmkg-fake:test
                    usernameEnv: JDBC_TEST_USERNAME
                    passwordEnv: JDBC_TEST_PASSWORD
                    fetchSize: 50
                """ + indent(objectConfiguration, 4));
        ConfiguredSource source = SourceConfig.load(config).requireSource("jdbc-main");
        return new JdbcSourceAdapter(source, Map.of(
                "JDBC_TEST_USERNAME", "reader",
                "JDBC_TEST_PASSWORD", "secret"));
    }

    private static String indent(String value, int spaces) {
        String prefix = " ".repeat(spaces);
        return value.lines().map(line -> prefix + line).reduce("", (left, right) -> left + right + "\n");
    }

    private static List<SourceRecord> list(Iterable<SourceRecord> source) {
        List<SourceRecord> records = new ArrayList<>();
        source.forEach(records::add);
        return records;
    }

    private static void assertVendorNeutral(String sql) {
        String normalized = sql.toUpperCase(Locale.ROOT);
        assertFalse(normalized.matches("(?s).*\\b(LIMIT|TOP|ROWNUM|NVL|DUAL)\\b.*"));
        assertFalse(normalized.contains("FETCH FIRST"));
        assertFalse(normalized.contains("/*+"));
        assertFalse(sql.contains("`"));
    }

    private record Execution(String sql, List<Object> parameters, Properties properties) {}

    public static final class FakeDriver implements Driver {
        private final List<Execution> executions = new ArrayList<>();
        private boolean rejectSetReadOnly;
        private int watermarkJdbcType = Types.TIMESTAMP;
        private int connectionCloseCount;
        private int statementCloseCount;

        @Override
        public Connection connect(String url, Properties info) {
            if (!acceptsURL(url)) return null;
            Properties connectionProperties = new Properties();
            connectionProperties.putAll(info);
            return connection(connectionProperties);
        }

        @Override public boolean acceptsURL(String url) { return url != null && url.startsWith("jdbc:atmkg-fake:"); }
        @Override public DriverPropertyInfo[] getPropertyInfo(String url, Properties info) { return new DriverPropertyInfo[0]; }
        @Override public int getMajorVersion() { return 1; }
        @Override public int getMinorVersion() { return 0; }
        @Override public boolean jdbcCompliant() { return false; }
        @Override public Logger getParentLogger() { return Logger.getGlobal(); }

        private Connection connection(Properties properties) {
            return (Connection) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{Connection.class},
                    (proxy, method, args) -> switch (method.getName()) {
                        case "prepareStatement" -> statement((String) args[0], properties);
                        case "setReadOnly" -> {
                            if (rejectSetReadOnly) throw new SQLException("setReadOnly is not supported");
                            yield null;
                        }
                        case "close" -> {
                            connectionCloseCount++;
                            yield null;
                        }
                        case "isClosed" -> false;
                        default -> defaultValue(method.getReturnType());
                    });
        }

        private PreparedStatement statement(String sql, Properties properties) {
            Map<Integer, Object> parameters = new LinkedHashMap<>();
            return (PreparedStatement) Proxy.newProxyInstance(getClass().getClassLoader(),
                    new Class<?>[]{PreparedStatement.class}, (proxy, method, args) -> {
                        return switch (method.getName()) {
                            case "setString", "setObject", "setTimestamp" -> {
                                parameters.put((Integer) args[0], args[1]);
                                yield null;
                            }
                            case "executeQuery" -> {
                                List<Object> ordered = parameters.entrySet().stream()
                                        .sorted(Map.Entry.comparingByKey()).map(Map.Entry::getValue).toList();
                                executions.add(new Execution(sql, ordered, copy(properties)));
                                yield rows(sql, ordered);
                            }
                            case "close" -> {
                                statementCloseCount++;
                                yield null;
                            }
                            case "setFetchSize", "setMaxRows" -> null;
                            case "isClosed" -> false;
                            default -> defaultValue(method.getReturnType());
                        };
                    });
        }

        private CachedRowSet rows(String sql, List<Object> parameters) throws SQLException {
            List<List<Object>> data = sql.contains("compose_rows")
                    ? List.of(
                            List.of("R1", 2, "p2", watermarkValue(FIRST_CHANGED)),
                            List.of("R1", 1, "p1", watermarkValue(FIRST_CHANGED)),
                            List.of("R1", 3, "p3", watermarkValue(SECOND_CHANGED)),
                            List.of("R2", 1, "q1", watermarkValue(FIRST_CHANGED)))
                    : List.of(
                            List.of("R|1", 2, "alpha", watermarkValue(FIRST_CHANGED)),
                            List.of("R\\2", 3, "bravo", watermarkValue(SECOND_CHANGED)));
            if (sql.contains("route_id = ?")) {
                data = data.stream().filter(row -> row.get(0).equals(parameters.get(0))
                        && String.valueOf(row.get(1)).equals(parameters.get(1))).toList();
            } else if (sql.contains("modified_at > ?")) {
                Timestamp since = (Timestamp) parameters.get(0);
                data = data.stream().filter(row -> ((java.util.Date) row.get(3)).getTime() > since.getTime()).toList();
            }
            RowSetMetaDataImpl metadata = new RowSetMetaDataImpl();
            metadata.setColumnCount(4);
            metadata.setColumnName(1, "route_id"); metadata.setColumnType(1, Types.VARCHAR);
            metadata.setColumnName(2, "sequence_no"); metadata.setColumnType(2, Types.NUMERIC);
            metadata.setColumnName(3, "MixedCaseCaption"); metadata.setColumnLabel(3, "MixedCaseCaption");
            metadata.setColumnType(3, Types.VARCHAR);
            metadata.setColumnName(4, "modified_at"); metadata.setColumnType(4, watermarkJdbcType);
            CachedRowSet rowSet = RowSetProvider.newFactory().createCachedRowSet();
            rowSet.setMetaData(metadata);
            for (int rowIndex = data.size() - 1; rowIndex >= 0; rowIndex--) {
                List<Object> row = data.get(rowIndex);
                rowSet.moveToInsertRow();
                for (int i = 0; i < row.size(); i++) rowSet.updateObject(i + 1, row.get(i));
                rowSet.insertRow();
                rowSet.moveToCurrentRow();
            }
            rowSet.beforeFirst();
            return rowSet;
        }

        private Object watermarkValue(Instant value) {
            if (watermarkJdbcType == Types.DATE) {
                return new java.sql.Date(Timestamp.from(value).getTime());
            }
            return Timestamp.from(value);
        }

        private static Properties copy(Properties source) {
            Properties copy = new Properties();
            copy.putAll(source);
            return copy;
        }

        private static Object defaultValue(Class<?> type) {
            if (!type.isPrimitive()) return null;
            if (type == boolean.class) return false;
            if (type == byte.class) return (byte) 0;
            if (type == short.class) return (short) 0;
            if (type == int.class) return 0;
            if (type == long.class) return 0L;
            if (type == float.class) return 0F;
            if (type == double.class) return 0D;
            if (type == char.class) return '\0';
            return null;
        }
    }
}
