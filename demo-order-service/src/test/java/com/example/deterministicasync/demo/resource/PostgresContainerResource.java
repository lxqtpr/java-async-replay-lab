package com.example.deterministicasync.demo.resource;

import com.example.deterministicasync.extension.resource.ResourceScope;
import com.example.deterministicasync.extension.resource.TestResource;
import org.testcontainers.containers.PostgreSQLContainer;

import javax.sql.DataSource;

public final class PostgresContainerResource implements TestResource {

    private PostgreSQLContainer<?> container;
    private DataSource dataSource;
    private String backend;

    @Override
    public void start() {
        if (isReady()) {
            return;
        }

        if (DemoDataSourceFactory.dockerAvailable()) {
            container = new PostgreSQLContainer<>("postgres:16-alpine")
                    .withDatabaseName("orders")
                    .withUsername("test")
                    .withPassword("test");
            container.start();
            dataSource = DemoDataSourceFactory.postgresDataSource(container);
            backend = "testcontainers-postgres";
        } else {
            dataSource = DemoDataSourceFactory.h2DataSource("orders_extension");
            backend = "h2-fallback";
        }
    }

    @Override
    public void stop() {
        if (container != null) {
            container.stop();
            container = null;
        }
        dataSource = null;
        backend = null;
    }

    @Override
    public boolean isReady() {
        return dataSource != null && (container == null || container.isRunning());
    }

    @Override
    public String describe() {
        if (dataSource == null) {
            return "PostgresContainerResource{state=not-started}";
        }
        if (container != null && container.isRunning()) {
            return "PostgresContainerResource{backend=%s,jdbcUrl=%s}".formatted(backend, container.getJdbcUrl());
        }
        return "PostgresContainerResource{backend=%s}".formatted(backend);
    }

    @Override
    public ResourceScope scope() {
        return ResourceScope.PER_CLASS;
    }

    public DataSource dataSource() {
        if (dataSource == null) {
            throw new IllegalStateException("Datasource is not initialized");
        }
        return dataSource;
    }
}
