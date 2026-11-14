package com.dynokore.gst.submission.bootstrap

import com.dynokore.gst.submission.application.listing.ListSubmissions
import com.dynokore.gst.submission.application.listing.ListSubmissionsRequest
import com.dynokore.gst.submission.application.listing.ListSubmissionsResult
import com.dynokore.gst.submission.application.listing.SourceRead
import com.dynokore.gst.submission.application.listing.SubmissionRowSource
import com.dynokore.gst.submission.application.listing.SubmissionSummaryQuery
import com.dynokore.gst.submission.application.listing.SubmissionSummaryRequest
import com.dynokore.gst.submission.application.listing.SubmissionSummaryResult
import com.dynokore.gst.submission.domain.listing.SubmissionFilter
import com.dynokore.platform.serviceruntime.replica.AuthorityReplica
import com.dynokore.platform.serviceruntime.replica.OperationRoleMap
import com.dynokore.platform.serviceruntime.replica.Principal
import com.dynokore.platform.serviceruntime.replica.PrincipalKind
import com.dynokore.platform.serviceruntime.resilience.Deadline
import com.dynokore.platform.tenancykernel.TenantId
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

/** The listing bean wiring built by [ListingConfiguration], over fakes (INV-AC-21818). */
class ListingConfigurationTest {
    private val clock = Clock.fixed(Instant.parse("2026-11-13T00:00:00Z"), ZoneOffset.UTC)
    private val deadline = Deadline.after(Duration.ofSeconds(5), clock)
    private val tenant = TenantId(UUID.fromString("00000000-0000-0000-0000-00000000000a"))
    private val stranger = Principal("stranger", PrincipalKind.HUMAN)

    private class RecordingSource : SubmissionRowSource {
        var calls = 0

        override fun rows(
            tenant: TenantId,
            entityRef: String,
            periodRef: String,
        ): SourceRead {
            calls++
            return SourceRead(emptyList(), 0)
        }
    }

    private fun request() =
        ListSubmissionsRequest(
            tenant = tenant,
            principal = stranger,
            entityRef = "E1",
            periodRef = "P1",
            filter = SubmissionFilter(),
            sort = null,
            cursor = null,
            limit = 10,
            maxLimit = 500,
        )

    @Test
    fun `INV-AC-21818 the listSubmissions bean answers a denied caller like the hand-built use case`() {
        // a cold replica: no snapshot has completed, so every decision is denied (no role name is needed here)
        val replica = AuthorityReplica(clock, OperationRoleMap { emptyMap() })
        val source = RecordingSource()

        val wired: ListSubmissions = ListingConfiguration().listSubmissions(replica, source)
        val handBuilt = ListSubmissions(replica, source)

        val wiredResult = wired.handle(request(), deadline)
        val handBuiltResult = handBuilt.handle(request(), deadline)

        wiredResult shouldBe ListSubmissionsResult.Forbidden
        wiredResult shouldBe handBuiltResult
        source.calls shouldBe 0
    }

    @Test
    fun `INV-AC-22164 the submissionSummaryQuery bean answers a denied caller like the hand-built query`() {
        val replica = AuthorityReplica(clock, OperationRoleMap { emptyMap() })
        val source = RecordingSource()
        val request = SubmissionSummaryRequest(tenant, stranger, "E1", "P1", SubmissionFilter())

        val wired: SubmissionSummaryQuery = ListingConfiguration().submissionSummaryQuery(replica, source)
        val handBuilt = SubmissionSummaryQuery(replica, source)

        wired.summary(request, deadline) shouldBe SubmissionSummaryResult.Forbidden
        wired.summary(request, deadline) shouldBe handBuilt.summary(request, deadline)
        source.calls shouldBe 0
    }
}
