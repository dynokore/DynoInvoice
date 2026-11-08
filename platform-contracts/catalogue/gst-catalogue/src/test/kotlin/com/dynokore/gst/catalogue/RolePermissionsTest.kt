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
            listOf("elevation.decide", "access-review.run", "privacy-request.handle", "security-case.record").map {
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
                Req("election-change.approve", PermissionKind.WRITE, "tax.approver", y, both),
                Req("regulatory-pack.author", PermissionKind.WRITE, "regulatory.author", y, both),
                Req("regulatory-pack.approve", PermissionKind.WRITE, "regulatory.approver", y, both),
                Req("enquiry-lookup.by-acknowledgement", PermissionKind.READ, "auditor", y, both),
                Req("enquiry-lookup.by-acknowledgement", PermissionKind.READ, "tax.approver", y, both),
                Req("enquiry-lookup.by-acknowledgement", PermissionKind.READ, "tax.operator", y, both),
                Req("enquiry-lookup.reverse", PermissionKind.READ, "auditor", y, both),
                Req("enquiry-lookup.reverse", PermissionKind.READ, "tax.approver", y, both),
                Req("rendition-sample.record", PermissionKind.WRITE, "tax.operator", y, both),
                Req("audit-entry.purge", PermissionKind.WRITE, "platform.engineer", y, listOf(saas)),
            ) +
            listOf(Req("filed-return.upload", PermissionKind.WRITE, "tax.approver", y, both)) +
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
    fun `filed-return upload is PERMITTED to tax approver only and WITHIN_SCOPE to agent operator only`() {
        val uploadKey = "filed-return.upload"
        both.forEach { form ->
            val cells = RolePermissions.cells(form)

            fun holders(a: RoleAllowance) = cells.filter { (_, perms) -> perms[uploadKey] == a }.keys
            assertEquals(setOf("tax.approver"), holders(y), "PERMITTED $form")
            assertEquals(setOf("agent.operator"), holders(ws), "WITHIN_SCOPE $form")
        }
        assertEquals(PermissionKind.WRITE, RolePermissions.kind(uploadKey))
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
            *vpGroup(
                setOf("tenant.admin"),
                listOf(1, 2, 3, 6, 7, 9, 17, 28, 32, 33, 43, 46, 58, 64, 68, 69, 73, 81, 82),
            ),
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
    fun `the 82 vp set keys are WRITE and held by the set_by roles of their variation point, per form`() {
        assertEquals((1..82).toSet(), vpHolders.keys)
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
    fun `election-change approve is WRITE and held by tax approver only in both forms`() {
        assertEquals(PermissionKind.WRITE, RolePermissions.kind("election-change.approve"))
        both.forEach { form ->
            val holders =
                RolePermissions
                    .cells(form)
                    .filter { (_, perms) -> perms["election-change.approve"] == y }
                    .keys
            assertEquals(setOf("tax.approver"), holders, "$form")
        }
        assertNull(RolePermissions.kind("election-change.manage"))
        assertNull(RolePermissions.kind("election-change.approve "))
        assertNull(RolePermissions.kind("entity-config.approve"))
    }

    @Test
    fun `election-change withdraw and list have their kinds and the holders of entity-config confirm in both forms`() {
        assertEquals(PermissionKind.WRITE, RolePermissions.kind("election-change.withdraw"))
        assertEquals(PermissionKind.READ, RolePermissions.kind("election-change.list"))
        both.forEach { form ->
            listOf("election-change.withdraw", "election-change.list").forEach { key ->
                val holders = RolePermissions.cells(form).filter { (_, perms) -> perms[key] == y }.keys
                assertEquals(setOf("entity.admin", "tenant.admin", "tax.approver"), holders, "$key $form")
            }
        }
        assertNull(RolePermissions.kind("election-change.decline"))
    }

    @Test
    fun `the enquiry lookups and the rendition sample check have their kinds and exact holders in both forms`() {
        val expected =
            mapOf(
                "enquiry-lookup.by-acknowledgement" to
                    Pair(PermissionKind.READ, setOf("auditor", "tax.approver", "tax.operator")),
                "enquiry-lookup.reverse" to Pair(PermissionKind.READ, setOf("auditor", "tax.approver")),
                "rendition-sample.record" to Pair(PermissionKind.WRITE, setOf("tax.operator")),
            )
        expected.forEach { (key, kindAndRoles) ->
            assertEquals(kindAndRoles.first, RolePermissions.kind(key), key)
            both.forEach { form ->
                val holders =
                    RolePermissions
                        .cells(form)
                        .filter { (_, perms) -> perms[key] == RoleAllowance.PERMITTED }
                        .keys
                assertEquals(kindAndRoles.second, holders, "$key $form")
            }
        }
        listOf(
            "enquiry-lookup.by-acknowledgement ",
            "enquiry-lookup.run",
            "enquiry-lookup.view",
            "Enquiry-lookup.reverse",
            "rendition-sample.upload",
            "Rendition-sample.record",
        ).forEach { assertNull(RolePermissions.kind(it), it) }
    }

    @Test
    fun `the two sample copy keys have their kinds and exact holders in both forms and a third key is absent`() {
        val expected = mapOf("sample-copy.request" to "tenant.admin", "sample-copy.approve" to "security.officer")
        expected.forEach { (key, role) ->
            assertEquals(PermissionKind.WRITE, RolePermissions.kind(key), key)
            both.forEach { form ->
                val holders = RolePermissions.cells(form).filter { (_, perms) -> perms[key] != null }.keys
                assertEquals(setOf(role), holders, "$key $form")
                assertEquals(RoleAllowance.PERMITTED, RolePermissions.cells(form).getValue(role)[key], "$key $form")
            }
        }
        assertNull(RolePermissions.kind("sample-copy.cancel"))
        assertNull(RolePermissions.kind("sample-copy.requests"))
    }

    @Test
    fun `the compliance metrics key is READ with exactly the four cells and an export key is absent`() {
        assertEquals(PermissionKind.READ, RolePermissions.kind("compliance-metrics.view"))
        both.forEach { form ->
            val holders = RolePermissions.cells(form).filter { (_, perms) -> perms["compliance-metrics.view"] != null }
            assertEquals(setOf("tax.approver", "finance.viewer"), holders.keys, "$form")
            holders.values.forEach { assertEquals(RoleAllowance.PERMITTED, it["compliance-metrics.view"], "$form") }
        }
        assertNull(RolePermissions.kind("compliance-metrics.export"))
    }

    @Test
    fun `the backfill approval key is WRITE with exactly the two tax approver cells`() {
        assertEquals(PermissionKind.WRITE, RolePermissions.kind("backfill.approve"))
        both.forEach { form ->
            val holders = RolePermissions.cells(form).filter { (_, perms) -> perms["backfill.approve"] != null }
            assertEquals(setOf("tax.approver"), holders.keys, "$form")
            holders.values.forEach { assertEquals(RoleAllowance.PERMITTED, it["backfill.approve"], "$form") }
        }
        assertNull(RolePermissions.kind("backfill.run"))
    }

    @Test
    fun `the exception deferral key is WRITE with exactly the tax operator and agent operator cells`() {
        assertEquals(PermissionKind.WRITE, RolePermissions.kind("exception.defer"))
        both.forEach { form ->
            val holders = RolePermissions.cells(form).filter { (_, perms) -> perms["exception.defer"] != null }
            assertEquals(
                mapOf(
                    "tax.operator" to RoleAllowance.PERMITTED,
                    "agent.operator" to RoleAllowance.WITHIN_SCOPE,
                ),
                holders.mapValues {
                    it.value["exception.defer"]
                },
                "$form",
            )
        }
        assertNull(RolePermissions.kind("exception.undefer"))
    }

    @Test
    fun `INV-AC-1930 an operator elevation waits for security officer approval through the catalogue cells`() {
        assertEquals(y, cell(saas, "platform.support", "elevation.request"))
        assertEquals(y, cell(saas, "security.officer", "elevation.decide"))
        assertNull(cell(saas, "platform.support", "elevation.decide"))
    }

    @Test
    fun `the elevation request and diagnostic invite keys are WRITE with exactly one cell each`() {
        val holders =
            mapOf(
                "elevation.request" to ("platform.support" to saas),
                "diagnostic.invite" to ("deployment.operator" to privateForm),
            )
        holders
            .forEach { (key, holder) ->
                assertEquals(PermissionKind.WRITE, RolePermissions.kind(key), key)
                both.forEach { form ->
                    val actual =
                        RolePermissions.cells(form).mapNotNull { (role, perms) -> perms[key]?.let { role to it } }
                    val expected = if (form == holder.second) listOf(holder.first to y) else emptyList()
                    assertEquals(expected, actual, "$key $form")
                }
            }
        assertNull(RolePermissions.kind("elevation.requests"))
        assertNull(RolePermissions.kind("diagnostic.invitation"))
    }

    @Test
    fun `INV-AC-16572 exception view is READ and held exactly as masterdata-exception resolve`() {
        val key = "exception.view"
        assertEquals(PermissionKind.READ, RolePermissions.kind(key))
        both.forEach { form ->
            val cells = RolePermissions.cells(form)

            fun held(k: String) = cells.mapNotNull { (role, perms) -> perms[k]?.let { Triple(role, form, it) } }.toSet()

            assertEquals(held("masterdata-exception.resolve"), held(key), "$key $form")
            assertEquals(4, held(key).size, "$key $form")
        }
        assertNull(RolePermissions.kind("exception.views"))
    }

    @Test
    fun `the operator session key is WRITE with exactly three cells and every other role is DENIED`() {
        val key = "operator-session.open"
        assertEquals(PermissionKind.WRITE, RolePermissions.kind(key))
        val holders =
            mapOf(
                saas to setOf("platform.support", "platform.engineer"),
                privateForm to setOf("deployment.operator"),
            )
        both.forEach { form ->
            val actual =
                RolePermissions.cells(form).mapNotNull { (role, perms) -> perms[key]?.let { role to it } }.toMap()
            assertEquals(holders.getValue(form).associateWith { y }, actual, "$key $form")
        }
        assertNull(RolePermissions.kind("operator-session.opens"))
    }

    @Test
    fun `the held document release key is WRITE with two tax approver cells, others DENIED`() {
        val key = "held-document.release"
        assertEquals(PermissionKind.WRITE, RolePermissions.kind(key))
        both.forEach { form ->
            val actual =
                RolePermissions.cells(form).mapNotNull { (role, perms) -> perms[key]?.let { role to it } }.toMap()
            assertEquals(mapOf("tax.approver" to y), actual, "$key $form")
        }
    }

    @Test
    fun `period-extension record is WRITE with two tax approver cells, others DENIED`() {
        val key = "period-extension.record"
        assertEquals(PermissionKind.WRITE, RolePermissions.kind(key))
        both.forEach { form ->
            val actual =
                RolePermissions.cells(form).mapNotNull { (role, perms) -> perms[key]?.let { role to it } }.toMap()
            assertEquals(mapOf("tax.approver" to y), actual, "$key $form")
        }
    }

    @Test
    fun `INV-AC-16682 period-extension record is tax approver only in both forms`() {
        assertEquals(y, cell(saas, "tax.approver", "period-extension.record"))
        assertEquals(y, cell(privateForm, "tax.approver", "period-extension.record"))
        // DENIED is the absence of a cell (role-permissions.yaml header: fail closed).
        assertNull(cell(saas, "entity.admin", "period-extension.record"))
        assertNull(RolePermissions.kind("period-extensions.record"))
    }

    @Test
    fun `INV-AC-18096 questionnaire answer is WRITE and held by tenant admin alone in both forms`() {
        assertEquals(PermissionKind.WRITE, RolePermissions.kind("questionnaire.answer"))
        val actual =
            both
                .flatMap { form ->
                    RolePermissions.cells(form).mapNotNull { (role, perms) ->
                        perms["questionnaire.answer"]?.let { Triple(role, form, it) }
                    }
                }.toSet()
        assertEquals(both.map { Triple("tenant.admin", it, y) }.toSet(), actual)
        assertNull(RolePermissions.kind("questionnaire.answers"))
        assertNull(RolePermissions.kind("questionnaire.answer-"))
    }

    @Test
    fun `INV-AC-17221 gap report and counterparty import keys carry exactly the INV-NOTE-7 cells`() {
        val expected =
            mapOf(
                "gap-report.view" to (PermissionKind.READ to setOf("tax.operator", "tax.approver")),
                "gap-report.export" to (PermissionKind.READ to setOf("tax.operator")),
                "counterparty-import.run" to
                    (PermissionKind.WRITE to setOf("tax.operator", "implementation.consultant")),
            )
        expected.forEach { (key, kindAndRoles) ->
            assertEquals(kindAndRoles.first, RolePermissions.kind(key), key)
            val actual =
                both
                    .flatMap { form ->
                        RolePermissions.cells(form).mapNotNull { (role, perms) ->
                            perms[key]?.let { Triple(role, form, it) }
                        }
                    }.toSet()
            val roles = kindAndRoles.second
            assertEquals(roles.flatMap { r -> both.map { Triple(r, it, y) } }.toSet(), actual, key)
        }
        assertNull(RolePermissions.kind("gap-report.views"))
        assertNull(RolePermissions.kind("counterparty-import.runs"))
    }

    @Test
    fun `INV-T-15460 accreditation read keys are READ and held by release manager in SAAS only`() {
        listOf("accreditation.view", "accreditation-task.list", "accreditation-task.view").forEach { key ->
            assertEquals(PermissionKind.READ, RolePermissions.kind(key), key)
            val saasHolders =
                RolePermissions.cells(saas).mapNotNull { (role, perms) -> perms[key]?.let { role to it } }.toMap()
            assertEquals(mapOf("release.manager" to y), saasHolders, key)
            val privateHolders =
                RolePermissions
                    .cells(privateForm)
                    .mapNotNull { (role, perms) -> perms[key]?.let { role to it } }
                    .toMap()
            assertEquals(emptyMap<String, Any>(), privateHolders, key)
        }
        assertNull(RolePermissions.kind("accreditation-task.get"))
        assertEquals(PermissionKind.WRITE, RolePermissions.kind("accreditation.record"))
        assertEquals(PermissionKind.WRITE, RolePermissions.kind("accreditation.conclude"))
    }

    @Test
    fun `INV-T-16220 tenant-invitation reissue is WRITE held by commercial admin in SAAS only`() {
        val key = "tenant-invitation.reissue"
        assertEquals(PermissionKind.WRITE, RolePermissions.kind(key))
        val saasHolders =
            RolePermissions.cells(saas).mapNotNull { (role, perms) -> perms[key]?.let { role to it } }.toMap()
        assertEquals(mapOf("commercial.admin" to y), saasHolders)
        val privateHolders =
            RolePermissions
                .cells(privateForm)
                .mapNotNull { (role, perms) -> perms[key]?.let { role to it } }
                .toMap()
        assertEquals(emptyMap<String, Any>(), privateHolders)
        assertNull(RolePermissions.kind("tenant-invitation.reissued"))
    }

    @Test
    fun `INV-T-16273 the two commercial read keys are READ held by commercial admin in SAAS only`() {
        listOf("licence.view", "tenant-provisioning-request.view").forEach { key ->
            assertEquals(PermissionKind.READ, RolePermissions.kind(key))
            val saasHolders =
                RolePermissions.cells(saas).mapNotNull { (role, perms) -> perms[key]?.let { role to it } }.toMap()
            assertEquals(mapOf("commercial.admin" to y), saasHolders, key)
            val privateHolders =
                RolePermissions
                    .cells(privateForm)
                    .mapNotNull { (role, perms) -> perms[key]?.let { role to it } }
                    .toMap()
            assertEquals(emptyMap<String, Any>(), privateHolders, key)
        }
        assertNull(RolePermissions.kind("licence.views"))
    }

    @Test
    fun `calendar-pack-delta view is READ with two tax approver cells, others DENIED`() {
        val key = "calendar-pack-delta.view"
        assertEquals(PermissionKind.READ, RolePermissions.kind(key))
        both.forEach { form ->
            val actual =
                RolePermissions.cells(form).mapNotNull { (role, perms) -> perms[key]?.let { role to it } }.toMap()
            assertEquals(mapOf("tax.approver" to y), actual, "$key $form")
        }
    }

    @Test
    fun `INV-AC-16821 calendar-pack-delta view is tax approver only in both forms`() {
        assertEquals(y, cell(saas, "tax.approver", "calendar-pack-delta.view"))
        assertEquals(y, cell(privateForm, "tax.approver", "calendar-pack-delta.view"))
        // DENIED is the absence of a cell (role-permissions.yaml header: fail closed).
        assertNull(cell(saas, "tenant.admin", "calendar-pack-delta.view"))
        assertNull(RolePermissions.kind("calendar-pack-deltas.view"))
    }

    @Test
    fun `INV-AC-16537 held-document release is tax approver only`() {
        assertEquals(y, cell(saas, "tax.approver", "held-document.release"))
        // DENIED is the absence of a cell (role-permissions.yaml header: fail closed).
        assertNull(cell(saas, "tax.operator", "held-document.release"))
        assertNull(RolePermissions.kind("held-document.releases"))
    }

    @Test
    fun `the consultant grant request key is WRITE with exactly two cells and every other role is DENIED`() {
        val key = "consultant-grant.request"
        assertEquals(PermissionKind.WRITE, RolePermissions.kind(key))
        val holders =
            mapOf(
                saas to setOf("platform.support"),
                privateForm to setOf("deployment.operator"),
            )
        both.forEach { form ->
            val actual =
                RolePermissions.cells(form).mapNotNull { (role, perms) -> perms[key]?.let { role to it } }.toMap()
            assertEquals(holders.getValue(form).associateWith { y }, actual, "$key $form")
            listOf("platform.engineer", "tenant.admin", "security.officer").forEach {
                assertNull(cell(form, it, key), "$it $form")
            }
        }
        assertEquals(y, cell(saas, "tenant.admin", "user.manage"))
        assertEquals(y, cell(privateForm, "tenant.admin", "user.manage"))
        assertNull(cell(saas, "platform.support", "user.manage"))
        assertNull(cell(privateForm, "deployment.operator", "user.manage"))
        assertNull(RolePermissions.kind("consultant-grant.requests"))
    }

    @Test
    fun `INV-AC-922 platform support opens a session but sees no invoice content`() {
        assertEquals(y, cell(saas, "platform.support", "operator-session.open"))
        // DENIED is the absence of a cell (role-permissions.yaml header: fail closed).
        assertNull(cell(saas, "platform.support", "doc.view.content"))
    }

    @Test
    fun `the break-glass reseal key is WRITE with exactly the deployment operator PRIVATE cell`() {
        assertEquals(PermissionKind.WRITE, RolePermissions.kind("break-glass.reseal"))
        assertEquals(
            mapOf("deployment.operator" to RoleAllowance.PERMITTED),
            RolePermissions
                .cells(privateForm)
                .filter { (_, perms) ->
                    perms["break-glass.reseal"] != null
                }.mapValues { it.value["break-glass.reseal"] },
        )
        assertTrue(RolePermissions.cells(saas).none { (_, perms) -> perms["break-glass.reseal"] != null })
        assertNull(RolePermissions.kind("break-glass.sign-in"))
    }

    @Test
    fun `vp keys outside 01 to 82 are absent`() {
        assertNull(RolePermissions.kind("vp-83.set"))
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

    @Test
    fun `INV-AC-18809 migration-cutover keys have their kinds and holders in both forms`() {
        assertEquals(PermissionKind.WRITE, RolePermissions.kind("migration-cutover.record"))
        assertEquals(PermissionKind.READ, RolePermissions.kind("migration-cutover.view"))
        val view = setOf("entity.admin", "tenant.admin", "tax.approver")
        both.forEach { f ->
            val h = { k: String -> RolePermissions.cells(f).filter { (_, p) -> p[k] == y }.keys }
            assertEquals(setOf("entity.admin"), h("migration-cutover.record"))
            assertEquals(view, h("migration-cutover.view"))
        }
    }
}
