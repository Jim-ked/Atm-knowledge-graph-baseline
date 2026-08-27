package org.atmkg.core.spi;

import org.atmkg.core.model.CypherResultDTO;

/** Viewer raw Cypher 的最小边界；不加入 QueryService/QuerySpec 命名查询主链。 */
@FunctionalInterface
public interface ReadOnlyCypherService {
    CypherResultDTO execute(String cypher);
}
