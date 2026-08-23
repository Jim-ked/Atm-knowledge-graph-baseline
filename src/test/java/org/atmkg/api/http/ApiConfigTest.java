package org.atmkg.api.http;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class ApiConfigTest {
    @Test
    void loadsTheSingleProjectApiConfiguration() {
        ApiConfig config = ApiConfig.load(Path.of("config/api.yaml"));

        assertEquals("127.0.0.1", config.getHost());
        assertEquals(18080, config.getPort());
        assertEquals("/api/v1", config.getBasePath());
        assertEquals("1", config.getSchemaVersion());
        assertEquals(8, config.getMaxDepth());
    }
}
