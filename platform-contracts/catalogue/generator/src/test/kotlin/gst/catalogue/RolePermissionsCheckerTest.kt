package gst.catalogue

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/** INV-T-7987: each probe is added alone to the parsed real file, and the checker names the role, key and form. */
class RolePermissionsCheckerTest {
    private val real = RolePermissionYamlParser.parse(File("../role-permissions/role-permissions.yaml"))

    private fun permission(
        table: RolePermissionTable,
        key: String,
    ) = table.permissions.first { it.key == key }

    private fun withPermission(
        key: String,
        change: (PermissionRow) -> PermissionRow,
    ) = real.copy(permissions = real.permissions.map { if (it.key == key) change(it) else it })

    private fun withRole(
        key: String,
        change: (RoleRow) -> RoleRow,
    ) = real.copy(roles = real.roles.map { if (it.key == key) change(it) else it })

    private fun assertReported(
        table: RolePermissionTable,
        vararg parts: String,
    ) {
        val violations = RolePermissionsChecker.check(table)
        assertTrue(violations.any { v -> parts.all { it in v } }, "no violation names ${parts.toList()}: $violations")
    }

    @Test
    fun `the unmodified file reports no violation`() {
        assertEquals(emptyList<String>(), RolePermissionsChecker.check(real))
    }

    @Test
    fun `the file has the 19 roles and the 34 published rows`() {
        assertEquals(19, real.roles.size)
        assertEquals(
            (RolePermissionsChecker.publishedRows + RolePermissionsChecker.addedRows).toSet(),
            real.permissions.map { it.row }.toSet(),
        )
        assertEquals(34, RolePermissionsChecker.publishedRows.toSet().size)
        assertEquals(9, RolePermissionsChecker.addedRows.toSet().size)
        assertTrue(RolePermissionsChecker.addedRows.none { it in RolePermissionsChecker.publishedRows })
    }

    @Test
    fun `probe 1 a renamed role is reported`() {
        assertReported(withRole("tenant.admin") { it.copy(key = "tenant.admins") }, "role tenant.admins")
        assertReported(withRole("tenant.admin") { it.copy(key = "tenant.admins") }, "role tenant.admin: missing")
    }

    @Test
    fun `probe 2 a deleted role is reported`() {
        assertReported(real.copy(roles = real.roles.filter { it.key != "auditor" }), "role auditor: missing")
        assertReported(real.copy(roles = real.roles.filter { it.key != "auditor" }), "role auditor key doc.view.content form SAAS", "not in the file")
    }

    @Test
    fun `probe 3 deployment operator in SAAS is reported`() {
        assertReported(withRole("deployment.operator") { it.copy(forms = listOf(DeploymentForm.SAAS, DeploymentForm.PRIVATE)) }, "role deployment.operator: forms")
    }

    @Test
    fun `probe 4 release manager in PRIVATE is reported`() {
        assertReported(withRole("release.manager") { it.copy(forms = listOf(DeploymentForm.SAAS, DeploymentForm.PRIVATE)) }, "role release.manager: forms")
    }

    @Test
    fun `probe 5 the entity admin cell given to tenant admin is reported`() {
        val extra = permission(real, "entity-config.manage").cells.filter { it.role == "entity.admin" }.map { it.copy(role = "tenant.admin") }
        val table = withPermission("entity-config.manage") { it.copy(cells = it.cells + extra) }
        assertReported(table, "role tenant.admin key entity-config.manage form SAAS", "extra")
        assertReported(table, "role tenant.admin key entity-config.manage form PRIVATE", "extra")
    }

    @Test
    fun `probe 6 a removed required cell is reported`() {
        val table = withPermission("regulatory-pack.arm") { p -> p.copy(cells = p.cells.filter { it.role != "tax.approver" }) }
        assertReported(table, "role tax.approver key regulatory-pack.arm form PRIVATE", "missing")
    }

    @Test
    fun `probe 7 a deployment operator cell on arm is reported`() {
        val table = withPermission("regulatory-pack.arm") { it.copy(cells = it.cells + CellRow("deployment.operator", DeploymentForm.PRIVATE, RoleAllowance.PERMITTED)) }
        assertReported(table, "role deployment.operator key regulatory-pack.arm form PRIVATE", "extra")
    }

