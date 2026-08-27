package org.atmkg.infra.identity;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Map;
import org.atmkg.core.ProjectConstants;
import org.atmkg.core.model.SourceRecord;
import org.atmkg.core.model.mapping.RelationshipMappingSpec;
import org.junit.jupiter.api.Test;

class DeterministicIdentityResolverTest {
    @Test
    void fixedProjectIdentityPreservesExistingEntityAndRelationshipUids() {
        assertEquals("atm-knowledge-graph", ProjectConstants.PROJECT_ID);
        assertEquals("urn:atm-knowledge-graph:", ProjectConstants.IDENTITY_NAMESPACE);

        DeterministicIdentityResolver resolver =
                new DeterministicIdentityResolver(ProjectConstants.IDENTITY_NAMESPACE);
        RelationshipMappingSpec hasRunway = new RelationshipMappingSpec(
                "urn:atm-knowledge-graph:hasRunway",
                "urn:atm-knowledge-graph:Airport",
                "urn:atm-knowledge-graph:Runway",
                "source", "runway", "airportCode", "runwayCode", "");
        SourceRecord record = new SourceRecord("source", "runway", "RWY01", Map.of(), null);

        assertEquals(
                "urn:atm-knowledge-graph:entity:urn%3Aatm-knowledge-graph%3AAirport:ZBAA",
                resolver.entityUid("urn:atm-knowledge-graph:Airport", "ZBAA"));
        assertEquals(
                "urn:atm-knowledge-graph:rel:urn%3Aatm-knowledge-graph%3AhasRunway:source-uid:target-uid",
                resolver.relationshipUid(hasRunway, "source-uid", "target-uid", record));
    }

    @Test
    void entityUidDependsOnlyOnClassAndTrimmedBusinessKey() {
        DeterministicIdentityResolver resolver =
                new DeterministicIdentityResolver(ProjectConstants.IDENTITY_NAMESPACE);

        String fromSourceA = resolver.entityUid("urn:atm-knowledge-graph:Airport", " ZBAA ");
        String fromSourceB = resolver.entityUid("urn:atm-knowledge-graph:Airport", "ZBAA");

        assertEquals(fromSourceA, fromSourceB);
        assertEquals(
                "urn:atm-knowledge-graph:entity:urn%3Aatm-knowledge-graph%3AAirport:ZBAA",
                fromSourceA);
    }
}
