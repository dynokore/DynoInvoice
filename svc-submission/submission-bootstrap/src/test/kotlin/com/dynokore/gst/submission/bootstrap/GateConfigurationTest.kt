package com.dynokore.gst.submission.bootstrap

import com.dynokore.gst.submission.adapters.gate.AuthorityReplicaOverrideAuthority
import com.dynokore.gst.submission.adapters.gate.HttpReleaseDecisions
import com.dynokore.gst.submission.adapters.gate.PolicyReplicaGateModes
import com.dynokore.gst.submission.adapters.http.FilingGateOverrides
import com.dynokore.gst.submission.adapters.http.ReadinessController
import com.dynokore.gst.submission.adapters.persistence.JooqFilingGateAuditTrail
import com.dynokore.gst.submission.adapters.persistence.JooqOverrideStore
import com.dynokore.gst.submission.adapters.persistence.ReadinessLatestChecks
import com.dynokore.gst.submission.application.gate.ApproveOverride
import com.dynokore.gst.submission.application.gate.AuditTrail
import com.dynokore.gst.submission.application.gate.GateModes
import com.dynokore.gst.submission.application.gate.LatestChecks
import com.dynokore.gst.submission.application.gate.OverrideAuthority
import com.dynokore.gst.submission.application.gate.OverrideStore
import com.dynokore.gst.submission.application.gate.ReadOverrides
import com.dynokore.gst.submission.application.gate.ReleaseDecisions
import com.dynokore.gst.submission.application.gate.RequestOverride
import com.dynokore.gst.submission.application.readiness.ReadinessCheckReader
import com.dynokore.gst.submission.domain.gate.REFUSAL_CODE
import com.dynokore.gst.submission.domain.gate.ReleaseOutcome
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

/** TPL-18 (P109): the filing-gate override wired the way the service starts, with the ports as beans. */
class GateConfigurationTest {
    @Test
    fun `the filing-gate beans exist in the started context and the controller is built from them`(
        @TempDir dir: Path,
    ) {
        SubmissionContext.offline(dir).run { context ->
            context.startupFailure.shouldBeNull()
            context.getBean(GateModes::class.java).shouldBeInstanceOf<PolicyReplicaGateModes>()
            context.getBean(FilingGateOverrides::class.java)
            context.getBean(AuditTrail::class.java).shouldBeInstanceOf<JooqFilingGateAuditTrail>()
            listOf(RequestOverride::class, ApproveOverride::class, ReadOverrides::class, ReadinessController::class)
                .forEach { context.getBean(it.java) }
        }
    }

    @Test
    fun `the context holds exactly one bean of each store and reader type of main and the store is the jooq store`(
        @TempDir dir: Path,
    ) {
        SubmissionContext.offline(dir).run { context ->
            context.startupFailure.shouldBeNull()
            listOf(
                OverrideStore::class.java,
                LatestChecks::class.java,
                OverrideAuthority::class.java,
                ReadinessCheckReader::class.java,
            ).forEach { type -> context.getBeanNamesForType(type).size shouldBe 1 }
            context.getBean(OverrideStore::class.java).shouldBeInstanceOf<JooqOverrideStore>()
            context.getBean(OverrideAuthority::class.java).shouldBeInstanceOf<AuthorityReplicaOverrideAuthority>()
            context.getBean(LatestChecks::class.java).shouldBeInstanceOf<ReadinessLatestChecks>()
        }
    }

    @Test
    fun `with no access-svc URL every release decision is a denial and with one the generated client is used`(
        @TempDir dir: Path,
    ) {
        SubmissionContext.offline(dir).run { context ->
            context.startupFailure.shouldBeNull()
            context.getBean(ReleaseDecisions::class.java).decide("E1", REFUSAL_CODE, "A1", emptyList()) shouldBe
                ReleaseOutcome.Denied(ReleaseOutcome.NOT_OVERRIDABLE)
        }
        SubmissionContext
            .offline(dir)
            .withPropertyValues("product-runtime.fallback.access-svc-url=http://127.0.0.1:9")
            .run { context ->
                context.startupFailure.shouldBeNull()
                context.getBean(ReleaseDecisions::class.java).shouldBeInstanceOf<HttpReleaseDecisions>()
                // nothing listens there: a connection failure is a denial
                context.getBean(ReleaseDecisions::class.java).decide("E1", REFUSAL_CODE, "A1", emptyList()) shouldBe
                    ReleaseOutcome.Denied(ReleaseOutcome.NOT_OVERRIDABLE)
            }
    }
}
