package org.atmkg.core.spi;

import org.atmkg.core.model.MappingResult;
import org.atmkg.core.model.SourceRecord;

public interface MappingEngine {
    MappingResult map(SourceRecord record);
}
