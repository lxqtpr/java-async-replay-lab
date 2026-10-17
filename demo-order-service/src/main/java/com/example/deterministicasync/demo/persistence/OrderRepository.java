package com.example.deterministicasync.demo.persistence;

import com.example.deterministicasync.demo.model.OrderStatus;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public final class OrderRepository {
    private final DataSource dataSource;

    public OrderRepository(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    public void initSchema() {
        String ddl = """
                CREATE TABLE IF NOT EXISTS orders (
                    id UUID PRIMARY KEY,
                    customer_id TEXT NOT NULL,
                    amount NUMERIC(12,2) NOT NULL,
                    status TEXT NOT NULL,
                    created_at TIMESTAMP NOT NULL,
                    processed_at TIMESTAMP NULL
                )
                """;

        withConnection(connection -> {
            try (Statement st = connection.createStatement()) {
                st.execute(ddl);
            }
        });
    }

    public UUID createOrder(String customerId, BigDecimal amount) {
        UUID id = UUID.randomUUID();
        String sql = "INSERT INTO orders(id, customer_id, amount, status, created_at, processed_at) VALUES (?, ?, ?, ?, ?, ?)";

        withConnection(connection -> {
            try (PreparedStatement ps = connection.prepareStatement(sql)) {
                ps.setObject(1, id);
                ps.setString(2, customerId);
                ps.setBigDecimal(3, amount);
                ps.setString(4, OrderStatus.QUEUED.name());
                ps.setTimestamp(5, Timestamp.from(Instant.now()));
                ps.setTimestamp(6, null);
                ps.executeUpdate();
            }
        });

        return id;
    }

    public void updateStatus(UUID id, OrderStatus status) {
        String sql = "UPDATE orders SET status = ?, processed_at = ? WHERE id = ?";
        Timestamp processedAt = status == OrderStatus.COMPLETED || status == OrderStatus.FAILED
                ? Timestamp.from(Instant.now())
                : null;

        withConnection(connection -> {
            try (PreparedStatement ps = connection.prepareStatement(sql)) {
                ps.setString(1, status.name());
                ps.setTimestamp(2, processedAt);
                ps.setObject(3, id);
                ps.executeUpdate();
            }
        });
    }

    public Optional<OrderStatus> findStatus(UUID id) {
        String sql = "SELECT status FROM orders WHERE id = ?";

        return withConnection(connection -> {
            try (PreparedStatement ps = connection.prepareStatement(sql)) {
                ps.setObject(1, id);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        return Optional.empty();
                    }
                    return Optional.of(OrderStatus.valueOf(rs.getString("status")));
                }
            }
        });
    }

    public long countOrders() {
        String sql = "SELECT COUNT(*) FROM orders";

        return withConnection(connection -> {
            try (Statement st = connection.createStatement();
                 ResultSet rs = st.executeQuery(sql)) {
                rs.next();
                return rs.getLong(1);
            }
        });
    }

    public void deleteAll() {
        withConnection(connection -> {
            try (Statement st = connection.createStatement()) {
                st.executeUpdate("TRUNCATE TABLE orders");
            }
        });
    }

    private void withConnection(SqlConsumer<Connection> callback) {
        try (Connection connection = dataSource.getConnection()) {
            callback.accept(connection);
        } catch (SQLException ex) {
            throw new IllegalStateException("Database operation failed", ex);
        }
    }

    private <T> T withConnection(SqlFunction<Connection, T> callback) {
        try (Connection connection = dataSource.getConnection()) {
            return callback.apply(connection);
        } catch (SQLException ex) {
            throw new IllegalStateException("Database operation failed", ex);
        }
    }

    @FunctionalInterface
    private interface SqlConsumer<T> {
        void accept(T value) throws SQLException;
    }

    @FunctionalInterface
    private interface SqlFunction<T, R> {
        R apply(T value) throws SQLException;
    }
}
