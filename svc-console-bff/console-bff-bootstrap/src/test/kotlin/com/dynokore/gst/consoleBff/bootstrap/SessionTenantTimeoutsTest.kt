package com.dynokore.gst.consoleBff.bootstrap

import com.dynokore.gst.consoleBff.adapters.session.PolicyTenantSessionPolicy
import com.dynokore.gst.consoleBff.application.session.SessionResolver
import com.dynokore.gst.consoleBff.application.session.TenantSessionPolicy
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

/** The per-tenant console session timeouts wiring (VP-84, INV-REQ-25100). */
class SessionTenantTimeoutsTest {
    @Test
    fun `INV-AC-25103 the context holds exactly one SessionResolver and one TenantSessionPolicy of the policy class`(
        @TempDir dir: Path,
    ) {
        ConsoleContext.offline(dir).run { context ->
            context.startupFailure shouldBe null
            context.getBeansOfType(SessionResolver::class.java).size shouldBe 1
            val policies = context.getBeansOfType(TenantSessionPolicy::class.java)
            policies.size shouldBe 1
            (policies.values.single() is PolicyTenantSessionPolicy) shouldBe true
        }
    }
}
