package com.dynokore.platform.serviceruntime.replica

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.dynokore.platform.outboxandqueue.Retention
import com.dynokore.platform.serviceruntime.ServiceRuntimeConfiguration
import com.dynokore.platform.serviceruntime.composition.L1CompositionAutoConfiguration
import com.dynokore.platform.serviceruntime.datasource.withBindingKey
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.assertj.AssertableApplicationContext
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import java.time.Duration
import javax.sql.DataSource

/** INV-Q-13768 A, INV-Q-13770 A: the tombstone time-to-live is a property and the retention comes from the bean. */
class AuthorityTombstoneTtlWiringTest {
    private val property = "service-runtime.replica.authority-tombstone-ttl"
    private val runner =
        ApplicationContextRunner()
            .withBean(MeterRegistry::class.java, { SimpleMeterRegistry() })
            .withBindingKey()
            .withConfiguration(
                AutoConfigurations.of(
                    ServiceRuntimeConfiguration::class.java,
                    L1CompositionAutoConfiguration::class.java,
                ),
            ).withPropertyValues("service-runtime.workload-identity.audience=canonical-svc")

    private fun startupMessage(context: AssertableApplicationContext): String =
        generateSequence(context.startupFailure) { it.cause }.joinToString(" | ") { it.message.orEmpty() }

    @Test
    fun `no property leaves the ttl null`() {
        runner.run { context ->
            context.getBean(AuthorityReplica::class.java).tombstoneTtl shouldBe null
            context.getBean(AuthorityReplica::class.java).eventLogRetention shouldBe null
        }
    }

    @Test
    fun `the property with the default retention starts and the replica holds both`() {
        runner.withBean(Retention::class.java, { Retention() }).withPropertyValues("$property=PT721H").run { context ->
            val replica = context.getBean(AuthorityReplica::class.java)
            replica.tombstoneTtl shouldBe Duration.ofHours(721)
            replica.eventLogRetention shouldBe Duration.ofDays(30)
        }
    }

    @Test
    fun `a ttl equal to the retention or below it fails the start naming both values`() {
        for (bad in listOf("PT720H", "PT719H59M59S")) {
            runner
                .withBean(Retention::class.java, { Retention(Duration.ofDays(30), Duration.ofDays(30)) })
                .withPropertyValues("$property=$bad")
                .run { context ->
                    val message = startupMessage(context)
                    message shouldContain Duration.parse(bad).toString()
                    message shouldContain Duration.ofDays(30).toString()
                }
        }
    }

    @Test
    fun `the retention is the event-log retention and not the content window`() {
        runner
            .withBean(Retention::class.java, { Retention(Duration.ofDays(10), Duration.ofDays(30)) })
            .withPropertyValues("$property=P20D")
            .run { context ->
                startupMessage(context) shouldContain Duration.ofDays(30).toString()
            }
    }

    @Test
    fun `the property with no Retention bean fails the start naming the property and Retention`() {
        runner.withPropertyValues("$property=PT720H").run { context ->
            val message = startupMessage(context)
            message shouldContain property
            message shouldContain "Retention"
        }
    }

    @Test
    fun `the property not set logs the one warning`() {
        val logger = LoggerFactory.getLogger(ReplicaAutoConfiguration::class.java) as Logger
        val appender = ListAppender<ILoggingEvent>().also { it.start() }
        logger.addAppender(appender)
        try {
            runner.run { it.getBean(AuthorityReplica::class.java) }
        } finally {
            logger.detachAppender(appender)
        }
        appender.list.count { it.formattedMessage.contains("authority tombstones are not evicted") } shouldBe 1
    }

    @Test
    fun `the replica and its bean method take no database handle`() {
        val forbidden = setOf(DataSource::class.java.name, "org.jooq.DSLContext")
        val constructorTypes = AuthorityReplica::class.java.constructors.flatMap { it.parameterTypes.toList() }
        val beanTypes =
            ReplicaAutoConfiguration::class.java.declaredMethods
                .filter { it.name == "authorityReplica" }
                .flatMap { it.parameterTypes.toList() }
        (constructorTypes + beanTypes).map { it.name }.filter { it in forbidden } shouldBe emptyList()
        beanTypes.isNotEmpty() shouldBe true
    }
}
