package org.atmkg.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.atmkg.core.model.GraphProjectionSnapshot;
import org.junit.jupiter.api.Test;

class GraphProjectionSnapshotTest {
    @Test
    void keepsStableDistinctUidSummariesAndDoesNotExposeMutableInputs() {
        List<String> entities = new ArrayList<>(List.of("U1", "U1", "U2"));
        GraphProjectionSnapshot snapshot = new GraphProjectionSnapshot(
                entities, List.of("R1", "R1"), List.of("U1", "U3", "U1"));
        entities.clear();

        assertEquals(List.of("U1", "U2"), snapshot.getEntityUids());
        assertEquals(List.of("R1"), snapshot.getRelationshipUids());
        assertEquals(List.of("U1", "U3"), snapshot.getAnchorEntityUids());
    }

    @Test
    void emptySnapshotContainsNoSyntheticHistory() {
        GraphProjectionSnapshot snapshot = GraphProjectionSnapshot.empty();

        assertTrue(snapshot.getEntityUids().isEmpty());
        assertTrue(snapshot.getRelationshipUids().isEmpty());
        assertTrue(snapshot.getAnchorEntityUids().isEmpty());
    }
}
