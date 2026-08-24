package org.atmkg.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.atmkg.core.model.MappingResult;
import org.atmkg.core.model.SourceRecord;
import org.atmkg.core.model.mapping.EntityMappingSpec;
import org.atmkg.core.model.mapping.MappingCatalog;
import org.atmkg.core.spi.SourceAdapter;
import org.atmkg.infra.identity.DeterministicIdentityResolver;
import org.atmkg.infra.mapping.DefaultMappingEngine;
import org.atmkg.infra.source.config.ConfiguredSource;
import org.atmkg.infra.source.config.SourceConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SourceMappingPreviewMainTest {
    @TempDir Path temp;

    @Test
    void jdbcSourceRecordReachesMappingEngineWithoutNeo4j() throws Exception {
        String classIri = "urn:atm-knowledge-graph:Route";
        MappingCatalog catalog = new MappingCatalog(
                List.of(new EntityMappingSpec(
                        classIri, "jdbc-main", "route", "routeCode", "class-local-business-key")),
                List.of(), List.of());
        DefaultMappingEngine engine = new DefaultMappingEngine(
                catalog, new DeterministicIdentityResolver("urn:test:preview:"));
        SourceAdapter adapter = new SingleRecordAdapter(new SourceRecord(
                "jdbc-main", "route", "R001", Map.of("routeCode", "R001"), null));

        List<MappingResult> results = SourceMappingPreviewMain.preview(
                jdbcSource(), "route", adapter, engine, 5);

        assertEquals(1, results.size());
        assertEquals(classIri, results.get(0).getEntities().get(0).getClassIri());
        assertEquals("urn:test:preview:entity:urn%3Aatm-knowledge-graph%3ARoute:R001",
                results.get(0).getEntities().get(0).getUid());
    }

    private ConfiguredSource jdbcSource() throws Exception {
        Path config = temp.resolve("sources.yaml");
        Files.writeString(config, """
                sources:
                  - sourceId: jdbc-main
                    adapter: jdbc
                    objects:
                      route:
                        table: ATM.ROUTE
                        keyFields: [routeCode]
                """);
        return SourceConfig.load(config).requireSource("jdbc-main");
    }

    private record SingleRecordAdapter(SourceRecord record) implements SourceAdapter {
        @Override public Iterable<SourceRecord> readAll(String objectName) { return List.of(record); }
        @Override public Optional<SourceRecord> readByKey(String objectName, String sourceKey) {
            return Optional.of(record);
        }
        @Override public Iterable<SourceRecord> scanChangedSince(String objectName, Instant since) {
            return List.of(record);
        }
    }
}
