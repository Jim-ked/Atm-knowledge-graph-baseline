package org.atmkg.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Files;
import java.nio.file.Path;
import java.io.OutputStream;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.atmkg.core.model.MappingResult;
import org.atmkg.core.model.SourceRecord;
import org.atmkg.core.model.mapping.EntityMappingSpec;
import org.atmkg.core.model.mapping.MappingCatalog;
import org.atmkg.core.spi.SourceAdapter;
import org.atmkg.fixture.SourcePreviewMappingWorkbookGenerator;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.atmkg.infra.identity.DeterministicIdentityResolver;
import org.atmkg.infra.mapping.DefaultMappingEngine;
import org.atmkg.infra.mapping.PoiMappingRegistry;
import org.atmkg.infra.ontology.JenaOntologyService;
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
                        classIri, "jdbc-main", "route", "routeCode")),
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

    @Test
    void defaultPreviewWorkbookGeneratorUsesCurrentMappingContract() {
        Path workbook = temp.resolve("source-preview-mapping.xlsx");
        SourcePreviewMappingWorkbookGenerator.generate(workbook);

        MappingCatalog catalog = new PoiMappingRegistry().load(
                workbook,
                new JenaOntologyService().load(Path.of("ontology/atm_knowledge_graph.ttl")));

        assertEquals(10, catalog.getRelationships().size());
        assertEquals(1, catalog.relationshipMappingsFor(
                "preview-route-node", "route-node").size());
    }

    @Test
    void targetedCatalogIgnoresUnrelatedInvalidScope() throws Exception {
        Path workbook = temp.resolve("scoped.xlsx");
        SourcePreviewMappingWorkbookGenerator.generate(workbook);
        try (XSSFWorkbook book = new XSSFWorkbook(Files.newInputStream(workbook));
             OutputStream output = Files.newOutputStream(workbook)) {
            Sheet properties = book.getSheet("属性映射");
            Row row = properties.createRow(properties.getLastRowNum() + 1);
            String[] values = {"bad-source", "bad-object", "Route", "routeCode", "missingProperty", "", ""};
            for (int i = 0; i < values.length; i++) row.createCell(i).setCellValue(values[i]);
            book.write(output);
        }
        var schema = new JenaOntologyService().load(Path.of("ontology/atm_knowledge_graph.ttl"));
        var inspection = new PoiMappingRegistry().inspect(workbook, schema);
        MappingCatalog catalog = SourceMappingPreviewMain.catalogForScope(
                inspection, "preview-route-parent", "route-parent");
        assertEquals(1, catalog.entityMappingsFor("preview-route-parent", "route-parent").size());
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
