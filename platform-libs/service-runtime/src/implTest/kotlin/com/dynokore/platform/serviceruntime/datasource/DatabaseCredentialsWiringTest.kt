package com.dynokore.platform.serviceruntime.datasource

import com.dynokore.platform.serviceruntime.binding.MountedFileSecretStore
import com.dynokore.platform.serviceruntime.binding.SecretNotFound
import com.dynokore.platform.serviceruntime.binding.SecretRef
import com.zaxxer.hikari.HikariDataSource
import io.kotest.assertions.throwables.shouldThrow
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
    private fun service(mountRoot: Path) =
        tpl18Service().withPropertyValues(
            "gst.bindings.secret-store.mount-root=$mountRoot",
            "spring.datasource.url=jdbc:postgresql://localhost:5432/submission",
            "spring.datasource.username=submission_app",
        )

    private fun AssertableApplicationContext.applicationPool() = getBean(DataSource::class.java) as HikariDataSource

    // The pool reads sit in method bodies of this class, the one that holds the exempt-test line, and return no
    // HikariCP type (INV-T-4790).
    private fun assertPoolCredentialsFromRef(
        context: AssertableApplicationContext,
        username: String,
        ref: String,
        password: String,
    ) {
        val pool = context.applicationPool()
        val provider = pool.credentialsProvider.shouldBeInstanceOf<SecretStoreCredentialsProvider>()
        provider.username shouldBe username
        provider.passwordRef.value shouldBe ref
        pool.password.shouldBeNull()
        pool.isRunning shouldBe false
        provider.credentials.password shouldBe password
    }

    private fun assertNoCredentialsProvider(context: AssertableApplicationContext) {
        context.applicationPool().credentialsProvider.shouldBeNull()
    }

    private fun AssertableApplicationContext.failureMessages() =
        generateSequence(startupFailure.shouldNotBeNull()) {
            it.cause
        }.joinToString(" | ") { it.message.orEmpty() }

    @Test
    fun `the application pool takes its credentials from the named SecretRef`(
        @TempDir mountRoot: Path,
    ) {
        Files.writeString(mountRoot.resolve("db-app"), "app-password-1")
        service(mountRoot).withPropertyValues("service-runtime.datasource.credential=db-app").run { context ->
            context.startupFailure shouldBe null
            assertPoolCredentialsFromRef(context, "submission_app", "db-app", "app-password-1")
        }
    }

    @Test
    fun `without the property nothing changes`(
        @TempDir mountRoot: Path,
    ) {
        service(mountRoot).run { context ->
            context.startupFailure shouldBe null
            assertNoCredentialsProvider(context)
        }
    }

    @Test
    fun `start-up fails when spring-datasource-password is set as well, naming the property only (CS-24, INV-Q-4548)`(
        @TempDir mountRoot: Path,
    ) {
        Files.writeString(mountRoot.resolve("db-app"), "app-password-1")
        service(mountRoot)
            .withPropertyValues(
                "service-runtime.datasource.credential=db-app",
                "spring.datasource.password=property-password-1",
            ).run { context ->
                val messages = context.failureMessages()
                // the key check refuses it first, before any bean (coordinator rulings INV-Q-4548, INV-Q-4559)
                messages shouldContain "spring.datasource.password"
                messages shouldContain "INV-Q-4542"
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

    // The provider tests, moved from SecretStoreCredentialsProviderTest: the provider's accessors return HikariCP's
    // Credentials, which only this class may read in the test code (INV-T-4790).
    @Test
    fun `every call reads the password mounted at that moment`(
        @TempDir mountRoot: Path,
    ) {
        val file = mountRoot.resolve("db-app")
        Files.writeString(file, "first-value\n")
        val provider =
            SecretStoreCredentialsProvider(MountedFileSecretStore(mountRoot), "submission_app", SecretRef("db-app"))

        provider.credentials.username shouldBe "submission_app"
        provider.credentials.password shouldBe "first-value"
        Files.writeString(file, "rotated-value")
        provider.credentials.password shouldBe "rotated-value"
        Files.delete(file)
        shouldThrow<SecretNotFound> { provider.credentials }.message shouldBe "no secret bound at 'db-app'"
    }

    @Test
    fun `toString names the role and the reference only (CS-24)`(
        @TempDir mountRoot: Path,
    ) {
        Files.writeString(mountRoot.resolve("db-app"), "never-shown")
        val provider =
            SecretStoreCredentialsProvider(MountedFileSecretStore(mountRoot), "submission_app", SecretRef("db-app"))
        provider.credentials
        provider.toString() shouldBe "SecretStoreCredentialsProvider(username=submission_app, passwordRef=db-app)"
    }

    @Test
    fun `a blank username is refused`(
        @TempDir mountRoot: Path,
    ) {
        shouldThrow<IllegalArgumentException> {
            SecretStoreCredentialsProvider(MountedFileSecretStore(mountRoot), " ", SecretRef("db-app"))
        }
    }
}
