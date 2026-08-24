package org.atmkg.core.error;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** 明确、安全地向 HTTP 层传递只读 Cypher 的用户可见失败。 */
public final class ReadOnlyCypherException extends RuntimeException {
    private final int status;
    private final String code;
    private final Map<String, Object> details;

    public ReadOnlyCypherException(int status, String code, String message) {
        this(status, code, message, Map.of());
    }

    public ReadOnlyCypherException(int status, String code, String message, Map<String, Object> details) {
        super(message);
        this.status = status;
        this.code = code;
        this.details = Collections.unmodifiableMap(new LinkedHashMap<>(details));
    }

    public int getStatus() { return status; }
    public String getCode() { return code; }
    public Map<String, Object> getDetails() { return details; }
}
