package org.atmkg.core;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Collections;
import org.atmkg.core.model.GraphDTO;
import org.atmkg.core.model.QuerySpec;
import org.junit.jupiter.api.Test;

class CoreModelTest {
    @Test
    void graphDtoKeepsSchemaVersion() {
        GraphDTO dto = new GraphDTO("1", Collections.emptyList(), Collections.emptyList(), Collections.emptyMap());
        assertEquals("1", dto.getSchemaVersion());
    }

    @Test
    void namedQueryCanRemainOutsideCoreSemantics() {
        QuerySpec spec = new QuerySpec(QuerySpec.Type.NAMED, null, null, null,
                Collections.emptySet(), Collections.emptySet(), QuerySpec.Direction.BOTH,
                "route-structure", Collections.emptyMap());
        assertEquals("route-structure", spec.getQueryId());
    }
}
