package org.atmkg.core.spi;

import org.atmkg.core.model.GraphDTO;

/** Resolves user-facing business keys to canonical graph entities without changing UID semantics. */
@FunctionalInterface
public interface EntityLookupService {
    GraphDTO lookup(String key, String classIri);
}
