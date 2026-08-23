package org.atmkg.core.spi;

import java.time.Instant;
import java.util.Optional;
import org.atmkg.core.model.SourceRecord;

/** Physical source access only; it must not decide ontology semantics. */
public interface SourceAdapter {
    Iterable<SourceRecord> readAll(String objectName);
    Optional<SourceRecord> readByKey(String objectName, String sourceKey);
    Iterable<SourceRecord> scanChangedSince(String objectName, Instant since);
}
