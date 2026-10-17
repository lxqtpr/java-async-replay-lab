package com.example.deterministicasync.extension.lifecycle;

import com.example.deterministicasync.extension.annotation.DeterministicAsyncTest;
import com.example.deterministicasync.extension.cleanup.CleanupAction;
import com.example.deterministicasync.extension.cleanup.CleanupActionFactory;
import com.example.deterministicasync.extension.config.DeterministicAsyncConfigResolver;
import com.example.deterministicasync.extension.config.DeterministicAsyncSettings;
import com.example.deterministicasync.extension.context.DefaultTestContext;
import com.example.deterministicasync.extension.context.ResourceRegistry;
import com.example.deterministicasync.extension.context.TestContext;
import com.example.deterministicasync.extension.resource.ResourceScope;
import com.example.deterministicasync.extension.resource.TestResource;
import com.example.deterministicasync.extension.resource.TestResourceFactory;
import org.junit.jupiter.api.extension.AfterAllCallback;
import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionConfigurationException;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.ParameterContext;
import org.junit.jupiter.api.extension.ParameterResolutionException;
import org.junit.jupiter.api.extension.ParameterResolver;
import org.opentest4j.TestAbortedException;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public final class DeterministicAsyncExtension implements
        BeforeAllCallback,
        BeforeEachCallback,
        AfterEachCallback,
        AfterAllCallback,
        ParameterResolver {

    private static final String KEY_CLASS_STATE = "class-state";
    private static final String KEY_METHOD_CONTEXT = "method-context";
    private static final String KEY_METHOD_RESOURCES = "method-resources";
    private static final String KEY_METHOD_CLEANUP_ACTIONS = "method-cleanup-actions";

    @Override
    public void beforeAll(ExtensionContext context) {
        ensureClassState(context);
    }

    @Override
    public void beforeEach(ExtensionContext context) {
        ClassState classState = ensureClassState(context);
        ResolvedDescriptor descriptor = resolveDescriptor(context);

        DefaultTestContext baseContext = new DefaultTestContext(descriptor.settings, classState.classRegistry);
        List<TestResource> allResources = createResources(descriptor.resourceFactories, baseContext);
        List<TestResource> perTestResources = filterByScope(allResources, ResourceScope.PER_TEST);

        ExtensionContext.Store methodStore = methodStore(context);
        methodStore.put(KEY_METHOD_RESOURCES, perTestResources);

        startResources(perTestResources, baseContext);

        ResourceRegistry mergedRegistry = classState.classRegistry.merge(perTestResources);
        DefaultTestContext testContext = baseContext.withResources(mergedRegistry);
        methodStore.put(KEY_METHOD_CONTEXT, testContext);

        List<CleanupAction> cleanupActions = createCleanupActions(descriptor.cleanupFactories, testContext);
        methodStore.put(KEY_METHOD_CLEANUP_ACTIONS, cleanupActions);
    }

    @Override
    public void afterEach(ExtensionContext context) {
        ExtensionContext.Store methodStore = methodStore(context);
        TestContext testContext = methodStore.get(KEY_METHOD_CONTEXT, TestContext.class);
        List<CleanupAction> cleanupActions = getList(methodStore, KEY_METHOD_CLEANUP_ACTIONS, CleanupAction.class);
        List<TestResource> perTestResources = getList(methodStore, KEY_METHOD_RESOURCES, TestResource.class);

        Throwable failure = null;

        if (testContext != null) {
            for (CleanupAction cleanupAction : cleanupActions) {
                try {
                    cleanupAction.cleanup(testContext);
                } catch (Throwable throwable) {
                    failure = appendFailure(failure, throwable);
                }
            }
        }

        Throwable stopFailure = stopResources(perTestResources);
        failure = appendFailure(failure, stopFailure);

        if (failure != null) {
            throw new ExtensionConfigurationException("afterEach lifecycle failed", failure);
        }
    }

    @Override
    public void afterAll(ExtensionContext context) {
        ExtensionContext.Store classStore = classStore(context);
        ClassState classState = classStore.remove(KEY_CLASS_STATE, ClassState.class);
        if (classState == null) {
            return;
        }

        Throwable stopFailure = stopResources(classState.classResources);
        if (stopFailure != null) {
            throw new ExtensionConfigurationException("afterAll lifecycle failed", stopFailure);
        }
    }

    @Override
    public boolean supportsParameter(ParameterContext parameterContext, ExtensionContext extensionContext)
            throws ParameterResolutionException {
        Class<?> parameterType = parameterContext.getParameter().getType();
        if (parameterType.equals(TestContext.class)) {
            return true;
        }

        return currentContext(extensionContext).resources().findDependency(parameterType).isPresent();
    }

    @Override
    public Object resolveParameter(ParameterContext parameterContext, ExtensionContext extensionContext)
            throws ParameterResolutionException {
        Class<?> parameterType = parameterContext.getParameter().getType();
        TestContext context = currentContext(extensionContext);
        if (parameterType.equals(TestContext.class)) {
            return context;
        }

        return context.resources()
                .findDependency(parameterType)
                .orElseThrow(() -> new ParameterResolutionException(
                        "Dependency not found for parameter type: " + parameterType.getName()
                ));
    }

    private TestContext currentContext(ExtensionContext extensionContext) {
        ExtensionContext.Store methodStore = methodStore(extensionContext);
        TestContext methodContext = methodStore.get(KEY_METHOD_CONTEXT, TestContext.class);
        if (methodContext != null) {
            return methodContext;
        }
        ClassState classState = ensureClassState(extensionContext);
        return new DefaultTestContext(classState.settings, classState.classRegistry);
    }

    private ClassState ensureClassState(ExtensionContext context) {
        ExtensionContext.Store classStore = classStore(context);
        ClassState existing = classStore.get(KEY_CLASS_STATE, ClassState.class);
        if (existing != null) {
            return existing;
        }

        ResolvedDescriptor descriptor = resolveDescriptor(context);
        DefaultTestContext bootstrapContext = new DefaultTestContext(descriptor.settings, ResourceRegistry.empty());

        List<TestResource> allResources = createResources(descriptor.resourceFactories, bootstrapContext);
        List<TestResource> classResources = filterByScope(allResources, ResourceScope.PER_CLASS);

        startResources(classResources, bootstrapContext);

        ResourceRegistry classRegistry = ResourceRegistry.of(classResources);
        ClassState classState = new ClassState(descriptor.settings, classResources, classRegistry);
        classStore.put(KEY_CLASS_STATE, classState);
        return classState;
    }

    private ResolvedDescriptor resolveDescriptor(ExtensionContext context) {
        DeterministicAsyncTest classAnnotation = context.getRequiredTestClass().getAnnotation(DeterministicAsyncTest.class);
        DeterministicAsyncTest methodAnnotation = context.getElement()
                .map(el -> el.getAnnotation(DeterministicAsyncTest.class))
                .orElse(null);

        DeterministicAsyncTest annotationForSettings = methodAnnotation != null ? methodAnnotation : classAnnotation;
        DeterministicAsyncSettings settings = DeterministicAsyncConfigResolver.resolve(annotationForSettings);

        List<Class<? extends TestResourceFactory>> resourceFactories = mergeFactoryClasses(
                classAnnotation != null ? classAnnotation.resourceFactories() : null,
                methodAnnotation != null ? methodAnnotation.resourceFactories() : null
        );

        List<Class<? extends CleanupActionFactory>> cleanupFactories = mergeFactoryClasses(
                classAnnotation != null ? classAnnotation.cleanupFactories() : null,
                methodAnnotation != null ? methodAnnotation.cleanupFactories() : null
        );

        return new ResolvedDescriptor(settings, resourceFactories, cleanupFactories);
    }

    private void startResources(List<TestResource> resources, DefaultTestContext testContext) {
        List<TestResource> started = new ArrayList<>();

        for (TestResource resource : resources) {
            try {
                resource.start();
                testContext.awaiter().await()
                        .atMost(testContext.config().resourceReadyTimeout())
                        .pollInterval(testContext.config().defaultPollInterval())
                        .ignoreConditionExceptions(true)
                        .until(resource::isReady);
                started.add(resource);
            } catch (TestAbortedException abortedException) {
                Throwable stopFailure = stopResources(started);
                if (stopFailure != null) {
                    abortedException.addSuppressed(stopFailure);
                }
                throw abortedException;
            } catch (Throwable throwable) {
                Throwable stopFailure = stopResources(started);
                if (stopFailure != null) {
                    throwable.addSuppressed(stopFailure);
                }
                throw new ExtensionConfigurationException("Failed to start resource: " + resource.describe(), throwable);
            }
        }
    }

    private List<TestResource> createResources(
            List<Class<? extends TestResourceFactory>> factoryClasses,
            TestContext context
    ) {
        List<TestResource> resources = new ArrayList<>();
        for (Class<? extends TestResourceFactory> factoryClass : factoryClasses) {
            TestResourceFactory factory = newInstance(factoryClass);
            try {
                List<TestResource> produced = factory.createResources(context);
                if (produced != null) {
                    resources.addAll(produced);
                }
            } catch (Exception ex) {
                throw new ExtensionConfigurationException(
                        "Failed to create resources using factory: " + factoryClass.getName(),
                        ex
                );
            }
        }
        return resources;
    }

    private List<CleanupAction> createCleanupActions(
            List<Class<? extends CleanupActionFactory>> factoryClasses,
            TestContext context
    ) {
        List<CleanupAction> cleanupActions = new ArrayList<>();
        for (Class<? extends CleanupActionFactory> factoryClass : factoryClasses) {
            CleanupActionFactory factory = newInstance(factoryClass);
            try {
                List<CleanupAction> produced = factory.createActions(context);
                if (produced != null) {
                    cleanupActions.addAll(produced);
                }
            } catch (Exception ex) {
                throw new ExtensionConfigurationException(
                        "Failed to create cleanup actions using factory: " + factoryClass.getName(),
                        ex
                );
            }
        }
        return cleanupActions;
    }

    private List<TestResource> filterByScope(List<TestResource> resources, ResourceScope scope) {
        List<TestResource> filtered = new ArrayList<>();
        for (TestResource resource : resources) {
            if (resource.scope() == scope) {
                filtered.add(resource);
            }
        }
        return filtered;
    }

    private Throwable stopResources(List<TestResource> resources) {
        Throwable failure = null;
        for (int i = resources.size() - 1; i >= 0; i--) {
            TestResource resource = resources.get(i);
            try {
                resource.stop();
            } catch (Throwable throwable) {
                failure = appendFailure(failure, throwable);
            }
        }
        return failure;
    }

    @SuppressWarnings("unchecked")
    private <T> List<T> getList(ExtensionContext.Store store, String key, Class<T> type) {
        List<?> raw = store.get(key, List.class);
        if (raw == null) {
            return List.of();
        }

        List<T> result = new ArrayList<>(raw.size());
        for (Object item : raw) {
            if (type.isInstance(item)) {
                result.add((T) item);
            }
        }
        return result;
    }

    private Throwable appendFailure(Throwable current, Throwable next) {
        if (next == null) {
            return current;
        }

        if (current == null) {
            return next;
        }

        current.addSuppressed(next);
        return current;
    }

    private ExtensionContext.Store classStore(ExtensionContext context) {
        return context.getRoot().getStore(ExtensionContext.Namespace.create(
                DeterministicAsyncExtension.class,
                context.getRequiredTestClass().getName()
        ));
    }

    private ExtensionContext.Store methodStore(ExtensionContext context) {
        return context.getStore(ExtensionContext.Namespace.create(
                DeterministicAsyncExtension.class,
                context.getUniqueId()
        ));
    }

    private <T> T newInstance(Class<T> type) {
        try {
            Constructor<T> ctor = type.getDeclaredConstructor();
            ctor.setAccessible(true);
            return ctor.newInstance();
        } catch (NoSuchMethodException
                 | InstantiationException
                 | IllegalAccessException
                 | InvocationTargetException ex) {
            throw new ExtensionConfigurationException("Cannot instantiate " + type.getName() + ". Use public no-arg constructor.", ex);
        }
    }

    private <T> List<Class<? extends T>> mergeFactoryClasses(
            Class<? extends T>[] classFactories,
            Class<? extends T>[] methodFactories
    ) {
        Set<Class<? extends T>> merged = new LinkedHashSet<>();

        if (classFactories != null) {
            merged.addAll(Arrays.asList(classFactories));
        }
        if (methodFactories != null) {
            merged.addAll(Arrays.asList(methodFactories));
        }

        return List.copyOf(merged);
    }

    private record ResolvedDescriptor(
            DeterministicAsyncSettings settings,
            List<Class<? extends TestResourceFactory>> resourceFactories,
            List<Class<? extends CleanupActionFactory>> cleanupFactories
    ) {
    }

    private record ClassState(
            DeterministicAsyncSettings settings,
            List<TestResource> classResources,
            ResourceRegistry classRegistry
    ) {
    }
}
