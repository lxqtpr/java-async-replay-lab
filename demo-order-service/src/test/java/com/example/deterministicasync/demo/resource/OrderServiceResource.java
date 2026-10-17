package com.example.deterministicasync.demo.resource;

import com.example.deterministicasync.demo.persistence.OrderRepository;
import com.example.deterministicasync.demo.service.OrderService;
import com.example.deterministicasync.extension.await.Awaiter;
import com.example.deterministicasync.extension.config.DeterministicAsyncSettings;
import com.example.deterministicasync.extension.resource.DependencyProvider;
import com.example.deterministicasync.extension.resource.ManagedExecutorResource;
import com.example.deterministicasync.extension.resource.ResourceScope;
import com.example.deterministicasync.extension.resource.TestResource;

import java.time.Duration;
import java.util.Optional;

public final class OrderServiceResource implements TestResource, DependencyProvider {
    private final PostgresContainerResource postgres;
    private final ManagedExecutorResource executorResource;

    private OrderRepository orderRepository;
    private OrderService orderService;

    public OrderServiceResource(PostgresContainerResource postgres, ManagedExecutorResource executorResource) {
        this.postgres = postgres;
        this.executorResource = executorResource;
    }

    @Override
    public void start() {
        this.orderRepository = new OrderRepository(postgres.dataSource());
        this.orderRepository.initSchema();
        this.orderService = new OrderService(
                orderRepository,
                executorResource.executor(),
                Duration.ofMillis(40),
                Duration.ofMillis(260)
        );
    }

    @Override
    public void stop() {
        if (orderService != null) {
            awaitIdle();
            orderService.stop();
        }
        orderService = null;
        orderRepository = null;
    }

    @Override
    public boolean isReady() {
        return orderService != null && orderRepository != null;
    }

    @Override
    public String describe() {
        return "OrderServiceResource{scope=PER_TEST}";
    }

    @Override
    public ResourceScope scope() {
        return ResourceScope.PER_TEST;
    }

    @Override
    public <T> Optional<T> resolveDependency(Class<T> type) {
        if (type.equals(OrderService.class) && orderService != null) {
            return Optional.of(type.cast(orderService));
        }
        if (type.equals(OrderRepository.class) && orderRepository != null) {
            return Optional.of(type.cast(orderRepository));
        }
        return Optional.empty();
    }

    private void awaitIdle() {
        new Awaiter(DeterministicAsyncSettings.defaults())
                .await()
                .atMost(DeterministicAsyncSettings.DEFAULT_RESOURCE_READY_TIMEOUT)
                .pollInterval(Duration.ofMillis(10))
                .until(() -> orderService.inFlightCount() == 0);
    }
}
