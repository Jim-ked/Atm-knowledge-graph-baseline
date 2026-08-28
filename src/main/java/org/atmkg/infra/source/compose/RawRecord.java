package org.atmkg.infra.source.compose;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** One physically read source row before logical record composition. */
public record RawRecord(Map<String, Object> fields, Instant sourceTimestamp, String location) {
    public RawRecord {
        fields = Collections.unmodifiableMap(new LinkedHashMap<>(Objects.requireNonNull(fields, "fields")));
        if (location == null || location.isBlank()) throw new IllegalArgumentException("location 不能为空");
        location = location.trim();
    }
}
