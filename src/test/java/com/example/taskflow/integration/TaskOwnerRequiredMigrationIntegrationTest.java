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
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@Testcontainers
class TaskOwnerRequiredMigrationIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>("postgres:16-alpine");

    private String schema;
    private long legacyTaskId;
    private long legacyUserId;
    private List<List<Object>> originalTasks;
    private List<List<Object>> originalUsers;

    @BeforeEach
    void createUnassignedLegacyData() throws SQLException {
        schema = "owner_required_" + UUID.randomUUID().toString().replace("-", "");
        flywayAt("4").migrate();

        try (Connection connection = connection(); var statement = connection.createStatement()) {
            statement.executeUpdate("""
                    INSERT INTO tasks (title, description, status, priority, owner_id)
                    VALUES ('Unassigned legacy task', 'Preserve this task', 'TODO', 2, NULL),
                           ('Other legacy task', NULL, 'COMPLETED', NULL, NULL)
                    """);
            statement.executeUpdate("""
                    INSERT INTO app_users (username, password, role)
                    VALUES ('legacy-owner', '{noop}legacy-test-password', 'USER')
                    """);
            originalTasks = rows(connection,
                    "SELECT id, title, description, status, priority, owner_id FROM tasks ORDER BY id");
            originalUsers = rows(connection, "SELECT id, username, password, role FROM app_users ORDER BY id");
            legacyTaskId = number(connection, "SELECT id FROM tasks WHERE title = 'Unassigned legacy task'");
            legacyUserId = number(connection, "SELECT id FROM app_users WHERE username = 'legacy-owner'");
        }
    }

    @Test
    void shouldPreserveLegacyRowsWithoutValidatingExistingNullOwners() throws SQLException {
        assertEquals(1, flywayAt("5").migrate().migrationsExecuted);
        assertEquals(0, flywayAt("5").migrate().migrationsExecuted);

        try (Connection connection = connection()) {
            assertEquals(originalTasks, rows(connection,
                    "SELECT id, title, description, status, priority, owner_id FROM tasks ORDER BY id"));
            assertEquals(originalUsers,
                    rows(connection, "SELECT id, username, password, role FROM app_users ORDER BY id"));
            assertEquals(2, number(connection, "SELECT COUNT(*) FROM tasks WHERE owner_id IS NULL"));
            try (var statement = connection.createStatement(); var result = statement.executeQuery("""
                    SELECT convalidated FROM pg_constraint
                    WHERE conname = 'chk_tasks_owner_required' AND conrelid = 'tasks'::regclass
                    """)) {
                assertTrue(result.next());
                assertFalse(result.getBoolean("convalidated"));
                assertFalse(result.next());
            }
        }
    }

    @Test
    void shouldRejectNewUnassignedTasksAndUpdatesLeavingLegacyTasksUnassigned() throws SQLException {
        flywayAt("5").migrate();

        try (Connection connection = connection(); var statement = connection.createStatement()) {
            assertCheckViolation(statement, """
                    INSERT INTO tasks (title, status, owner_id) VALUES ('Rejected task', 'TODO', NULL)
                    """);
            assertCheckViolation(statement, """
                    INSERT INTO tasks (title, status) VALUES ('Owner omitted', 'TODO')
                    """);
            assertCheckViolation(statement,
                    "UPDATE tasks SET title = 'Rejected edit' WHERE id = " + legacyTaskId);

            assertEquals(originalTasks, rows(connection,
                    "SELECT id, title, description, status, priority, owner_id FROM tasks ORDER BY id"));
        }
    }

    @Test
    void shouldAllowOwnershipAssignmentThenNormalUpdatesAndNewOwnedTasks() throws SQLException {
        flywayAt("5").migrate();

        try (Connection connection = connection(); var statement = connection.createStatement()) {
            assertEquals(1, statement.executeUpdate(
                    "UPDATE tasks SET owner_id = " + legacyUserId + " WHERE id = " + legacyTaskId));
            assertEquals(1, statement.executeUpdate(
                    "UPDATE tasks SET title = 'Assigned legacy task' WHERE id = " + legacyTaskId));
            assertEquals(1, statement.executeUpdate(
                    "INSERT INTO tasks (title, status, owner_id) VALUES ('New owned task', 'TODO', "
                            + legacyUserId + ")"));
            assertCheckViolation(statement,
                    "UPDATE tasks SET owner_id = NULL WHERE id = " + legacyTaskId);

            assertEquals(legacyUserId, number(connection,
                    "SELECT owner_id FROM tasks WHERE id = " + legacyTaskId));
            assertEquals(1, number(connection, """
                    SELECT COUNT(*) FROM tasks WHERE title = 'Assigned legacy task'
                    """));
            assertEquals(1, number(connection,
                    "SELECT COUNT(*) FROM tasks WHERE title = 'New owned task' AND owner_id = " + legacyUserId));
            assertEquals(3, number(connection, "SELECT COUNT(*) FROM tasks"));
            assertEquals(1, number(connection, "SELECT COUNT(*) FROM tasks WHERE owner_id IS NULL"));
            assertEquals(originalUsers,
                    rows(connection, "SELECT id, username, password, role FROM app_users ORDER BY id"));
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
        // Expected check failures must not abort subsequent assertions or writes.
        connection.setAutoCommit(true);
        return connection;
    }

    private static void assertCheckViolation(Statement statement, String sql) {
        SQLException failure = assertThrows(SQLException.class, () -> statement.executeUpdate(sql));
        assertEquals("23514", failure.getSQLState());
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
