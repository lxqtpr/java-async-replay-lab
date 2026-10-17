package com.example.deterministicasync.extension.annotation;

import com.example.deterministicasync.extension.cleanup.CleanupActionFactory;
import com.example.deterministicasync.extension.lifecycle.DeterministicAsyncExtension;
import com.example.deterministicasync.extension.resource.TestResourceFactory;
import org.junit.jupiter.api.extension.ExtendWith;

import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@Inherited
@ExtendWith(DeterministicAsyncExtension.class)
public @interface DeterministicAsyncTest {
    long timeoutMillis() default 3_000;

    long pollIntervalMillis() default 50;

    long resourceReadyTimeoutMillis() default 5_000;

    boolean ignoreConditionExceptions() default true;

    Class<? extends TestResourceFactory>[] resourceFactories() default {};

    Class<? extends CleanupActionFactory>[] cleanupFactories() default {};
}
