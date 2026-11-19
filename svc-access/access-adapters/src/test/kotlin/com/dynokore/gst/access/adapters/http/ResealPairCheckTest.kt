package com.dynokore.gst.access.adapters.http

import com.dynokore.gst.access.application.breakglass.BreakGlassSession
import com.dynokore.gst.access.application.breakglass.BreakGlassSessions
import com.dynokore.gst.access.domain.breakglass.BreakGlassAction
import com.dynokore.platform.tenancykernel.TransactionRunner
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant

/** The caller check of `resealBreakGlass` is membership of the stored pair and nothing else (INV-AC-24943). */
class ResealPairCheckTest {
    private val tx =
        object : TransactionRunner {
            override fun <T> inTransaction(block: () -> T): T = block()
        }

    private fun session(vararg approvedBy: String) =
        BreakGlassSession("BG-1", "t", 4, Instant.EPOCH, Instant.EPOCH, approvedBy.toList(), false)

    private fun check(found: () -> BreakGlassSession?): ResealPairCheck =
        StoredPairResealCheck(
            tx,
            object : BreakGlassSessions {
                override fun insert(
                    sessionHours: Int,
                    unlockedAt: Instant,
                    approvedBy: List<String>,
                ) = error("not used")

                override fun lockTenant() = Unit

                override fun latest(): BreakGlassSession? = null

                override fun find(ref: String) = found()

                override fun markSealed(ref: String) = error("not used")

                override fun insertAction(
                    unlockRef: String,
                    action: BreakGlassAction,
                    target: String?,
                    callerService: String,
                ) = error("not used")
            },
        )

    @Test
    fun `INV-AC-24943 a member is admitted and a look-alike, a non-member and a blank caller are not`() {
        val check = check { session("user:a", "user:b") }
        assertThat(check.admit("user:a", "BG-1")).isEqualTo(ResealAdmission.MEMBER)
        assertThat(check.admit("user:b", "BG-1")).isEqualTo(ResealAdmission.MEMBER)
        listOf("user:a ", " user:a", "USER:A", "user:", "user:c", "", "user:a,user:b").forEach {
            assertThat(check.admit(it, "BG-1")).describedAs("[$it]").isEqualTo(ResealAdmission.OUTSIDER)
        }
    }

    @Test
    fun `INV-AC-24943 an unknown unlock is UNKNOWN and a lookup that throws never admits`() {
        assertThat(check { null }.admit("user:a", "BG-1")).isEqualTo(ResealAdmission.UNKNOWN)
        assertThat(check { error("down") }.admit("user:a", "BG-1")).isEqualTo(ResealAdmission.OUTSIDER)
        assertThat(check { session() }.admit("user:a", "BG-1")).isEqualTo(ResealAdmission.OUTSIDER)
    }

    private val forbidden =
        listOf("deployment.operator", "DEPLOYMENT_OPERATOR", "BREAK_GLASS_RESEAL", "break-glass.reseal")

    private fun findings(source: String): List<String> = forbidden.filter { source.contains(it) }

    @Test
    fun `INV-AC-24943 neither ResealPairCheck nor BreakGlassController names a role or the reseal permission`() {
        val dir = Path.of("src/main/kotlin/com/dynokore/gst/access/adapters/http")
        listOf("ResealPairCheck.kt", "BreakGlassController.kt").forEach { name ->
            val source = Files.readString(dir.resolve(name))
            assertThat(findings(source)).describedAs(name).isEmpty()
            // probes: adding any one of the strings is a finding
            forbidden.forEach { assertThat(findings("$source\n// $it")).containsExactly(it) }
        }
    }
}
