package com.dynokore.platform.productruntime

import com.dynokore.platform.productruntime.catalogue.GstOperationRoleMap
import com.dynokore.platform.serviceruntime.replica.PrincipalKind
import com.dynokore.platform.serviceruntime.replica.livePermissionKey
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import com.dynokore.gst.catalogue.DeploymentForm as GstForm

/**
 * INV-T-9258, owner answer INV-Q-19441 A: every permission key of a row of `high-risk-live-calls.tsv` has roles in the
 * generated catalogue for the machine key and for the person key the live call derives from it. The table of a
 * consuming service is not on this class path, so [ROW_KEYS] is the full set of today's rows; a row added later needs
 * its key here in the same change.
 */
class HighRiskLivePermissionKeysTest {
    private val forms = listOf(GstForm.SAAS, GstForm.PRIVATE)

    @Test
    fun `INV-AC-9051 the machine key and the person key of every live row hold roles under both forms`() {
        for (form in forms) {
            val map = GstOperationRoleMap(form)
            for (key in ROW_KEYS) {
                map.rolesFor(livePermissionKey(key, PrincipalKind.MACHINE)).keys.shouldNotBeEmpty()
                for (person in listOf(PrincipalKind.HUMAN, PrincipalKind.CONSENT_AGENT)) {
                    val personKey = livePermissionKey(key, person)
                    map.rolesFor(personKey).keys.shouldNotBeEmpty()
                }
            }
        }
    }

    @Test
    fun `INV-AC-9051 the person key of machine filing confirm is filing confirm and holds its roles`() {
        for (form in forms) {
            val map = GstOperationRoleMap(form)
            for (person in listOf(PrincipalKind.HUMAN, PrincipalKind.CONSENT_AGENT)) {
                val key = livePermissionKey("machine.filing.confirm", person)
                key shouldBe "filing.confirm"
                map.rolesFor(key) shouldBe map.rolesFor("filing.confirm")
            }
        }
    }

    private companion object {
        val ROW_KEYS = listOf("machine.filing.confirm")
    }
}
