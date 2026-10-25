package com.dynokore.gst.catalogue

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** INV-T-7987: the published library serves the role-by-permission table; asserted through that API only. */
class RolePermissionsTest {
    private val saas = DeploymentForm.SAAS
    private val privateForm = DeploymentForm.PRIVATE
    private val both = listOf(saas, privateForm)
    private val y = RoleAllowance.PERMITTED
    private val ws = RoleAllowance.WITHIN_SCOPE

    private fun cell(
        form: DeploymentForm,
        role: String,
        key: String,
    ) = RolePermissions.cells(form)[role]?.get(key)

    /** One row of the required-cell table; every other (role, form) is DENIED on that key. */
    private data class Req(
        val key: String,
        val kind: PermissionKind,
        val role: String,
        val allowance: RoleAllowance,
        val forms: List<DeploymentForm>,
    )

    private val required =
        listOf(
            Req("user.manage", PermissionKind.WRITE, "tenant.admin", y, both),
            Req("ap-credential.manage", PermissionKind.WRITE, "tenant.admin", y, both),
            Req("ap-credential.manage", PermissionKind.WRITE, "platform.engineer", y, listOf(saas)),
            Req("entity-config.manage", PermissionKind.WRITE, "entity.admin", y, both),
            Req("entity-config.confirm", PermissionKind.WRITE, "entity.admin", y, both),
            Req("entity-config.confirm", PermissionKind.WRITE, "tenant.admin", y, both),
            Req("entity-config.confirm", PermissionKind.WRITE, "tax.approver", y, both),
        ) +
            listOf("tenant.admin", "tax.operator", "tax.approver", "finance.viewer", "auditor").map {
                Req("doc.view.content", PermissionKind.READ, it, y, both)
            } +
            listOf(
                Req("doc.view.content", PermissionKind.READ, "agent.operator", RoleAllowance.WITHIN_SCOPE, both),
                Req("support-bundle.export", PermissionKind.READ, "deployment.operator", y, listOf(privateForm)),
                Req(
                    "regulatory-pack.import-verify",
                    PermissionKind.WRITE,
                    "deployment.operator",
                    y,
                    listOf(privateForm),
                ),
                Req("regulatory-pack.import-verify", PermissionKind.WRITE, "tax.approver", y, listOf(privateForm)),
                Req("regulatory-pack.arm", PermissionKind.WRITE, "tax.approver", y, listOf(privateForm)),
                Req("regulatory-pack.activate", PermissionKind.WRITE, "platform.engineer", y, listOf(saas)),
                Req("retention.delete.request", PermissionKind.WRITE, "platform.engineer", y, listOf(saas)),
                Req("retention.delete.request", PermissionKind.WRITE, "deployment.operator", y, listOf(privateForm)),
                Req("retention.delete.approve", PermissionKind.WRITE, "security.officer", y, listOf(saas)),
                Req("retention.delete.approve", PermissionKind.WRITE, "tax.approver", y, listOf(privateForm)),
            ) +
            listOf("elevation.decide", "access-review.run", "privacy-request.handle").map {
                Req(it, PermissionKind.WRITE, "security.officer", y, both)
            } +
            listOf("tenant.admin", "tax.approver", "auditor", "security.officer").map {
                Req("audit-log.view", PermissionKind.READ, it, y, both)
            } +
            Req("audit-log.view", PermissionKind.READ, "platform.engineer", y, listOf(saas)) +
            listOf("mapping-effect.acknowledge", "consent.grant", "consent.withdraw").map {
                Req(it, PermissionKind.WRITE, "client.signatory", RoleAllowance.WITHIN_SCOPE, both)
            } +
            listOf(
                Req("break-glass.unlock", PermissionKind.WRITE, "deployment.operator", y, listOf(privateForm)),
                Req("break-glass.unlock", PermissionKind.WRITE, "tenant.admin", y, both),
                Req("break-glass.unlock", PermissionKind.WRITE, "tax.approver", y, both),
            ) +
            listOf(
                "onboarding.entity-setup.configure",
                "onboarding.integration.configure",
                "onboarding.mapping-draft.configure",
            ).map { Req(it, PermissionKind.WRITE, "implementation.consultant", y, both) } +
            listOf("usage.view", "plan.view").flatMap {
                listOf("billing.contact", "tenant.admin").map { r -> Req(it, PermissionKind.READ, r, y, listOf(saas)) }
            } +
            listOf("billing.contact", "tenant.admin").map {
                Req("licence-status.view", PermissionKind.READ, it, y, listOf(privateForm))
            } +
            listOf(
                Req("job.operate", PermissionKind.WRITE, "integration.engineer", y, both),
                Req("job.operate", PermissionKind.WRITE, "deployment.operator", y, listOf(privateForm)),
                Req("job.operate-whole-job", PermissionKind.WRITE, "platform.engineer", y, listOf(saas)),
                Req("job.operate-whole-job", PermissionKind.WRITE, "deployment.operator", y, listOf(privateForm)),
            ) +
            Req("filing.confirm", PermissionKind.WRITE, "tax.approver", y, both) +
            listOf(
                Req("filing-frequency.change", PermissionKind.WRITE, "entity.admin", y, both),
                Req("period.confirm", PermissionKind.WRITE, "entity.admin", y, both),
                Req("period.confirm", PermissionKind.WRITE, "tax.approver", y, both),
                Req("submission.withdraw", PermissionKind.WRITE, "tax.approver", y, both),
                Req("accreditation.record", PermissionKind.WRITE, "release.manager", y, listOf(saas)),
                Req("accreditation.conclude", PermissionKind.WRITE, "release.manager", y, listOf(saas)),
                Req("tenant-schedule.approve", PermissionKind.WRITE, "commercial.admin", y, listOf(saas)),
                Req("vp-03.acknowledge", PermissionKind.WRITE, "tenant.admin", y, both),
                Req("vp-07.acknowledge", PermissionKind.WRITE, "tenant.admin", y, both),
                Req("vp-40.acknowledge", PermissionKind.WRITE, "tenant.admin", y, both),
                Req("vp-44.acknowledge", PermissionKind.WRITE, "tenant.admin", y, both),
                Req("vp-81.acknowledge", PermissionKind.WRITE, "tenant.admin", y, both),
                Req("vp-13.acknowledge", PermissionKind.WRITE, "client.signatory", ws, both),
                Req("vp-50.acknowledge", PermissionKind.WRITE, "tax.approver", y, both),
                Req("vp-61.acknowledge", PermissionKind.WRITE, "entity.admin", y, both),
                Req("vp-66.acknowledge", PermissionKind.WRITE, "security.officer", y, listOf(saas)),
                Req("vp-66.acknowledge", PermissionKind.WRITE, "tax.approver", y, listOf(privateForm)),
                Req("vp-59.acknowledge.tax-approver", PermissionKind.WRITE, "tax.approver", y, both),
                Req("vp-59.acknowledge.client-signatory", PermissionKind.WRITE, "client.signatory", ws, both),
                Req("vp-62.acknowledge.tax-approver", PermissionKind.WRITE, "tax.approver", y, both),
                Req("vp-62.acknowledge.client-signatory", PermissionKind.WRITE, "client.signatory", ws, both),
                Req("authority-incarnation.bump", PermissionKind.WRITE, "platform.engineer", y, listOf(saas)),
                Req("authority-incarnation.bump", PermissionKind.WRITE, "deployment.operator", y, listOf(privateForm)),
            ) +
            listOf(
                "filing.confirm",
                "filed-return.upload",
                "reconciliation.run",
                "reconciliation.annotate",
                "reconciliation.attest",
            ).map {
                Req(it, PermissionKind.WRITE, "agent.operator", RoleAllowance.WITHIN_SCOPE, both)
            }

