package org.atmkg.core.error;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** Safe explicit failure returned by the thin entity-lookup boundary. */
public final class EntityLookupException extends RuntimeException {
    private final int status;
    private final String code;
    private final Map<String, Object> details;

    public EntityLookupException(int status, String code, String message, Map<String, Object> details) {
        super(message);
        this.status = status;
        this.code = code;
        this.details = Collections.unmodifiableMap(new LinkedHashMap<>(details));
    }

    public int getStatus() { return status; }
    public String getCode() { return code; }
    public Map<String, Object> getDetails() { return details; }
}