    @Test
    fun `probe 8 an auditor cell on user manage is reported`() {
        val table = withPermission("user.manage") { it.copy(cells = it.cells + CellRow("auditor", DeploymentForm.SAAS, RoleAllowance.PERMITTED)) }
        assertReported(table, "role auditor key user.manage form SAAS", "WRITE")
    }

    @Test
    fun `probe 9 a repeated key is reported`() {
        assertReported(real.copy(permissions = real.permissions + permission(real, "user.manage")), "key user.manage: repeated")
    }

    @Test
    fun `probe 10 a key with a capital letter is reported`() {
        assertReported(withPermission("user.manage") { it.copy(key = "User.manage") }, "key User.manage: does not match")
    }

    @Test
    fun `probe 11 a look-alike row label is reported`() {
        val table = withPermission("user.manage") { it.copy(row = it.row + "s") }
        assertReported(table, "key user.manage", "Manage users and roless", "neither one of the 34")
        assertReported(table, "row 'Manage users and roles': no key")
    }

    @Test
    fun `probe 12 a cell listed twice is reported`() {
        val twice = CellRow("tenant.admin", DeploymentForm.PRIVATE, RoleAllowance.PERMITTED)
        val table = withPermission("user.manage") { it.copy(cells = it.cells + twice) }
        assertReported(table, "role tenant.admin key user.manage form PRIVATE", "more than once")
    }

    @Test
    fun `a cell in a form the role does not have is reported`() {
        val table = withPermission("submission.view-status") { it.copy(cells = it.cells + CellRow("deployment.operator", DeploymentForm.SAAS, RoleAllowance.PERMITTED)) }
        assertReported(table, "role deployment.operator key submission.view-status form SAAS", "does not have that form")
    }

    @Test
    fun `a changed allowance on a required cell is reported`() {
        val table = withPermission("doc.view.content") { p -> p.copy(cells = p.cells.map { if (it.role == "agent.operator") it.copy(allowance = RoleAllowance.PERMITTED) else it }) }
        assertReported(table, "role agent.operator key doc.view.content form SAAS", "required WITHIN_SCOPE")
    }

    @Test
    fun `a removed required key is reported and look-alike keys do not satisfy it`() {
        assertReported(real.copy(permissions = real.permissions.filter { it.key != "user.manage" }), "key user.manage: missing")
        assertReported(withPermission("user.manage") { it.copy(key = "user.manage2") }, "key user.manage: missing")
    }

    @Test
    fun `a changed kind on a required key is reported`() {
        assertReported(withPermission("user.manage") { it.copy(kind = PermissionKind.READ) }, "key user.manage: kind READ")
    }

    @Test
    fun `a role listed twice and a role outside section 22 2 are reported`() {
        assertReported(real.copy(roles = real.roles + real.roles.first()), "role tenant.admin: listed more than once")
        assertReported(real.copy(roles = real.roles + RoleRow("x.client", "Customer", listOf(DeploymentForm.SAAS))), "role x.client: not one of the 19")
    }

    @Test
    fun `a form listed twice on a role is reported`() {
        assertReported(withRole("deployment.operator") { it.copy(forms = listOf(DeploymentForm.PRIVATE, DeploymentForm.PRIVATE)) }, "role deployment.operator: forms")
    }

    private fun cellAdd(
        key: String,
        role: String,
        form: DeploymentForm,
        allowance: RoleAllowance = RoleAllowance.PERMITTED,
    ) = withPermission(key) { it.copy(cells = it.cells + CellRow(role, form, allowance)) }

    @Test
    fun `probe 13 swapped retention rows are reported naming both keys`() {
        val a = permission(real, "retention.delete.request").row
        val b = permission(real, "retention.delete.approve").row
        val table =
            real.copy(
                permissions =
                    real.permissions.map {
                        when (it.key) {
                            "retention.delete.request" -> it.copy(row = b)
                            "retention.delete.approve" -> it.copy(row = a)
                            else -> it
                        }
                    },
            )
        assertReported(table, "key retention.delete.request", "differs from")
        assertReported(table, "key retention.delete.approve", "differs from")
    }

    @Test
    fun `probe 14 a client signatory PERMITTED cell on consent grant is reported`() {
        val table = withPermission("consent.grant") { p -> p.copy(cells = p.cells.map { if (it.form == DeploymentForm.SAAS) it.copy(allowance = RoleAllowance.PERMITTED) else it }) }
        assertReported(table, "role client.signatory key consent.grant form SAAS", "required WITHIN_SCOPE")
    }

