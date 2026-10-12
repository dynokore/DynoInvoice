package com.dynokore.platform.serviceruntime.datasource

import com.zaxxer.hikari.HikariDataSource
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.springframework.boot.test.context.assertj.AssertableApplicationContext
import java.nio.file.Files
import java.nio.file.Path
import javax.sql.DataSource

/**
 * INV-T-3913 (STK-29, ruling INV-Q-3909): `service-runtime.datasource.credential` on Spring Boot's application pool,
 * composed as TPL-18. The pool is lazy, so no database is needed: no connection is opened at start.
 */
class DatabaseCredentialsWiringTest {
    private fun service(mountRoot: Path) = tpl18Service().withPropertyValues(
        "gst.bindings.secret-store.mount-root=$mountRoot",
        "spring.datasource.url=jdbc:postgresql://localhost:5432/submission",
        "spring.datasource.username=submission_app",
    )

    private fun AssertableApplicationContext.applicationPool() = getBean(DataSource::class.java).shouldBeInstanceOf<HikariDataSource>()

    private fun AssertableApplicationContext.failureMessages() = generateSequence(startupFailure.shouldNotBeNull()) { it.cause }.joinToString(" | ") { it.message.orEmpty() }

    @Test
    fun `the application pool takes its credentials from the named SecretRef`(
        @TempDir mountRoot: Path,
    ) {
        Files.writeString(mountRoot.resolve("db-app"), "app-password-1")
        service(mountRoot).withPropertyValues("service-runtime.datasource.credential=db-app").run { context ->
            context.startupFailure shouldBe null
            val pool = context.applicationPool()
            val provider = pool.credentialsProvider.shouldBeInstanceOf<SecretStoreCredentialsProvider>()
            provider.username shouldBe "submission_app"
            provider.passwordRef.value shouldBe "db-app"
            pool.password.shouldBeNull()
            pool.isRunning shouldBe false
            provider.credentials.password shouldBe "app-password-1"
        }
    }

    @Test
    fun `without the property nothing changes`(
        @TempDir mountRoot: Path,
    ) {
        service(mountRoot).run { context ->
            context.startupFailure shouldBe null
            context.applicationPool().credentialsProvider.shouldBeNull()
        }
    }

    @Test
    fun `start-up fails when spring-datasource-password is set as well, naming the property only (CS-24)`(
        @TempDir mountRoot: Path,
    ) {
        Files.writeString(mountRoot.resolve("db-app"), "app-password-1")
        service(mountRoot)
            .withPropertyValues("service-runtime.datasource.credential=db-app", "spring.datasource.password=property-password-1")
            .run { context ->
                val messages = context.failureMessages()
                messages shouldContain
                    "service-runtime.datasource.credential takes the database password through SecretStore (STK-29), " +
                    "so spring.datasource.password must not be set as well"
                messages shouldNotContain "property-password-1"
                messages shouldNotContain "app-password-1"
            }
    }

    @Test
    fun `start-up fails when no username names the role`(
        @TempDir mountRoot: Path,
    ) {
        tpl18Service()
            .withPropertyValues(
                "gst.bindings.secret-store.mount-root=$mountRoot",
                "spring.datasource.url=jdbc:postgresql://localhost:5432/submission",
                "service-runtime.datasource.credential=db-app",
            ).run { context ->
                context.failureMessages() shouldContain
                    "service-runtime.datasource.credential is set, so spring.datasource.username must name the database role"
            }
    }
}
