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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Testcontainers
class UserTokenStateMigrationIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>("postgres:16-alpine");

    private String schema;
    private long legacyUserId;
    private List<List<Object>> originalUsers;
    private List<List<Object>> originalTasks;

    @BeforeEach
    void createPreTokenStateDatabase() throws SQLException {
        schema = "token_state_upgrade_" + UUID.randomUUID().toString().replace("-", "");
        flywayAt("4").migrate();

        try (Connection connection = connection(); var statement = connection.createStatement()) {
            statement.executeUpdate("""
                    INSERT INTO app_users (username, password, role)
                    VALUES ('legacy-user', '{noop}legacy-user-test-password', 'USER'),
                           ('legacy-admin', '{noop}legacy-admin-test-password', 'ADMIN')
                    """);
            legacyUserId = number(connection,
                    "SELECT id FROM app_users WHERE username = 'legacy-user'");
            statement.executeUpdate("""
                    INSERT INTO tasks (title, description, status, priority, owner_id)
                    VALUES ('Assigned task', 'Keep assigned task', 'TODO', 4,
                            (SELECT id FROM app_users WHERE username = 'legacy-user')),
                           ('Unassigned legacy task', NULL, 'COMPLETED', NULL, NULL)
                    """);
            originalUsers = rows(connection,
                    "SELECT id, username, password, role FROM app_users ORDER BY id");
            originalTasks = rows(connection,
                    "SELECT id, title, description, status, priority, owner_id FROM tasks ORDER BY id");
        }

        flywayAt("5").migrate();
    }

    @Test
    void shouldUpgradeExistingUsersWithoutChangingCredentialsRolesOrTaskOwnership() throws SQLException {
        assertEquals(1, flywayAt("6").migrate().migrationsExecuted);
        assertEquals(0, flywayAt("6").migrate().migrationsExecuted);

        try (Connection connection = connection()) {
            assertEquals(originalUsers,
                    rows(connection, "SELECT id, username, password, role FROM app_users ORDER BY id"));
            assertEquals(originalTasks,
                    rows(connection, "SELECT id, title, description, status, priority, owner_id FROM tasks ORDER BY id"));
            assertEquals(2, number(connection,
                    "SELECT COUNT(*) FROM app_users WHERE enabled = TRUE AND token_version = 0 AND row_version = 0"));
            assertEquals(1, number(connection, "SELECT COUNT(*) FROM tasks WHERE owner_id IS NULL"));
            assertEquals(1, number(connection, """
                    SELECT COUNT(*) FROM flyway_schema_history WHERE version = '6' AND success
                    """));
        }
    }

    @Test
    void shouldDefaultNewUsersToEnabledWithVersionZeroAndRejectNullTokenState() throws SQLException {
        flywayAt("6").migrate();

        try (Connection connection = connection(); var statement = connection.createStatement()) {
            statement.executeUpdate("""
                    INSERT INTO app_users (username, password, role)
                    VALUES ('new-user', '{noop}new-user-test-password', 'USER')
                    """);

            assertEquals(1, number(connection, """
                    SELECT COUNT(*) FROM app_users
                    WHERE username = 'new-user' AND enabled = TRUE AND token_version = 0 AND row_version = 0
                    """));

            SQLException nullEnabled = assertThrows(SQLException.class, () -> statement.executeUpdate(
                    "UPDATE app_users SET enabled = NULL WHERE id = " + legacyUserId));
            assertEquals("23502", nullEnabled.getSQLState());

            SQLException nullVersion = assertThrows(SQLException.class, () -> statement.executeUpdate(
                    "UPDATE app_users SET token_version = NULL WHERE id = " + legacyUserId));
            assertEquals("23502", nullVersion.getSQLState());

            SQLException nullRowVersion = assertThrows(SQLException.class, () -> statement.executeUpdate(
                    "UPDATE app_users SET row_version = NULL WHERE id = " + legacyUserId));
            assertEquals("23502", nullRowVersion.getSQLState());

            assertEquals(1, number(connection,
                    "SELECT COUNT(*) FROM app_users WHERE id = " + legacyUserId
                            + " AND enabled = TRUE AND token_version = 0 AND row_version = 0"));
        }
    }

    @Test
    void shouldAllowDisablingUsersAndAdvancingTokenVersion() throws SQLException {
        flywayAt("6").migrate();

        try (Connection connection = connection(); var statement = connection.createStatement()) {
            assertEquals(1, statement.executeUpdate(
                    "UPDATE app_users SET enabled = FALSE, token_version = 1 WHERE id = " + legacyUserId));
            try (var result = statement.executeQuery(
                    "SELECT enabled, token_version FROM app_users WHERE id = " + legacyUserId)) {
                assertTrue(result.next());
                assertFalse(result.getBoolean("enabled"));
                assertEquals(1, result.getLong("token_version"));
            }

            SQLException negativeVersion = assertThrows(SQLException.class, () -> statement.executeUpdate(
                    "UPDATE app_users SET token_version = -1 WHERE id = " + legacyUserId));
            assertEquals("23514", negativeVersion.getSQLState());
            assertEquals(1, number(connection, "SELECT token_version FROM app_users WHERE id = " + legacyUserId));
            assertEquals(originalTasks,
                    rows(connection, "SELECT id, title, description, status, priority, owner_id FROM tasks ORDER BY id"));
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
        // Each expected violation rolls back only its own statement.
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