    @Test
    fun `probe 15 a tenant admin cell on licence status in SAAS is reported`() {
        assertReported(cellAdd("licence-status.view", "tenant.admin", DeploymentForm.SAAS), "role tenant.admin key licence-status.view form SAAS", "extra")
    }

    @Test
    fun `probe 16 a removed deployment operator cell on break-glass unlock is reported`() {
        val table = withPermission("break-glass.unlock") { p -> p.copy(cells = p.cells.filter { it.role != "deployment.operator" }) }
        assertReported(table, "role deployment.operator key break-glass.unlock form PRIVATE", "missing")
    }

    @Test
    fun `probe 17 a WITHIN_SCOPE consultant cell on onboarding integration is reported`() {
        val table =
            withPermission("onboarding.integration.configure") { p ->
                p.copy(cells = p.cells.map { if (it.form == DeploymentForm.PRIVATE) it.copy(allowance = RoleAllowance.WITHIN_SCOPE) else it })
            }
        assertReported(table, "role implementation.consultant key onboarding.integration.configure form PRIVATE", "required PERMITTED")
    }

    @Test
    fun `probe 18 an integration engineer cell on the whole-job key is reported`() {
        assertReported(cellAdd("job.operate-whole-job", "integration.engineer", DeploymentForm.SAAS), "role integration.engineer key job.operate-whole-job form SAAS", "extra")
    }

    @Test
    fun `probe 19 a removed platform engineer cell on the whole-job key is reported`() {
        val table = withPermission("job.operate-whole-job") { p -> p.copy(cells = p.cells.filter { it.role != "platform.engineer" }) }
        assertReported(table, "role platform.engineer key job.operate-whole-job form SAAS", "missing")
    }

    @Test
    fun `probe 20 a platform engineer cell on job operate is reported`() {
        assertReported(cellAdd("job.operate", "platform.engineer", DeploymentForm.SAAS), "role platform.engineer key job.operate form SAAS", "extra")
    }

    @Test
    fun `probe 21 a changed row of filing confirm is reported`() {
        val label = permission(real, "filing.confirm").row
        assertReported(withPermission("filing.confirm") { it.copy(row = "$label ") }, "key filing.confirm", "neither one of the 34")
        assertReported(withPermission("filing.confirm") { it.copy(row = "Confirm filing") }, "key filing.confirm", "neither one of the 34")
    }

    @Test
    fun `probe 22 a removed agent operator PRIVATE cell of reconciliation attest is reported`() {
        val table =
            withPermission("reconciliation.attest") { p ->
                p.copy(cells = p.cells.filter { !(it.role == "agent.operator" && it.form == DeploymentForm.PRIVATE) })
            }
        assertReported(table, "role agent.operator key reconciliation.attest form PRIVATE", "missing")
    }

    @Test
    fun `probe 23 extra cells on the filing keys are reported`() {
        assertReported(cellAdd("filing.confirm", "tax.operator", DeploymentForm.SAAS), "role tax.operator key filing.confirm form SAAS", "extra")
        assertReported(
            cellAdd("filed-return.upload", "client.signatory", DeploymentForm.SAAS),
            "role client.signatory key filed-return.upload form SAAS",
            "extra",
        )
        assertReported(cellAdd("reconciliation.annotate", "auditor", DeploymentForm.SAAS), "key reconciliation.annotate", "auditor")
    }

    @Test
    fun `probe 24 look-alike keys of filing confirm are reported`() {
        listOf("filing.confirms", "Filing.confirm").forEach { name ->
            assertReported(withPermission("filing.confirm") { it.copy(key = name) }, "key filing.confirm: missing")
        }
    }

    @Test
    fun `probe 25 removed reconciliation and upload keys leave the shared row without a key`() {
        val gone = setOf("filed-return.upload", "reconciliation.run", "reconciliation.annotate", "reconciliation.attest")
        val table = real.copy(permissions = real.permissions.filter { it.key !in gone })
        assertReported(
            table,
            "row 'Record the filing fact (confirmFiling), upload the filed return, run and annotate reconciliation, attest as preparer'",
            "no key",
        )
    }

