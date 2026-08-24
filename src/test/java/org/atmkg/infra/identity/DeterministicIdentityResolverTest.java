package org.atmkg.infra.identity;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Map;
import org.atmkg.core.ProjectConstants;
import org.atmkg.core.model.SourceRecord;
import org.atmkg.core.model.mapping.EntityMappingSpec;
import org.atmkg.core.model.mapping.RelationshipMappingSpec;
import org.junit.jupiter.api.Test;

class DeterministicIdentityResolverTest {
    @Test
    void fixedProjectIdentityPreservesExistingEntityAndRelationshipUids() {
        assertEquals("atm-knowledge-graph", ProjectConstants.PROJECT_ID);
        assertEquals("urn:atm-knowledge-graph:", ProjectConstants.IDENTITY_NAMESPACE);

        DeterministicIdentityResolver resolver =
                new DeterministicIdentityResolver(ProjectConstants.IDENTITY_NAMESPACE);
        EntityMappingSpec airport = new EntityMappingSpec(
                "urn:atm-knowledge-graph:Airport", "source", "airport", "airportCode",
                "class-local-business-key");
        RelationshipMappingSpec hasRunway = new RelationshipMappingSpec(
                "urn:atm-knowledge-graph:hasRunway",
                "urn:atm-knowledge-graph:Airport",
                "urn:atm-knowledge-graph:Runway",
                "source", "airportCode", "runwayCode", "");
        SourceRecord record = new SourceRecord("source", "runway", "RWY01", Map.of(), null);

        assertEquals(
                "urn:atm-knowledge-graph:entity:urn%3Aatm-knowledge-graph%3AAirport:ZBAA",
                resolver.entityUid(airport, "ZBAA"));
        assertEquals(
                "urn:atm-knowledge-graph:rel:urn%3Aatm-knowledge-graph%3AhasRunway:source-uid:target-uid",
                resolver.relationshipUid(hasRunway, "source-uid", "target-uid", record));
    }
}
