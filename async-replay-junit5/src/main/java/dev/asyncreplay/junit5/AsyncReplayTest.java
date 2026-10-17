package dev.asyncreplay.junit5;

import org.junit.jupiter.api.extension.ExtendWith;
import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Composed extension annotation, used alongside JUnit's @Test. */
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@Inherited
@ExtendWith(AsyncReplayExtension.class)
public @interface AsyncReplayTest {
    long seed() default 0L;
    /** Optional local trace path. If set, the recorded trace seed/origin replace seed(). */
    String replayFile() default "";
}
