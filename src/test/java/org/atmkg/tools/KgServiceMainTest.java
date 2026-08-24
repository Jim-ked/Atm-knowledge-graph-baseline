package org.atmkg.tools;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import org.atmkg.core.model.GraphDTO;
import org.atmkg.core.model.GraphEntity;
import org.atmkg.core.model.GraphNodeDTO;
import org.atmkg.core.model.MappingResult;
import org.atmkg.core.model.SourceRef;
import org.atmkg.core.spi.QueryService;
import org.atmkg.service.query.QueryTemplateRegistry;
import org.atmkg.service.query.TemplateAwareQueryService;
import org.atmkg.service.sync.GraphChangeNotice;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class KgServiceMainTest {
    @TempDir Path temp;

    @Test
    void formalGraphChangeAssemblyLoadsRulesAndReportsProjectedResult() {
        QueryService delegate = spec -> {
            GraphNodeDTO airport = new GraphNodeDTO(spec.getStartUid(), List.of("Airport"),
                    "Airport", "ZBAA", Map.of());
            return new GraphDTO("1", List.of(airport), List.of(), Map.of());
        };
        QueryService query = new TemplateAwareQueryService(delegate,
                QueryTemplateRegistry.load(Path.of("queries/query-templates.yaml")));
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        Consumer<GraphChangeNotice> listener = KgServiceMain.graphChangeListener(
                Path.of(".").toAbsolutePath().normalize(), query,
                new PrintStream(bytes, true, StandardCharsets.UTF_8));
        GraphChangeNotice notice = GraphChangeNotice.forUpsert(
                new SourceRef("jdbc-main", "airport-base", "ZBAA"),
                new MappingResult(List.of(new GraphEntity(
                        "U1", "urn:test:Airport", "ZBAA", Map.of(), Map.of())), List.of()),
                Instant.parse("2026-08-24T12:00:00Z"));

        listener.accept(notice);

        String output = bytes.toString(StandardCharsets.UTF_8);
        assertTrue(output.contains("[CHANGE] UPSERT"));
        assertTrue(output.contains("neighborhood=QUERIED"));
        assertTrue(output.contains("associations=1"));
    }

    @Test
    void missingFormalChangeRulesFailAssemblyClearly() throws Exception {
        Files.createDirectories(temp.resolve("queries"));

        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> KgServiceMain.graphChangeListener(temp,
                        spec -> new GraphDTO("1", List.of(), List.of(), Map.of()), System.out));

        assertTrue(failure.getMessage().contains("变化关联配置"));
    }
}
