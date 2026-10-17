package dev.asyncreplay.junit5;

import dev.asyncreplay.ControlledRuntime;
import dev.asyncreplay.TraceFiles;
import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.ParameterContext;
import org.junit.jupiter.api.extension.ParameterResolver;
import java.nio.file.Path;

/** One runtime per test. Tear-down fails on pending tasks instead of silently draining them. */
public final class AsyncReplayExtension implements BeforeEachCallback, AfterEachCallback, ParameterResolver {
    private static final ExtensionContext.Namespace NS = ExtensionContext.Namespace.create(AsyncReplayExtension.class);
    private static final String KEY = "runtime";

    @Override public void beforeEach(ExtensionContext context) throws Exception {
        AsyncReplayTest options = context.getElement().map(e -> e.getAnnotation(AsyncReplayTest.class)).orElse(null);
        if (options == null) options = context.getRequiredTestClass().getAnnotation(AsyncReplayTest.class);
        if (options == null) throw new IllegalStateException("@AsyncReplayTest is required");
        ControlledRuntime runtime = options.replayFile().isBlank() ? new ControlledRuntime(options.seed())
                : ControlledRuntime.replay(TraceFiles.read(Path.of(options.replayFile())));
        context.getStore(NS).put(KEY, runtime);
    }

    @Override public void afterEach(ExtensionContext context) {
        ControlledRuntime runtime = context.getStore(NS).remove(KEY, ControlledRuntime.class);
        if (runtime != null) runtime.close();
    }

    @Override public boolean supportsParameter(ParameterContext parameter, ExtensionContext context) {
        return parameter.getParameter().getType() == ControlledRuntime.class;
    }

    @Override public Object resolveParameter(ParameterContext parameter, ExtensionContext context) {
        ControlledRuntime runtime = context.getStore(NS).get(KEY, ControlledRuntime.class);
        if (runtime == null) throw new IllegalStateException("runtime has not been initialized");
        return runtime;
    }
}