    @Test
    fun `every required cell is served and every other cell of a required key is denied`() {
        val keys = required.map { it.key }.toSet()
        keys.forEach { key ->
            val reqs = required.filter { it.key == key }
            assertEquals(reqs.first().kind, RolePermissions.kind(key), key)
            both.forEach { form ->
                val expected = reqs.filter { form in it.forms }.associate { it.role to it.allowance }
                val actual =
                    RolePermissions.cells(form).mapNotNull { (role, perms) -> perms[key]?.let { role to it } }.toMap()
                assertEquals(expected, actual, "$key $form")
            }
        }
    }

    /** The `set_by` of VP-NN on main, grouped by equal holders (INV-T-10132, INV-Q-10127 A). */
    private val vpHolders: Map<Int, Set<String>> =
        mapOf(
            *vpGroup(
                setOf("tax.approver"),
                listOf(14, 15, 16, 18, 19, 20, 29, 31, 36, 37, 39, 40, 41, 42, 50, 54, 57, 59, 62, 65, 76),
            ),
            *vpGroup(setOf("tenant.admin"), listOf(1, 2, 3, 6, 7, 9, 17, 28, 32, 33, 43, 46, 58, 64, 68, 69, 73, 81)),
            *vpGroup(setOf("integration.engineer"), listOf(26, 34, 35, 38, 47, 48, 49, 55, 56, 67, 71)),
            *vpGroup(setOf("entity.admin"), listOf(21, 22, 23, 24, 25, 27, 51, 53, 60, 61)),
            *vpGroup(setOf("platform.engineer", "deployment.operator"), listOf(66, 70, 72, 74, 75, 77, 78, 79, 80)),
            *vpGroup(setOf("client.signatory"), listOf(4, 10, 11, 12, 13)),
            *vpGroup(setOf("security.officer"), listOf(5, 8, 44, 63)),
            *vpGroup(setOf("tax.operator"), listOf(30)),
            *vpGroup(setOf("commercial.admin"), listOf(45)),
            *vpGroup(setOf("deployment.operator"), listOf(52)),
        )

