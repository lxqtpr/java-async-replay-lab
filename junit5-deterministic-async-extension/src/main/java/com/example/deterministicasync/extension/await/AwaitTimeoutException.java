package com.example.deterministicasync.extension.await;

public final class AwaitTimeoutException extends AssertionError {
    public AwaitTimeoutException(String message) {
        super(message);
    }
}
