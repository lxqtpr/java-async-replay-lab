package com.example.deterministicasync.demo.resource;

import org.h2.jdbcx.JdbcDataSource;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;

import javax.sql.DataSource;

public final class DemoDataSourceFactory {
    private DemoDataSourceFactory() {
    }

    public static boolean dockerAvailable() {
        try {
            return DockerClientFactory.instance().isDockerAvailable();
        } catch (Throwable ignored) {
            return false;
        }
    }

    public static DataSource postgresDataSource(PostgreSQLContainer<?> container) {
        PGSimpleDataSource dataSource = new PGSimpleDataSource();
        dataSource.setURL(container.getJdbcUrl());
        dataSource.setUser(container.getUsername());
        dataSource.setPassword(container.getPassword());
        return dataSource;
    }

    public static DataSource h2DataSource(String databaseName) {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:" + databaseName + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE");
        dataSource.setUser("sa");
        dataSource.setPassword("sa");
        return dataSource;
    }
}
