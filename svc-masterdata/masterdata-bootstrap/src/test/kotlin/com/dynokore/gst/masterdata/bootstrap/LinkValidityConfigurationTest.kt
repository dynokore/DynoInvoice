package com.dynokore.gst.masterdata.bootstrap

import com.dynokore.gst.masterdata.application.request.SendCommand
import com.dynokore.gst.masterdata.application.request.SendCounterpartyRequest
import com.dynokore.gst.masterdata.application.request.SendOutcome
import com.dynokore.gst.masterdata.domain.counterparty.PartyRef
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.assertj.AssertableWebApplicationContext
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.time.Instant

/**
 * INV-AC-18555: the default and the maximum link validity are configuration (INV-T-18221). The keys are read from the
 * shipped `application.yaml`; a bad value fails the start naming the key; the expiry of the SignedLinkRequested is the
 * send instant plus the configured default.
 */
class LinkValidityConfigurationTest {
    private val defaultKey = ConfiguredLinkValidityLimits.DEFAULT_KEY
    private val maxKey = ConfiguredLinkValidityLimits.MAX_KEY

    private fun shipped(): String = Files.readString(Path.of("src", "main", "resources", "application.yaml"))

    @Test
    fun `INV-AC-18555 the shipped application yaml carries the default P7D and the maximum P30D`() {
        val text = shipped()
        assertThat(text).containsPattern("default: P7D\\b")
        assertThat(text).containsPattern("max: P30D\\b")
        val limits = ConfiguredLinkValidityLimits.of("P7D", "P30D")
        assertThat(limits.defaultDays).isEqualTo(7)
        assertThat(limits.maxDays).isEqualTo(30)
    }

    @Test
    fun `INV-AC-18555 each bad value fails the start naming the key`() {
        val bad =
            listOf(
                Triple(null, "P30D", defaultKey),
                Triple("P7D", null, maxKey),
                Triple("", "P30D", defaultKey),
                Triple("seven days", "P30D", defaultKey),
                Triple("P7D", "30", maxKey),
                Triple("PT0S", "P30D", defaultKey),
                Triple("P7D", "P0D", maxKey),
                Triple("-P7D", "P30D", defaultKey),
                Triple("P7D", "-P30D", maxKey),
                Triple("PT36H", "P30D", defaultKey),
                Triple("P1D", "PT36H", maxKey),
                Triple("P31D", "P30D", defaultKey),
            )
        bad.forEach { (default, max, key) ->
            assertThatThrownBy { ConfiguredLinkValidityLimits.of(default, max) }
                .describedAs("default=$default max=$max")
                .isInstanceOf(LinkValidityConfigurationException::class.java)
                .hasMessageContaining(key)
                .extracting { (it as LinkValidityConfigurationException).key }
                .isEqualTo(key)
        }
    }

    @Test
    fun `INV-AC-18555 PT24H is accepted as one day`() {
        assertThat(ConfiguredLinkValidityLimits.of("PT24H", "P30D").defaultDays).isEqualTo(1)
        assertThat(ConfiguredLinkValidityLimits.of("P1D", "PT720H").maxDays).isEqualTo(30)
    }

    @Test
    fun `INV-AC-18555 a default equal to the maximum and the overrides P3D and P10D are accepted`() {
        assertThat(ConfiguredLinkValidityLimits.of("P30D", "P30D").defaultDays).isEqualTo(30)
        val overridden = ConfiguredLinkValidityLimits.of("P3D", "P10D")
        assertThat(overridden.defaultDays).isEqualTo(3)
        assertThat(overridden.maxDays).isEqualTo(10)
    }

    @Test
    fun `INV-AC-18555 the service does not start with a bad value and the failure names the key`() {
        BoundaryFixture
            .runner()
            .withPropertyValues("$defaultKey=PT0S")
            .run { context ->
                assertThat(context.startupFailure).isNotNull
                assertThat(rootMessages(context.startupFailure!!)).contains(defaultKey)
            }
        BoundaryFixture
            .runner()
            .withPropertyValues("$defaultKey=P31D")
            .run { context ->
                assertThat(context.startupFailure).isNotNull
                assertThat(rootMessages(context.startupFailure!!)).contains(defaultKey)
            }
    }

    @Test
    fun `INV-AC-18555 the SignedLinkRequested expires 7 days after the send, 3 days with the override`() {
        assertThat(expiryAfterSend()).isEqualTo(Duration.ofDays(7))
        assertThat(expiryAfterSend("$defaultKey=P3D", "$maxKey=P10D")).isEqualTo(Duration.ofDays(3))
    }

    private fun rootMessages(failure: Throwable): String =
        generateSequence(failure) { it.cause }.mapNotNull { it.message }.joinToString(" | ")

    private fun expiryAfterSend(vararg overrides: String): Duration {
        var result: Duration? = null
        BoundaryFixture
            .runner()
            .withPropertyValues(*overrides)
            .run { context ->
                context.startupFailure?.let { throw it }
                result = sendOnce(CounterpartyEnv(context), context)
            }
        return checkNotNull(result)
    }

    private fun sendOnce(
        env: CounterpartyEnv,
        context: AssertableWebApplicationContext,
    ): Duration {
        val send = context.getBean(SendCounterpartyRequest::class.java)
        val before = Instant.now()
        val outcome =
            env.inTenant {
                send.handle(SendCommand("E-LINK-VALIDITY", PartyRef(unique("CTP-VAL")), "ap@example.com.sg", null))
            }
        val sent = outcome as SendOutcome.Sent
        assertThat(sent.emitted).isTrue()
        val payload =
            env.captured.envelopes
                .last { it.type.endsWith("SignedLinkRequested.v1") }
                .dataJson
        val expiry = Instant.parse(Regex("\"expiry\":\"([^\"]+)\"").find(payload)!!.groupValues[1])
        assertThat(expiry).isEqualTo(sent.request.expiresAt)
        return Duration.between(sent.request.lastRequestedAt, expiry).also {
            assertThat(sent.request.lastRequestedAt).isBetween(before, Instant.now())
        }
    }
}