    private val vpLabel = "Set a variation point"

    private fun vpKeys() = real.permissions.filter { Regex("vp-\\d\\d\\.set").matches(it.key) }

    @Test
    fun `probe 26 the file has the 81 vp keys, each WRITE with the added row`() {
        assertEquals((1..81).map { "vp-%02d.set".format(it) }, vpKeys().map { it.key })
        assertTrue(vpKeys().all { it.kind == PermissionKind.WRITE && it.row == vpLabel })
    }

    @Test
    fun `probe 27 a tax approver cell added to vp-01 set is reported`() {
        assertReported(cellAdd("vp-01.set", "tax.approver", DeploymentForm.SAAS), "role tax.approver key vp-01.set form SAAS", "extra")
    }

    @Test
    fun `probe 28 a removed holder cell of vp-01 set is reported`() {
        val table = withPermission("vp-01.set") { p -> p.copy(cells = p.cells.filter { it.form != DeploymentForm.PRIVATE }) }
        assertReported(table, "role tenant.admin key vp-01.set form PRIVATE", "missing")
    }

    @Test
    fun `probe 29 vp-21 set given the holders of vp-01 set is reported, and the reverse`() {
        // vp-21 and vp-22 have the same holder (entity.admin), so the look-alike swap uses vp-01 (tenant.admin).
        val tenant = permission(real, "vp-01.set").cells
        val entity = permission(real, "vp-21.set").cells
        val a = withPermission("vp-21.set") { it.copy(cells = tenant) }
        assertReported(a, "role tenant.admin key vp-21.set", "extra")
        assertReported(a, "role entity.admin key vp-21.set", "missing")
        val b = withPermission("vp-01.set") { it.copy(cells = entity) }
        assertReported(b, "role entity.admin key vp-01.set", "extra")
        assertReported(b, "role tenant.admin key vp-01.set", "missing")
    }

    @Test
    fun `probe 30 look-alike vp keys are reported as missing`() {
        listOf("vp-01.set ", "vp-1.set", "VP-01.set", "vp-01.sets").forEach { name ->
            assertReported(withPermission("vp-01.set") { it.copy(key = name) }, "key vp-01.set: missing")
        }
    }

    @Test
    fun `probe 31 an auditor cell on vp-01 set is reported`() {
        assertReported(cellAdd("vp-01.set", "auditor", DeploymentForm.SAAS), "role auditor key vp-01.set form SAAS")
    }

    @Test
    fun `probe 32 a PRIVATE cell for platform engineer on vp-66 set is reported`() {
        assertReported(cellAdd("vp-66.set", "platform.engineer", DeploymentForm.PRIVATE), "role platform.engineer key vp-66.set form PRIVATE")
    }

    @Test
    fun `probe 33 a removed vp-81 set is reported`() {
        assertReported(real.copy(permissions = real.permissions.filter { it.key != "vp-81.set" }), "key vp-81.set: missing")
    }

    @Test
    fun `probe 34 look-alike labels on vp-01 set each fail`() {
        listOf("$vpLabel ", "Set a Variation Point", "Set variation point").forEach { label ->
            assertReported(withPermission("vp-01.set") { it.copy(row = label) }, "key vp-01.set", "neither one of the 34 published labels nor one of the added labels")
        }
    }

    @Test
    fun `probe 35 the label removed from addedRows while the keys keep it fails with the first message`() {
        val v = RolePermissionsChecker.check(real, added = emptyList())
        assertTrue(v.any { "key vp-01.set: row '$vpLabel' is neither one of the 34 published labels nor one of the added labels." == it }, "$v")
    }

    @Test
    fun `probe 36 the 81 keys removed while the label stays in addedRows fails with the second message`() {
        val table = real.copy(permissions = real.permissions - vpKeys().toSet())
        assertReported(table, "row '$vpLabel': no key transcribes it.")
    }

    private val opLabels =
        mapOf(
            "filing-frequency.change" to "Change the filing frequency of an entity",
            "period.confirm" to "Confirm filing periods of an entity",
            "submission.withdraw" to "Withdraw a submission",
            "accreditation.record" to "Record an accreditation",
            "accreditation.conclude" to "Conclude an accreditation task",
            "tenant-schedule.approve" to "Approve a tenant schedule",
        )

