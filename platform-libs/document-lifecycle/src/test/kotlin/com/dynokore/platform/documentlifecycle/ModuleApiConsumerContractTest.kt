package com.dynokore.platform.documentlifecycle

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

/**
 * Consumer-driven contract test of the module API (the ModuleApiConsumerContractTest pattern): a stub written the way
 * `submission-svc` writes its resubmission and supersession use case — it instantiates [DocumentLifecycle] with its
 * own model and drives only the public surface — run against the real implementation. `submission-svc` does not exist
 * yet: the stub plays its role. If the supersession, the guard or the exceptions drift from what the service expects,
 * this fails.
 */
class ModuleApiConsumerContractTest {
    private class SubmissionSvcStub(
        private val lifecycle: DocumentLifecycle,
    ) {
        fun received(id: String) = lifecycle.open(LogicalId(id))

        fun resubmit(doc: VersionedDocument) = doc.addVersion()

        fun move(
            doc: VersionedDocument,
            version: Int,
            to: String,
        ) = doc.transition(version, to)

        fun waiting(
            doc: VersionedDocument,
            version: Int,
        ): Boolean =
            try {
                doc.transition(version, "QUEUED")
                false
            } catch (_: InFlightConflictException) {
                true
            }
    }

    private val svc = SubmissionSvcStub(Fixtures.lifecycle)

    @Test
    fun `a newer version cancels the unclaimed older one and only the newer is queued`() {
        var doc = svc.received("INV-1")
        doc = svc.move(svc.move(doc, 1, "READY"), 1, "QUEUED")
        doc = svc.move(svc.resubmit(doc), 2, "READY")
        assertEquals(listOf("CANCELLED", "READY"), doc.versions.map { it.state })
        assertEquals(listOf("CANCELLED", "QUEUED"), svc.move(doc, 2, "QUEUED").versions.map { it.state })
    }

    @Test
    fun `a newer version waits while the older one is in flight`() {
        var doc = svc.received("INV-2")
        listOf("READY", "QUEUED", "SENT", "RETRY").forEach { doc = svc.move(doc, 1, it) }
        doc = svc.move(svc.resubmit(doc), 2, "READY")
        assert(svc.waiting(doc, 2))
        assertThrows(IllegalTransitionException::class.java) { svc.move(doc, 1, "READY") }
    }
}
