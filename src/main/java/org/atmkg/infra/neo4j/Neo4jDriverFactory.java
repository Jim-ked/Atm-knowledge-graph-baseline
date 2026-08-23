package org.atmkg.infra.neo4j;

import org.neo4j.driver.AuthTokens;
import org.neo4j.driver.Driver;
import org.neo4j.driver.GraphDatabase;

public final class Neo4jDriverFactory {
    private Neo4jDriverFactory() {}

    public static Driver create(Neo4jConnectionSettings settings) {
        return GraphDatabase.driver(settings.getUri(), AuthTokens.basic(settings.getUsername(), settings.getPassword()));
    }
}
