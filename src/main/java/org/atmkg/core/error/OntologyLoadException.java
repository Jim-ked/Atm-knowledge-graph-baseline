package org.atmkg.core.error;

public final class OntologyLoadException extends RuntimeException {
    public OntologyLoadException(String message, Throwable cause) { super(message, cause); }
    public OntologyLoadException(String message) { super(message); }
}