    private fun vpGroup(
        holders: Set<String>,
        numbers: List<Int>,
    ): Array<Pair<Int, Set<String>>> = numbers.map { it to holders }.toTypedArray()

    @Test
    fun `the 81 vp set keys are WRITE and held by the set_by roles of their variation point, per form`() {
        assertEquals((1..81).toSet(), vpHolders.keys)
        vpHolders.forEach { (n, holders) ->
            val key = "vp-%02d.set".format(n)
            assertEquals(PermissionKind.WRITE, RolePermissions.kind(key), key)
            both.forEach { form ->
                val existing = RolePermissions.roles(form)
                val actual =
                    RolePermissions
                        .cells(form)
                        .filter { (_, perms) -> perms[key] == y || perms[key] == RoleAllowance.WITHIN_SCOPE }
                        .keys
                assertEquals(holders.filter { it in existing }.toSet(), actual, "$key $form")
                holders.filter { it in existing }.forEach {
                    val expected = if (it == "client.signatory") RoleAllowance.WITHIN_SCOPE else y
                    assertEquals(expected, cell(form, it, key), "$key $it $form")
                }
            }
        }
    }

    @Test
    fun `vp keys outside 01 to 81 are absent`() {
        assertNull(RolePermissions.kind("vp-82.set"))
        assertNull(RolePermissions.kind("vp-0.set"))
        assertNull(RolePermissions.kind("vp-00.set"))
    }

    @Test
    fun `private form holds the deployment operator and none of the four SaaS-only roles`() {
        val roles = RolePermissions.roles(privateForm)
        assertTrue("deployment.operator" in roles)
        listOf("platform.support", "platform.engineer", "release.manager", "commercial.admin")
            .forEach { assertFalse(it in roles, it) }
    }

    @Test
    fun `SaaS form does not hold the deployment operator`() {
        assertFalse("deployment.operator" in RolePermissions.roles(saas))
        assertEquals(18, RolePermissions.roles(saas).size)
        assertEquals(15, RolePermissions.roles(privateForm).size)
    }

    @Test
    fun `cells are served for exactly the roles of the form`() {
        both.forEach { assertEquals(RolePermissions.roles(it), RolePermissions.cells(it).keys, it.name) }
    }

    @Test
    fun `a role or key that is not in the table gives no cell and no kind`() {
        assertNull(cell(saas, "tenant.admins", "user.manage"))
        assertNull(cell(saas, "tenant.admin", "User.manage"))
        assertNull(cell(saas, "tenant.admin", "user.manage2"))
        assertNull(cell(saas, "deployment.operator", "support-bundle.export"))
        assertNull(RolePermissions.kind("user.manage2"))
        assertNull(RolePermissions.kind("filing.confirms"))
        assertNull(RolePermissions.kind(""))
        assertNull(RolePermissions.cells(saas)["x"])
    }

    @Test
    fun `an auditor holds no cell on a WRITE key in either form`() {
        both.forEach { form ->
            RolePermissions
                .cells(form)
                .getValue("auditor")
                .keys
                .forEach { assertEquals(PermissionKind.READ, RolePermissions.kind(it), it) }
        }
    }

    @Test
    fun `whole-job run is held by the platform engineer in SaaS and not by the integration engineer`() {
        assertEquals(y, cell(saas, "platform.engineer", "job.operate-whole-job"))
        assertNull(cell(saas, "integration.engineer", "job.operate-whole-job"))
        assertNull(cell(saas, "platform.engineer", "job.operate"))
    }

    @Test
    fun `private form gives the client signatory only WITHIN_SCOPE cells`() {
        val cells = RolePermissions.cells(privateForm).getValue("client.signatory")
        assertTrue(cells.isNotEmpty())
        cells.values.forEach { assertEquals(RoleAllowance.WITHIN_SCOPE, it) }
    }
}
