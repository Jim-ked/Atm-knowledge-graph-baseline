package org.atmkg.service.query;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.atmkg.core.error.QueryExecutionException;
import org.atmkg.core.model.GraphDTO;
import org.atmkg.core.model.QuerySpec;
import org.atmkg.core.spi.QueryService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TemplateAwareQueryServiceTest {
    @TempDir
    Path tempDir;

    @Test
    void namedQueryIsResolvedBeforeDelegation() throws IOException {
        RecordingQueryService delegate = new RecordingQueryService();
        TemplateAwareQueryService service = new TemplateAwareQueryService(delegate, registry());
        QuerySpec named = new QuerySpec(QuerySpec.Type.NAMED, "runtime-start", null, null,
                Set.of("ignored:relationship"), Set.of("ignored:class"), QuerySpec.Direction.OUTGOING,
                "direct", Map.of("ignored", true));

        GraphDTO result = service.query(named);

        assertSame(delegate.result, result);
        assertEquals(QuerySpec.Type.NEIGHBORS, delegate.lastSpec.getType());
        assertEquals("runtime-start", delegate.lastSpec.getStartUid());
        assertEquals(QuerySpec.Direction.INCOMING, delegate.lastSpec.getDirection());
        assertEquals(Set.of("urn:test:departs"), delegate.lastSpec.getRelationshipTypes());
        assertEquals(Set.of("urn:test:Flight"), delegate.lastSpec.getClassFilters());
    }

    @Test
    void ordinaryQueryIsDelegatedAsTheSameObject() throws IOException {
        RecordingQueryService delegate = new RecordingQueryService();
        TemplateAwareQueryService service = new TemplateAwareQueryService(delegate, registry());
        QuerySpec ordinary = new QuerySpec(QuerySpec.Type.ENTITY, "entity-uid", null, null,
                Set.of(), Set.of(), QuerySpec.Direction.BOTH, null, Map.of());

        service.query(ordinary);

        assertSame(ordinary, delegate.lastSpec);
    }

    @Test
    void unknownQueryIdAndMissingRuntimeInputsFailBeforeDelegation() throws IOException {
        RecordingQueryService delegate = new RecordingQueryService();
        TemplateAwareQueryService service = new TemplateAwareQueryService(delegate, registry());

        assertThrows(QueryExecutionException.class, () -> service.query(named("unknown", "uid")));
        assertThrows(QueryExecutionException.class, () -> service.query(named(null, "uid")));
        assertThrows(QueryExecutionException.class, () -> service.query(named("direct", " ")));
        assertEquals(0, delegate.calls);
    }

    private QueryTemplateRegistry registry() throws IOException {
        Path file = tempDir.resolve("query-templates.yaml");
        Files.writeString(file, """
                templates:
                  direct:
                    type: NEIGHBORS
                    direction: INCOMING
                    relationshipTypes: [urn:test:departs]
                    classFilters: [urn:test:Flight]
                """);
        return QueryTemplateRegistry.load(file);
    }

    private QuerySpec named(String queryId, String startUid) {
        return new QuerySpec(QuerySpec.Type.NAMED, startUid, null, null,
                Set.of(), Set.of(), QuerySpec.Direction.BOTH, queryId, Map.of());
    }

    private static final class RecordingQueryService implements QueryService {
        private final GraphDTO result = new GraphDTO("1", List.of(), List.of(), Map.of());
        private QuerySpec lastSpec;
        private int calls;

        @Override
        public GraphDTO query(QuerySpec spec) {
            calls++;
            lastSpec = spec;
            return result;
        }
    }
}
