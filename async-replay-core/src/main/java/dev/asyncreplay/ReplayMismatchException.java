package dev.asyncreplay;

/** The replay scenario no longer offers the same eligible tasks or result. */
public final class ReplayMismatchException extends IllegalStateException {
    private static final long serialVersionUID = 1L;
    public ReplayMismatchException(String message) { super(message); }
}
