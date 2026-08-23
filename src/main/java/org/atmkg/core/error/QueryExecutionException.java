package org.atmkg.core.error;

public final class QueryExecutionException extends RuntimeException {
    public QueryExecutionException(String message) { super(message); }
    public QueryExecutionException(String message, Throwable cause) { super(message, cause); }
}
