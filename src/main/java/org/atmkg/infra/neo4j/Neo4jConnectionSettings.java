package org.atmkg.infra.neo4j;

import java.util.Map;
import java.util.Objects;

/** Explicit Neo4j connection settings. No localhost/default-database fallback is allowed. */
public final class Neo4jConnectionSettings {
    private final String uri;
    private final String database;
    private final String username;
    private final String password;
    private final String projectId;
    private final int batchSize;

    public Neo4jConnectionSettings(String uri, String database, String username, String password,
                                   String projectId, int batchSize) {
        this.uri = requireText(uri, "uri");
        this.database = requireText(database, "database");
        this.username = requireText(username, "username");
        this.password = Objects.requireNonNull(password, "password");
        this.projectId = requireText(projectId, "projectId");
        if (batchSize <= 0) throw new IllegalArgumentException("batchSize 必须大于 0");
        this.batchSize = batchSize;
    }

    public static Neo4jConnectionSettings fromEnvironment(String projectId, int batchSize) {
        return fromMap(System.getenv(), projectId, batchSize);
    }

    static Neo4jConnectionSettings fromMap(Map<String, String> env, String projectId, int batchSize) {
        return new Neo4jConnectionSettings(
                requiredEnv(env, "ATMKG_NEO4J_URI"),
                requiredEnv(env, "ATMKG_NEO4J_DATABASE"),
                requiredEnv(env, "ATMKG_NEO4J_USERNAME"),
                requiredEnv(env, "ATMKG_NEO4J_PASSWORD"),
                projectId,
                batchSize);
    }

    private static String requiredEnv(Map<String, String> env, String name) {
        String value = env.get(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("缺少必需环境变量：" + name);
        }
        return value;
    }

    private static String requireText(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) throw new IllegalArgumentException(name + " 不能为空");
        return value;
    }

    public String getUri() { return uri; }
    public String getDatabase() { return database; }
    public String getUsername() { return username; }
    public String getPassword() { return password; }
    public String getProjectId() { return projectId; }
    public int getBatchSize() { return batchSize; }
}
