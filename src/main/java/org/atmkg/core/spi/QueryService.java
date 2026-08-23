package org.atmkg.core.spi;

import org.atmkg.core.model.GraphDTO;
import org.atmkg.core.model.QuerySpec;

public interface QueryService {
    GraphDTO query(QuerySpec spec);
}