    @Test
    fun `the six operation keys carry their labels and addedRows holds its labels`() {
        opLabels.forEach { (k, label) -> assertEquals(label, permission(real, k).row, k) }
        assertEquals(
            setOf("Set a variation point", "Acknowledge a control relaxation", "Run the authority incarnation command") + opLabels.values,
            RolePermissionsChecker.addedRows.toSet(),
        )
    }

    @Test
    fun `probe 37 tenant admin added to period confirm is reported`() {
        assertReported(cellAdd("period.confirm", "tenant.admin", DeploymentForm.SAAS), "role tenant.admin key period.confirm form SAAS", "extra")
    }

    @Test
    fun `probe 38 tax approver added to filing-frequency change is reported`() {
        assertReported(cellAdd("filing-frequency.change", "tax.approver", DeploymentForm.PRIVATE), "role tax.approver key filing-frequency.change form PRIVATE", "extra")
    }

    @Test
    fun `probe 39 a PRIVATE cell for release manager on accreditation keys is reported`() {
        listOf("accreditation.record", "accreditation.conclude").forEach {
            assertReported(cellAdd(it, "release.manager", DeploymentForm.PRIVATE), "role release.manager key $it form PRIVATE")
        }
    }

    @Test
    fun `probe 40 commercial admin added to accreditation record is reported`() {
        assertReported(cellAdd("accreditation.record", "commercial.admin", DeploymentForm.SAAS), "role commercial.admin key accreditation.record form SAAS", "extra")
    }

    @Test
    fun `probe 41 renamed operation keys are reported missing`() {
        opLabels.keys.forEach { k ->
            listOf("$k ", "${k}s", k.replaceFirstChar { it.uppercase() }).forEach { name ->
                assertReported(withPermission(k) { it.copy(key = name) }, "key $k: missing")
            }
        }
    }

    @Test
    fun `probe 42 an auditor cell on each operation key is reported`() {
        opLabels.keys.forEach { assertReported(cellAdd(it, "auditor", DeploymentForm.SAAS), "role auditor key $it form SAAS") }
    }

    @Test
    fun `probe 43 each removed operation key is reported`() {
        opLabels.keys.forEach { k -> assertReported(real.copy(permissions = real.permissions.filter { it.key != k }), "key $k: missing") }
    }

    @Test
    fun `probe 44 look-alike labels on period confirm each fail`() {
        listOf("Confirm filing periods of an entity ", "Confirm filing period of an entity", "confirm filing periods of an entity").forEach { label ->
            assertReported(withPermission("period.confirm") { it.copy(row = label) }, "key period.confirm", "neither one of the 34 published labels nor one of the added labels")
        }
    }

    @Test
    fun `probe 45 each operation label removed from addedRows while its key stays fails`() {
        opLabels.forEach { (k, label) ->
            val v = RolePermissionsChecker.check(real, added = RolePermissionsChecker.addedRows - label)
            assertTrue(v.any { "key $k: row '$label' is neither one of the 34 published labels nor one of the added labels." == it }, "$v")
        }
    }

    private val ackLabel = "Acknowledge a control relaxation"
    private val bumpLabel = "Run the authority incarnation command"
    private val ackKeys =
        listOf("03", "07", "13", "40", "44", "50", "61", "66", "81").map { "vp-$it.acknowledge" } +
            listOf("59", "62").flatMap { n -> listOf("tax-approver", "client-signatory").map { "vp-$n.acknowledge.$it" } }

    private fun cellRemove(
        key: String,
        role: String,
        form: DeploymentForm,
    ) = withPermission(key) { p -> p.copy(cells = p.cells.filterNot { it.role == role && it.form == form }) }

    private fun assertFails(
        table: RolePermissionTable,
        key: String,
    ) {
        val v = RolePermissionsChecker.check(table)
        assertTrue(v.any { key in it }, "no violation names $key: $v")
    }

    @Test
    fun `the thirteen acknowledge keys and the bump key carry their labels and addedRows holds the nine labels`() {
        assertEquals(13, ackKeys.size)
        ackKeys.forEach { assertEquals(ackLabel, permission(real, it).row, it) }
        assertEquals(bumpLabel, permission(real, "authority-incarnation.bump").row)
        assertEquals(
            setOf(
                "Set a variation point",
                "Change the filing frequency of an entity",
                "Confirm filing periods of an entity",
                "Withdraw a submission",
                "Record an accreditation",
                "Conclude an accreditation task",
                "Approve a tenant schedule",
                ackLabel,
                bumpLabel,
            ),
            RolePermissionsChecker.addedRows.toSet(),
        )
        assertEquals(34, RolePermissionsChecker.publishedRows.toSet().size)
    }

