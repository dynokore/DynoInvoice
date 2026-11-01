package com.dynokore.gst.ingestion.domain.testmode

import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.Test

class TestRequestRoutingTest {
    private val refused = TestRouting.Refused("ING-021", 422)

    private fun route(
        marked: Boolean,
        workspace: WorkspaceKind,
        ref: String?,
    ) = TestRequestRouting.route(marked, workspace, ref)

    @Test
    fun `INV-AC-14108 non-production workspace gives InRequestWorkspace marked or not with or without a reference`() {
        for (marked in listOf(true, false)) {
            for (ref in listOf("ws-1", null)) {
                route(marked, WorkspaceKind.NON_PRODUCTION, ref) shouldBe TestRouting.InRequestWorkspace
            }
        }
    }

    @Test
    fun `INV-AC-14108 unmarked production request gives Live with and without a reference`() {
        route(false, WorkspaceKind.PRODUCTION, "ws-1") shouldBe TestRouting.Live
        route(false, WorkspaceKind.PRODUCTION, null) shouldBe TestRouting.Live
    }

    @Test
    fun `INV-AC-14108 marked production request with reference ws-1 goes to the paired workspace`() {
        route(true, WorkspaceKind.PRODUCTION, "ws-1") shouldBe TestRouting.InPairedWorkspace("ws-1")
    }

    @Test
    fun `INV-AC-14108 marked production request with null reference is refused with ING-021 and 422`() {
        route(true, WorkspaceKind.PRODUCTION, null) shouldBe refused
    }

    @Test
    fun `INV-AC-14108 marked production request with an empty reference is refused`() {
        route(true, WorkspaceKind.PRODUCTION, "") shouldBe refused
    }

    @Test
    fun `INV-AC-14108 marked production request with a blank reference is refused`() {
        route(true, WorkspaceKind.PRODUCTION, "  ") shouldBe refused
    }

    @Test
    fun `INV-AC-14108 a marked production request never gives Live`() {
        for (ref in listOf(null, "", " ", "ws-1")) {
            route(true, WorkspaceKind.PRODUCTION, ref) shouldNotBe TestRouting.Live
        }
    }

    @Test
    fun `INV-AC-14108 the constants are ING-021 and 422`() {
        TestRequestRouting.ING_021 shouldBe "ING-021"
        TestRequestRouting.UNPROCESSABLE shouldBe 422
    }
}
