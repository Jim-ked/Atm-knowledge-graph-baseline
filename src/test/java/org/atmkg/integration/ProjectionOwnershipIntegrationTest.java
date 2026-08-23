package org.atmkg.integration;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.atmkg.core.model.GraphEntity;
import org.atmkg.core.model.GraphRelationship;
import org.atmkg.core.model.MappingResult;
import org.atmkg.core.model.SourceRef;
import org.atmkg.testsupport.InMemoryGraphStore;
import org.junit.jupiter.api.Test;

class ProjectionOwnershipIntegrationTest {
    @Test
    void deletingRecordRefusesToDetachRelationshipOwnedByAnotherRecord() {
        InMemoryGraphStore store = new InMemoryGraphStore();
        SourceRef entityOwner = new SourceRef("fixture", "PARENT", "P1");
        SourceRef relationshipOwner = new SourceRef("fixture", "CHILD", "C1");
        GraphEntity parent = new GraphEntity("P1", "urn:test:Parent", "P1", Map.of(), provenance(entityOwner));
        GraphEntity child = new GraphEntity("C1", "urn:test:Child", "C1", Map.of(), provenance(relationshipOwner));
        GraphRelationship relationship = new GraphRelationship(
                "R1", "urn:test:contains", "P1", "C1", Map.of(), provenance(relationshipOwner));
        store.upsertEntities(List.of(parent, child));
        store.upsertRelationships(List.of(relationship));

        assertThrows(IllegalStateException.class, () -> store.deleteProjection(entityOwner));

        assertTrue(store.findEntity("P1").isPresent());
        assertTrue(store.relationships().stream().anyMatch(value -> value.getUid().equals("R1")));
    }

    private Map<String, Object> provenance(SourceRef ref) {
        return Map.of(
                "sourceId", ref.getSourceId(),
                "sourceObject", ref.getObjectName(),
                "sourceKey", ref.getSourceKey());
    }
}
