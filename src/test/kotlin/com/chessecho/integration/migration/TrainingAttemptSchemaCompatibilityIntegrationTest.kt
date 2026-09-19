package com.chessecho.integration.migration

import org.junit.jupiter.api.Test
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter

class TrainingAttemptSchemaCompatibilityIntegrationTest : PostgresMigrationTestFixture() {
    @Test
    fun `hibernate validation accepts the flyway training attempt enum columns`() {
        val dataSource =
            DriverManagerDataSource(
                postgres.jdbcUrl,
                postgres.username,
                postgres.password,
            )
        val entityManagerFactory =
            LocalContainerEntityManagerFactoryBean().apply {
                setDataSource(dataSource)
                setPackagesToScan("com.chessecho.domain")
                jpaVendorAdapter = HibernateJpaVendorAdapter()
                setJpaPropertyMap(
                    mapOf(
                        "hibernate.hbm2ddl.auto" to "validate",
                        "hibernate.physical_naming_strategy" to
                            "org.hibernate.boot.model.naming.CamelCaseToUnderscoresNamingStrategy",
                    ),
                )
                afterPropertiesSet()
            }

        try {
            checkNotNull(entityManagerFactory.nativeEntityManagerFactory)
        } finally {
            entityManagerFactory.destroy()
        }
    }
}
