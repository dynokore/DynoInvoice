package com.dynokore.gst.access.bootstrap

import com.dynokore.gst.access.domain.role.Allowance
import com.dynokore.gst.access.domain.role.Permission
import com.dynokore.gst.access.domain.role.Role
import com.dynokore.gst.catalogue.DeploymentForm
import com.dynokore.gst.catalogue.VariationPoint
import com.dynokore.gst.catalogue.VpStatus
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.io.File

/**
 * The pins and the contract bundle of INV-T-4938 (INV-Q-8669 A): the libraries resolve at or above the versions that
 * carry what this service uses (compared as versions, not as strings), and the staged contract holds the operations,
 * the event and the VP-08 status the code is written against. The merge-base check of the bundle commit is a
 * git question and is shown in the merge request.
 */
class PinsAndContractBundleTest {
    private val lock = File("gradle.lockfile").readText()
    private val toml = File("../gradle/libs.versions.toml").readText()

    private fun parts(version: String): List<Int> = version.split('.').map { it.toInt() }

    private fun atLeast(
        actual: List<Int>,
        floor: List<Int>,
    ): Boolean {
        for (i in 0 until maxOf(actual.size, floor.size)) {
            val a = actual.getOrElse(i) { 0 }
            val f = floor.getOrElse(i) { 0 }
            if (a != f) return a > f
        }
        return true
    }

    private fun locked(
        text: String,
        module: String,
    ): List<Int> =
        parts(
            Regex("""(?m)^com\.dynokore\.platform:${Regex.escape(module)}:([0-9.]+)=""").find(text)!!.groupValues[1],
        )

    @Test
    fun `service-runtime resolves at 10_4_0 or later and outbox-and-queue at 6_3_0 or later, compared as versions`() {
        assertThat(atLeast(locked(lock, "service-runtime"), parts("10.4.0"))).isTrue()
        assertThat(atLeast(locked(lock, "outbox-and-queue"), parts("6.3.0"))).isTrue()
        // probes: service-runtime 10.3.x fails, and 10.10.0 passes where a string compare would fail it
        assertThat(atLeast(parts("10.3.9"), parts("10.4.0"))).isFalse()
        assertThat(atLeast(parts("10.10.0"), parts("10.4.0"))).isTrue()
        assertThat(atLeast(parts("6.2.2"), parts("6.3.0"))).isFalse()
    }

    private fun resource(path: String): String =
        checkNotNull(PinsAndContractBundleTest::class.java.classLoader.getResourceAsStream(path)) { "$path is missing" }
            .use { it.readBytes().toString(Charsets.UTF_8) }

    /** The findings of a staged contract that lacks what INV-T-4938 is written against. */
    private fun findings(
        openapi: String,
        asyncapi: String,
        status: VpStatus,
    ): List<String> =
        buildList {
            listOf("recordBreakGlassAction", "signInBreakGlass", "unlockBreakGlass").forEach {
                if (!Regex("""operationId:\s*$it\b""").containsMatchIn(openapi)) add("operation $it is absent")
            }
            if (!Regex("""\bBreakGlassAlertRaised\b""").containsMatchIn(asyncapi)) {
                add("message BreakGlassAlertRaised is absent")
            }
            if (status != VpStatus.AVAILABLE) add("VP-08 is $status")
        }

    @Test
    fun `the staged contract holds the break-glass operations, the event message and VP-08 available`() {
        val openapi = resource("gst/contracts/access/openapi.yaml")
        val asyncapi = resource("gst/contracts/service-runtime/asyncapi.yaml")
        assertThat(findings(openapi, asyncapi, VariationPoint.VP_08.status)).isEmpty()
        // probes: the bundle of the first contract task alone, or an older catalogue, each fails
        assertThat(findings(openapi.replace("recordBreakGlassAction", "x"), asyncapi, VpStatus.AVAILABLE))
            .containsExactly("operation recordBreakGlassAction is absent")
        assertThat(findings(openapi, asyncapi.replace("BreakGlassAlertRaised", "x"), VpStatus.AVAILABLE))
            .containsExactly("message BreakGlassAlertRaised is absent")
        assertThat(findings(openapi, asyncapi, VpStatus.PLANNED)).containsExactly("VP-08 is PLANNED")
    }

    /** The findings of a staged contract that lacks what INV-T-8853 is written against (INV-T-8901 supplement). */
    private fun sealFindings(openapi: String): List<String> =
        buildList {
            listOf("signInBreakGlass", "resealBreakGlass").forEach {
                if (!Regex("""operationId:\s*$it\b""").containsMatchIn(openapi)) add("operation $it is absent")
            }
            listOf("BreakGlassResealResponse", "AcceptFirstAdministratorInvitationResponse").forEach {
                // the schema block must declare the one-time property
                val block = Regex("""(?ms)^    $it:\n(.*?)(?=^    \w)""").find(openapi)?.groupValues?.get(1)
                if (block == null) {
                    add("schema $it is absent")
                } else if (!block.contains("sealingCredential:")) {
                    add("schema $it has no sealingCredential")
                }
            }
        }

