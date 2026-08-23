package org.atmkg.core.error;

public final class SyncException extends RuntimeException {
    public SyncException(String message) { super(message); }
    public SyncException(String message, Throwable cause) { super(message, cause); }
}