    @Test
    fun `probe 46 tax approver PRIVATE cell removed from vp-66 acknowledge is reported`() {
        assertReported(cellRemove("vp-66.acknowledge", "tax.approver", DeploymentForm.PRIVATE), "role tax.approver key vp-66.acknowledge form PRIVATE")
    }

    @Test
    fun `probe 47 security officer PRIVATE cell added to vp-66 acknowledge is reported`() {
        assertReported(cellAdd("vp-66.acknowledge", "security.officer", DeploymentForm.PRIVATE), "role security.officer key vp-66.acknowledge form PRIVATE", "extra")
    }

    @Test
    fun `probe 48 tax approver added to the client signatory acknowledge of VP-59 and VP-62 is reported`() {
        listOf("vp-59", "vp-62").forEach { vp ->
            DeploymentForm.entries.forEach {
                assertReported(cellAdd("$vp.acknowledge.client-signatory", "tax.approver", it), "role tax.approver key $vp.acknowledge.client-signatory form $it", "extra")
            }
        }
    }

    @Test
    fun `probe 49 client signatory added to the tax approver acknowledge of VP-59 and VP-62 is reported`() {
        listOf("vp-59", "vp-62").forEach { vp ->
            DeploymentForm.entries.forEach {
                assertReported(
                    cellAdd("$vp.acknowledge.tax-approver", "client.signatory", it, RoleAllowance.WITHIN_SCOPE),
                    "role client.signatory key $vp.acknowledge.tax-approver form $it",
                    "extra",
                )
            }
        }
    }

    @Test
    fun `probe 50 an extra or look-alike acknowledge key is reported`() {
        listOf("vp-59.acknowledge", "vp-60.acknowledge", "vp-61.acknowledge.entity-admin", "authority-incarnation.bumps").forEach { name ->
            val extra = permission(real, "vp-61.acknowledge").copy(key = name)
            assertFails(real.copy(permissions = real.permissions + extra), name)
            assertFails(real.copy(permissions = real.permissions + extra.copy(row = "Withdraw a submission")), name)
        }
    }

    @Test
    fun `probe 51 renamed acknowledge and bump keys are reported missing`() {
        val names = ackKeys + "authority-incarnation.bump"
        names.forEach { k ->
            listOf("$k ", "${k}s").forEach { name ->
                assertReported(withPermission(k) { it.copy(key = name) }, "key $k: missing")
            }
        }
        listOf("vp-59.acknowledge.tax-approver " to "vp-59.acknowledge.tax-approver", "vp-59.acknowledge.tax_approver" to "vp-59.acknowledge.tax-approver").forEach { (name, k) ->
            assertReported(withPermission(k) { it.copy(key = name) }, "key $k: missing")
        }
    }

    @Test
    fun `probe 52 a SAAS cell for deployment operator or a PRIVATE cell for platform engineer on the bump key is reported`() {
        assertReported(cellAdd("authority-incarnation.bump", "deployment.operator", DeploymentForm.SAAS), "authority-incarnation.bump")
        assertReported(cellAdd("authority-incarnation.bump", "platform.engineer", DeploymentForm.PRIVATE), "authority-incarnation.bump")
    }

    @Test
    fun `probe 53 look-alike labels on an acknowledge key each fail`() {
        listOf("$ackLabel ", "acknowledge a control relaxation").forEach { label ->
            assertReported(withPermission("vp-03.acknowledge") { it.copy(row = label) }, "key vp-03.acknowledge", "neither one of the 34 published labels nor one of the added labels")
        }
    }

    @Test
    fun `probe 54 each new label removed from addedRows while its keys stay fails`() {
        listOf(ackLabel, bumpLabel).forEach { label ->
            val v = RolePermissionsChecker.check(real, added = RolePermissionsChecker.addedRows - label)
            assertTrue(v.any { "row '$label' is neither one of the 34 published labels nor one of the added labels." in it }, "$v")
        }
    }
}
