package org.atmkg.service.change;

import java.util.List;
import org.atmkg.core.model.GraphRelationship;

/**
 * Minimal future extension point for derived relationships.
 * A future SpatialTopologyPlugin may calculate relationships for one changed entity UID; persistence,
 * ownership, reconciliation, and GraphChangeNotice wiring are intentionally outside this contract.
 */
@FunctionalInterface
public interface DerivedRelationPlugin {
    List<GraphRelationship> derive(String changedEntityUid);
}
