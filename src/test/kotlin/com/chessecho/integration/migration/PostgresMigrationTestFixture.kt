package com.chessecho.integration.migration

import org.flywaydb.core.Flyway
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.TestInstance
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.sql.Connection
import java.sql.DriverManager

@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
abstract class PostgresMigrationTestFixture {
    protected lateinit var connection: Connection

    @BeforeAll
    fun applyBaselineMigration() {
        flyway().clean()
        flyway().migrate()
        connection = DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password)
    }

    protected fun applyCurrentMigrations() {
        flyway().migrate()
    }

    protected fun query(sql: String): List<Map<String, Any?>> =
        connection.createStatement().use { statement ->
            statement.executeQuery(sql).use { result ->
                val metadata = result.metaData
                buildList {
                    while (result.next()) {
                        putRow@ run {
                            val row = linkedMapOf<String, Any?>()
                            for (column in 1..metadata.columnCount) {
                                row[metadata.getColumnLabel(column).lowercase()] = result.getObject(column)
                            }
                            add(row)
                        }
                    }
                }
            }
        }

    private fun flyway(): Flyway =
        Flyway.configure()
            .dataSource(postgres.jdbcUrl, postgres.username, postgres.password)
            .locations("classpath:db/migration")
            .cleanDisabled(false)
            .load()

    companion object {
        @Container
        @JvmField
        val postgres: PostgreSQLContainer<Nothing> = PostgreSQLContainer("postgres:16-alpine")
    }
}
