package org.atmkg.infra.query;

import java.lang.reflect.Proxy;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Stream;
import org.neo4j.driver.Driver;
import org.neo4j.driver.Query;
import org.neo4j.driver.Record;
import org.neo4j.driver.Result;
import org.neo4j.driver.Session;
import org.neo4j.driver.SessionConfig;
import org.neo4j.driver.Values;
import org.neo4j.driver.internal.InternalRecord;
import org.neo4j.driver.summary.QueryType;
import org.neo4j.driver.summary.ResultSummary;

/** Small deterministic driver boundary used by query-infrastructure tests. */
final class Neo4jTestDriver {
    record Call(String query, Map<String, Object> parameters) {}

    private final Deque<Result> results = new ArrayDeque<>();
    private final List<Call> calls = new ArrayList<>();
    private SessionConfig sessionConfig;

    void enqueue(Result result) {
        results.addLast(result);
    }

    List<Call> calls() {
        return List.copyOf(calls);
    }

    SessionConfig sessionConfig() {
        return sessionConfig;
    }

    Driver driver() {
        Session session = (Session) Proxy.newProxyInstance(
                Session.class.getClassLoader(), new Class<?>[]{Session.class}, (proxy, method, args) -> {
                    if ("run".equals(method.getName())) {
                        String query = queryText(args[0]);
                        Map<String, Object> parameters = parameters(args);
                        calls.add(new Call(query, parameters));
                        if (results.isEmpty()) throw new AssertionError("没有为查询准备结果：" + query);
                        return results.removeFirst();
                    }
                    if ("close".equals(method.getName())) return null;
                    if ("isOpen".equals(method.getName())) return true;
                    return defaultValue(method.getReturnType());
                });
        return (Driver) Proxy.newProxyInstance(
                Driver.class.getClassLoader(), new Class<?>[]{Driver.class}, (proxy, method, args) -> {
                    if ("session".equals(method.getName())) {
                        if (args != null) {
                            for (Object arg : args) if (arg instanceof SessionConfig config) sessionConfig = config;
                        }
                        return session;
                    }
                    if ("close".equals(method.getName()) || "verifyConnectivity".equals(method.getName())) return null;
                    return defaultValue(method.getReturnType());
                });
    }

    static Record record(Map<String, Object> fields) {
        List<String> keys = new ArrayList<>(fields.keySet());
        return new InternalRecord(keys, keys.stream().map(key -> Values.value(fields.get(key))).toList());
    }

    static Result result(QueryType queryType, Record... records) {
        return new TestResult(queryType, List.of(records));
    }

    private static String queryText(Object value) {
        if (value instanceof String text) return text;
        if (value instanceof Query query) return query.text();
        throw new AssertionError("未知 query 参数：" + value);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> parameters(Object[] args) {
        if (args == null) return Map.of();
        for (Object arg : args) {
            if (arg instanceof Map<?, ?> map) return new LinkedHashMap<>((Map<String, Object>) map);
            if (arg instanceof Query query) return query.parameters().asMap();
        }
        return Map.of();
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

    private static final class TestResult implements Result {
        private final QueryType queryType;
        private final List<Record> records;
        private int index;

        TestResult(QueryType queryType, List<Record> records) {
            this.queryType = queryType;
            this.records = records;
        }

        @Override public List<String> keys() {
            return records.isEmpty() ? List.of() : records.get(0).keys();
        }
        @Override public boolean hasNext() { return index < records.size(); }
        @Override public Record next() { return records.get(index++); }
        @Override public Record single() {
            if (records.size() != 1) throw new IllegalStateException("结果不是单行");
            index = records.size();
            return records.get(0);
        }
        @Override public Record peek() { return records.get(index); }
        @Override public Stream<Record> stream() {
            List<Record> remaining = list();
            return remaining.stream();
        }
        @Override public List<Record> list() {
            List<Record> remaining = new ArrayList<>(records.subList(index, records.size()));
            index = records.size();
            return remaining;
        }
        @Override public <T> List<T> list(Function<Record, T> mapper) {
            return list().stream().map(mapper).toList();
        }
        @Override public ResultSummary consume() {
            index = records.size();
            return (ResultSummary) Proxy.newProxyInstance(
                    ResultSummary.class.getClassLoader(), new Class<?>[]{ResultSummary.class},
                    (proxy, method, args) -> {
                        if ("queryType".equals(method.getName())) return queryType;
                        if (List.class.isAssignableFrom(method.getReturnType())) return List.of();
                        if (Set.class.isAssignableFrom(method.getReturnType())) return Set.of();
                        return defaultValue(method.getReturnType());
                    });
        }
        @Override public boolean isOpen() { return index < records.size(); }
        @Override public void remove() { throw new UnsupportedOperationException(); }
        @Override public void forEachRemaining(java.util.function.Consumer<? super Record> action) {
            while (hasNext()) action.accept(next());
        }
    }
}