    @Test
    fun `INV-T-8853 the staged contract holds the seal operations and the sealingCredential schemas`() {
        val openapi = resource("gst/contracts/access/openapi.yaml")
        assertThat(sealFindings(openapi)).isEmpty()
        // probes: a bundle from before INV-T-8901 has the schemas without the property
        assertThat(sealFindings(openapi.replace("resealBreakGlass", "x")))
            .containsExactly("operation resealBreakGlass is absent")
        assertThat(sealFindings(openapi.replace("        sealingCredential:", "        sealingCredentialX:")))
            .containsExactly(
                "schema BreakGlassResealResponse has no sealingCredential",
                "schema AcceptFirstAdministratorInvitationResponse has no sealingCredential",
            )
    }

    @Test
    fun `the bundle pin is a dev build of a platform-contracts commit and not one of the earlier commits`() {
        val bundle = Regex("""(?m)^platformContractsBundle\s*=\s*"([^"]+)"""").find(toml)!!.groupValues[1]
        assertThat(bundle).matches("""0\.0\.0-dev\+[0-9a-f]{8}""")
        // f4cb676f precedes the access contract supplement; bcd44dfd holds INV-T-8672 alone (no event, VP-08 planned)
        assertThat(bundle.substringAfter('+')).isNotIn("f4cb676f", "bcd44dfd")
    }

    @Test
    fun `INV-AC-24943 the pins follow INV-T-24946 and the staged reseal role equals the staged unlock role`() {
        val bundle = Regex("""(?m)^platformContractsBundle\s*=\s*"([^"]+)"""").find(toml)!!.groupValues[1]
        // f6b7b6e6 is the merge commit of INV-T-24946; the merge-base check is shown in the merge request
        assertThat(bundle).isEqualTo("0.0.0-dev+f6b7b6e6")
        val catalogue = Regex("""(?m)^gstCatalogue\s*=\s*"([^"]+)"""").find(toml)!!.groupValues[1]
        assertThat(atLeast(parts(catalogue), parts("3.14.0"))).isTrue()
        // probes: a catalogue below fails; 3.9.0 is below 3.14.0 although a string compare says otherwise
        assertThat(atLeast(parts("3.13.0"), parts("3.14.0"))).isFalse()
        assertThat(atLeast(parts("3.9.0"), parts("3.14.0"))).isFalse()
        val openapi = resource("gst/contracts/access/openapi.yaml")
        assertThat(roleOf(openapi, "resealBreakGlass")).isNotBlank().isEqualTo(roleOf(openapi, "unlockBreakGlass"))
        assertThat(
            roleOf(openapi, "resealBreakGlass"),
        ).isNotEqualTo(roleOf(openapi.replace("deployment.operator", "x"), "unlockBreakGlass"))
    }

    /** The `x-operation` role text of [operation]: the lines of its operation block that name a role. */
    private fun roleOf(
        openapi: String,
        operation: String,
    ): String {
        val block = Regex("""(?s)operationId:\s*$operation\b(.*?)(?=operationId:|\z)""").find(openapi)!!.groupValues[1]
        val lines = block.lines().filter { it.contains("role", ignoreCase = true) }.map { it.trim() }
        return lines.joinToString("\n")
    }

    @Test
    fun `the catalogue pin gives break-glass-reseal the holders of break-glass-unlock (INV-T-16106, INV-T-24946)`() {
        val key = Permission("break-glass.reseal")
        val unlock = Permission("break-glass.unlock")
        DeploymentForm.entries.forEach { form ->
            val matrix = PermissionMatrixLoader.load(form)
            val holders = Role.entries.filter { matrix.allowance(it, key) == Allowance.PERMITTED }
            assertThat(holders).describedAs("$form").isNotEmpty
            assertThat(holders).describedAs("$form").isEqualTo(
                Role.entries.filter { matrix.allowance(it, unlock) == Allowance.PERMITTED },
            )
        }
        // the deployment operator holds the key in PRIVATE only; platform.engineer holds no cell
        val private = PermissionMatrixLoader.load(DeploymentForm.PRIVATE)
        assertThat(private.allowance(Role.DEPLOYMENT_OPERATOR, key)).isEqualTo(Allowance.PERMITTED)
        assertThat(private.allowance(Role.PLATFORM_ENGINEER, key)).isNotEqualTo(Allowance.PERMITTED)
    }
}
