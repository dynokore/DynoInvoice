package com.dynokore.gst.submission.bootstrap

import com.dynokore.gst.submission.adapters.persistence.JooqFilingDueDates
import com.dynokore.gst.submission.adapters.persistence.ReadinessLatestChecks
import com.dynokore.gst.submission.adapters.policy.PolicyGateModes
import com.dynokore.gst.submission.application.filing.ConfirmFiling
import com.dynokore.gst.submission.application.filing.FilingDueDates
import com.dynokore.gst.submission.application.filing.GateModesAt
import com.dynokore.gst.submission.application.gate.LatestChecks
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.annotation.Bean
import java.nio.file.Path
import java.time.ZoneId

/** P109 wiring of `confirmFiling` (INV-REQ-914, INV-AC-20947): the business zone is configuration, never code. */
class FilingWiringTest {
    /**
     * Only the key and the zone bean of [FilingConfiguration]: the start fails on the key alone. Not a
     * `@Configuration`: the component scan of the full context must not pick it up.
     */
    @EnableConfigurationProperties(FilingProperties::class)
    class ZoneOnly {
        @Bean
        fun zone(properties: FilingProperties): ZoneId = FilingConfiguration().filingBusinessZone(properties)
    }

    private val zoneOnly = ApplicationContextRunner().withUserConfiguration(ZoneOnly::class.java)

    @Test
    fun `INV-AC-20947 a start with the key missing fails`() {
        zoneOnly.run { (it.startupFailure != null) shouldBe true }
    }

    @Test
    fun `INV-AC-20947 a start with a blank key fails`() {
        zoneOnly.withPropertyValues("inv.filing.business-zone=").run { (it.startupFailure != null) shouldBe true }
        zoneOnly.withPropertyValues("inv.filing.business-zone=   ").run { (it.startupFailure != null) shouldBe true }
    }

    @Test
    fun `INV-AC-20947 a start with a value that is not a zone id fails`() {
        zoneOnly
            .withPropertyValues(
                "inv.filing.business-zone=Not/AZone",
            ).run { (it.startupFailure != null) shouldBe true }
        zoneOnly
            .withPropertyValues(
                "inv.filing.business-zone=+08:00x",
            ).run { (it.startupFailure != null) shouldBe true }
    }

    @Test
    fun `INV-AC-20947 a start with Asia Singapore succeeds and the zone is that zone`() {
        zoneOnly.withPropertyValues("inv.filing.business-zone=Asia/Singapore").run {
            (it.startupFailure == null) shouldBe true
            it.getBean(ZoneId::class.java) shouldBe ZoneId.of("Asia/Singapore")
        }
    }

    @Test
    fun `INV-AC-20947 the full service context takes the zone from application yaml and fails on a bad value`(
        @TempDir dir: Path,
    ) {
        SubmissionContext.offline(dir).run { context ->
            context.getBean("filingBusinessZone", ZoneId::class.java) shouldBe ZoneId.of("Asia/Singapore")
        }
        SubmissionContext.offline(dir).withPropertyValues("inv.filing.business-zone=Nowhere/Land").run { context ->
            (context.startupFailure != null) shouldBe true
        }
    }

    @Test
    fun `the ConfirmFiling bean is built from the three adapter beans`(
        @TempDir dir: Path,
    ) {
        SubmissionContext.offline(dir).run { context ->
            context
                .getBeansOfType(FilingDueDates::class.java)
                .values
                .single()
                .javaClass shouldBe
                JooqFilingDueDates::class.java
            context
                .getBeansOfType(LatestChecks::class.java)
                .values
                .single()
                .javaClass shouldBe
                ReadinessLatestChecks::class.java
            context
                .getBeansOfType(GateModesAt::class.java)
                .values
                .single()
                .javaClass shouldBe
                PolicyGateModes::class.java
            context.getBeansOfType(ConfirmFiling::class.java).size shouldBe 1
        }
    }
}
