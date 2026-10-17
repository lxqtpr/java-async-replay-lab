package com.example.deterministicasync.demo;

import com.example.deterministicasync.demo.persistence.OrderRepository;
import com.example.deterministicasync.demo.resource.DemoDataSourceFactory;
import com.example.deterministicasync.demo.service.OrderService;
import com.example.deterministicasync.extension.await.Awaiter;
import com.example.deterministicasync.extension.config.DeterministicAsyncSettings;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.testcontainers.containers.PostgreSQLContainer;

import javax.sql.DataSource;
import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public abstract class AbstractManualOrderServiceTest {
    private static PostgreSQLContainer<?> postgres;
    private static DataSource sharedDataSource;

    protected OrderRepository orderRepository;
    protected OrderService orderService;
    protected ExecutorService executorService;

    @BeforeAll
    static void startDatabaseOnce() {
        if (sharedDataSource != null) {
            return;
        }

        if (DemoDataSourceFactory.dockerAvailable()) {
            postgres = new PostgreSQLContainer<>("postgres:16-alpine")
                    .withDatabaseName("orders")
                    .withUsername("test")
                    .withPassword("test");
            postgres.start();
            sharedDataSource = DemoDataSourceFactory.postgresDataSource(postgres);
        } else {
            sharedDataSource = DemoDataSourceFactory.h2DataSource("orders_manual_baseline");
        }
    }

    @AfterAll
    static void stopDatabaseOnce() {
        if (postgres != null) {
            postgres.stop();
            postgres = null;
        }
        sharedDataSource = null;
    }

    @BeforeEach
    void setUpService() {
        executorService = Executors.newFixedThreadPool(4);
        orderRepository = new OrderRepository(dataSource());
        orderRepository.initSchema();
        orderRepository.deleteAll();
        orderService = new OrderService(
                orderRepository,
                executorService,
                Duration.ofMillis(40),
                Duration.ofMillis(260)
        );
    }

    @AfterEach
    void tearDownService() throws InterruptedException {
        if (orderService != null) {
            awaitIdle();
            orderService.stop();
        }

        if (executorService != null) {
            executorService.shutdownNow();
            executorService.awaitTermination(1, TimeUnit.SECONDS);
        }

        if (orderRepository != null) {
            orderRepository.deleteAll();
        }
    }

    private DataSource dataSource() {
        if (sharedDataSource == null) {
            throw new IllegalStateException("Shared test datasource is not initialized");
        }
        return sharedDataSource;
    }

    private void awaitIdle() {
        new Awaiter(DeterministicAsyncSettings.defaults())
                .await()
                .atMost(DeterministicAsyncSettings.DEFAULT_RESOURCE_READY_TIMEOUT)
                .pollInterval(Duration.ofMillis(10))
                .until(() -> orderService.inFlightCount() == 0);
    }
}
