package com.dynokore.gst.consoleBff.bootstrap

import com.dynokore.gst.consoleBff.adapters.session.ConsoleSessionFilter
import com.dynokore.gst.consoleBff.application.session.PrincipalStanding
import com.dynokore.gst.consoleBff.application.session.SessionResolver
import com.dynokore.gst.consoleBff.application.session.SessionStore
import com.dynokore.gst.consoleBff.domain.session.SessionPolicy
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.jooq.Schema
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.time.Clock
import java.time.Duration
import javax.sql.DataSource

/**
 * TPL-16 and TPL-18 (P109): the service built the way TPL-18 writes it, over the Flyway-built database. The
 * service-runtime start-up check of INV-Q-4815 passes because exactly one `org.jooq.Schema` bean holds the table.
 */
class Tpl16ServiceCompositionTest {
    @Test
    fun `INV-AC-14457 the application starts with one Schema bean holding console_session and one DataSource`(
        @TempDir dir: Path,
    ) {
        ConsoleContext.online(dir).run { context ->
            context.startupFailure.shouldBeNull()
            context.getBeanNamesForType(DataSource::class.java).toList().size shouldBe 1
            val schemas = context.getBeansOfType(Schema::class.java).values.toList()
            schemas.size shouldBe 1
            schemas.single().tables.map { it.name }.toSet() shouldBe EXPECTED_TABLES
            context.environment.getProperty("gst.service") shouldBe "console-bff"
        }
    }

    @Test
    fun `INV-AC-21022 the context starts on the migrated database and its Schema bean holds the expected tables`(
        @TempDir dir: Path,
    ) {
        ConsoleContext.online(dir).run { context ->
            context.startupFailure.shouldBeNull()
            val schemas = context.getBeansOfType(Schema::class.java).values.toList()
            schemas.size shouldBe 1
            schemas.single().tables.map { it.name }.toSet() shouldBe EXPECTED_TABLES
        }
    }

    @Test
    fun `INV-AC-14457 the context holds one resolver one store one standing and one filter`(
        @TempDir dir: Path,
    ) {
        ConsoleContext.online(dir).run { context ->
            context.startupFailure.shouldBeNull()
            context.getBeanNamesForType(SessionResolver::class.java).size shouldBe 1
            context.getBeanNamesForType(SessionStore::class.java).size shouldBe 1
            context.getBeanNamesForType(PrincipalStanding::class.java).size shouldBe 1
            context.getBeanNamesForType(ConsoleSessionFilter::class.java).size shouldBe 1
            val clock = context.getBean(Clock::class.java)
            val policy = context.getBean(SessionPolicy::class.java)
            policy.idle shouldBe Duration.ofMinutes(30)
            policy.absolute shouldBe Duration.ofHours(12)
            val resolver = context.getBean(SessionResolver::class.java)
            val field = SessionResolver::class.java.getDeclaredField("clock").apply { isAccessible = true }
            field.get(resolver) shouldBe clock
            val policyField = SessionResolver::class.java.getDeclaredField("policy").apply { isAccessible = true }
            policyField.get(resolver) shouldBe policy
        }
    }

    @Test
    fun `INV-AC-14457 a copy of the configuration with no Schema bean fails the start`(
        @TempDir dir: Path,
    ) {
        ConsoleContext
            .online(dir)
            .withInitializer { ctx ->
                ctx.addBeanFactoryPostProcessor { factory ->
                    (factory as org.springframework.beans.factory.support.BeanDefinitionRegistry)
                        .removeBeanDefinition("consoleSchema")
                }
            }.run { context -> (context.startupFailure != null) shouldBe true }
    }

    @Test
    fun `INV-AC-14435 the slice registers no controller`(
        @TempDir dir: Path,
    ) {
        ConsoleContext.online(dir).run { context ->
            context.startupFailure.shouldBeNull()
            context
                .getBeansWithAnnotation(org.springframework.web.bind.annotation.RestController::class.java)
                .keys
                .toList() shouldBe emptyList()
            context
                .getBeansWithAnnotation(org.springframework.stereotype.Controller::class.java)
                .keys
                .toList() shouldBe emptyList()
        }
    }

    @Test
    fun `INV-AC-14435 the management port and the health groups are configured`(
        @TempDir dir: Path,
    ) {
        ConsoleContext.online(dir).run { context ->
            val environment = context.environment
            environment.getProperty("management.server.port") shouldBe "8081"
            environment.getProperty("management.endpoint.health.group.live.include") shouldBe "livenessState"
            environment.getProperty("management.endpoints.web.exposure.include") shouldBe
                "health,prometheus,diagnostics"
        }
    }

    private companion object {
        /** `console_session` plus what code generation adds for the tables, views and functions of OutboxSchema.ddl. */
        val EXPECTED_TABLES =
            setOf(
                "console_session",
                "consumer_offset",
                "consumer_replay",
                "consumer_replay_marker",
                "consumer_sequence",
                "consumer_source_seen",
                "event_dead_letter",
                "event_dead_letter_triage_page",
                "event_dead_letter_triage_v",
                "inbox",
                "outbox",
                "outbox_claim_page",
                "outbox_claim_v",
                "outbox_sequence",
            )
    }
}
