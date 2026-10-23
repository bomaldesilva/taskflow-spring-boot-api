package com.example.taskflow.integration;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@Testcontainers
class TaskOwnerMigrationIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>("postgres:16-alpine");

    private String schema;
    private long legacyTaskId;
    private long legacyUserId;
    private List<List<Object>> originalTasks;
    private List<List<Object>> originalUsers;

    @BeforeEach
    void createLegacyDatabase() throws SQLException {
        schema = "owner_upgrade_" + UUID.randomUUID().toString().replace("-", "");
        flywayAt("3").migrate();

        try (Connection connection = connection(); var statement = connection.createStatement()) {
            statement.executeUpdate("""
                    INSERT INTO tasks (title, description, status, priority)
                    VALUES ('Legacy TODO task', 'Keep this description', 'TODO', 3),
                           ('Legacy completed task', NULL, 'COMPLETED', NULL)
                    """);
            statement.executeUpdate("""
                    INSERT INTO app_users (username, password, role)
                    VALUES ('legacy-owner', '{noop}legacy-test-password', 'USER')
                    """);

            originalTasks = rows(connection, "SELECT id, title, description, status, priority FROM tasks ORDER BY id");
            originalUsers = rows(connection, "SELECT id, username, password, role FROM app_users ORDER BY id");
            legacyTaskId = number(connection, "SELECT id FROM tasks WHERE title = 'Legacy TODO task'");
            legacyUserId = number(connection, "SELECT id FROM app_users WHERE username = 'legacy-owner'");
        }
    }

    @Test
    void shouldUpgradeLegacyRowsWithoutAssigningOwnersOrChangingUsers() throws SQLException {
        assertEquals(1, flywayAt("4").migrate().migrationsExecuted);
        assertEquals(0, flywayAt("4").migrate().migrationsExecuted);

        try (Connection connection = connection()) {
            assertEquals(originalTasks,
                    rows(connection, "SELECT id, title, description, status, priority FROM tasks ORDER BY id"));
            assertEquals(originalUsers,
                    rows(connection, "SELECT id, username, password, role FROM app_users ORDER BY id"));
            assertEquals(2, number(connection, "SELECT COUNT(*) FROM tasks WHERE owner_id IS NULL"));
            assertEquals(1, number(connection, """
                    SELECT COUNT(*) FROM flyway_schema_history WHERE version = '4' AND success
                    """));
            assertEquals(1, number(connection, """
                    SELECT COUNT(*) FROM pg_indexes
                    WHERE schemaname = current_schema() AND tablename = 'tasks'
                      AND indexname = 'idx_tasks_owner_id'
                    """));
        }
    }

    @Test
    void shouldAllowLinkingLegacyTaskToExistingUser() throws SQLException {
        flywayAt("4").migrate();

        try (Connection connection = connection(); var statement = connection.createStatement()) {
            assertEquals(1, statement.executeUpdate(
                    "UPDATE tasks SET owner_id = " + legacyUserId + " WHERE id = " + legacyTaskId));
            assertEquals(legacyUserId, number(connection,
                    "SELECT owner_id FROM tasks WHERE id = " + legacyTaskId));
            assertEquals(1, number(connection, "SELECT COUNT(*) FROM tasks WHERE owner_id IS NULL"));
            assertEquals(originalUsers,
                    rows(connection, "SELECT id, username, password, role FROM app_users ORDER BY id"));
        }
    }

    @Test
    void shouldRejectUnknownOwnersAndPreventDeletingReferencedUser() throws SQLException {
        flywayAt("4").migrate();

        try (Connection connection = connection(); var statement = connection.createStatement()) {
            SQLException unknownOwner = assertThrows(SQLException.class, () -> statement.executeUpdate(
                    "UPDATE tasks SET owner_id = -1 WHERE id = " + legacyTaskId));
            assertEquals("23503", unknownOwner.getSQLState());
            assertEquals(2, number(connection, "SELECT COUNT(*) FROM tasks WHERE owner_id IS NULL"));

            statement.executeUpdate(
                    "UPDATE tasks SET owner_id = " + legacyUserId + " WHERE id = " + legacyTaskId);
            SQLException referencedUser = assertThrows(SQLException.class, () -> statement.executeUpdate(
                    "DELETE FROM app_users WHERE id = " + legacyUserId));
            assertEquals("23503", referencedUser.getSQLState());
            assertEquals(legacyUserId, number(connection,
                    "SELECT owner_id FROM tasks WHERE id = " + legacyTaskId));
            assertEquals(originalUsers,
                    rows(connection, "SELECT id, username, password, role FROM app_users ORDER BY id"));
            assertEquals(2, number(connection, "SELECT COUNT(*) FROM tasks"));
        }
    }

    private Flyway flywayAt(String version) {
        return Flyway.configure()
                .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .locations("classpath:db/migration")
                .schemas(schema)
                .defaultSchema(schema)
                .target(version)
                .load();
    }

    private Connection connection() throws SQLException {
        Connection connection = DriverManager.getConnection(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        connection.setSchema(schema);
        // Each expected constraint violation rolls back only its own statement.
        connection.setAutoCommit(true);
        return connection;
    }

    private static long number(Connection connection, String sql) throws SQLException {
        try (var statement = connection.createStatement(); var result = statement.executeQuery(sql)) {
            assertTrue(result.next());
            return result.getLong(1);
        }
    }

    private static List<List<Object>> rows(Connection connection, String sql) throws SQLException {
        try (var statement = connection.createStatement(); var result = statement.executeQuery(sql)) {
            List<List<Object>> rows = new ArrayList<>();
            while (result.next()) {
                List<Object> row = new ArrayList<>();
                for (int column = 1; column <= result.getMetaData().getColumnCount(); column++) {
                    row.add(result.getObject(column));
                }
                rows.add(row);
            }
            return rows;
        }
    }
}
