package org.atmkg.fixture;

public enum FixtureScale {
    SMALL(5, 3, 6, 3),
    MEDIUM(50, 20, 10, 20),
    LARGE(1000, 200, 20, 200);

    final int airports;
    final int routes;
    final int nodesPerRoute;
    final int airspaces;

    FixtureScale(int airports, int routes, int nodesPerRoute, int airspaces) {
        this.airports = airports;
        this.routes = routes;
        this.nodesPerRoute = nodesPerRoute;
        this.airspaces = airspaces;
    }

    public static FixtureScale parse(String value) {
        return value == null ? SMALL : valueOf(value.trim().toUpperCase());
    }
}
