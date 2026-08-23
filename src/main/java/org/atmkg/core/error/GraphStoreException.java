package org.atmkg.core.error;

public final class GraphStoreException extends RuntimeException {
    public GraphStoreException(String message) { super(message); }
    public GraphStoreException(String message, Throwable cause) { super(message, cause); }
}
