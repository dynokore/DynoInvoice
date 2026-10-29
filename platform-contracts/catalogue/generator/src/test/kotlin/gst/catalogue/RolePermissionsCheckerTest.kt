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
        assertEquals(18, RolePermissionsChecker.addedRows.toSet().size)
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
    fun `probe 26 the file has the 82 vp keys, each WRITE with the added row`() {
        assertEquals((1..82).map { "vp-%02d.set".format(it) }, vpKeys().map { it.key })
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
    fun `probe 82 a removed vp-82 set is reported`() {
        assertReported(real.copy(permissions = real.permissions.filter { it.key != "vp-82.set" }), "key vp-82.set: missing")
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
            setOf("Set a variation point", "Acknowledge a control relaxation", "Run the authority incarnation command", "Author a regulatory pack", "Approve a regulatory pack") + opLabels.values + lookupLabels + "Purge audit entries" + sampleCopyLabels + "View coverage, deadline and correction metrics per entity and period",
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
    private val sampleCopyLabels = setOf("Request an anonymised production sample copy", "Approve an anonymised production sample copy")
    private val lookupLabels = setOf("Look up an enquiry by Acknowledgement ID", "Reverse-look up an enquiry", "Record a rendition sample check")

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
    fun `the thirteen acknowledge keys and the bump key carry their labels and addedRows holds the twelve labels`() {
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
                "Author a regulatory pack",
                "Approve a regulatory pack",
            ) + lookupLabels + "Purge audit entries" + sampleCopyLabels + "View coverage, deadline and correction metrics per entity and period",
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

    private val eca = "election-change.approve"

    @Test
    fun `election-change approve carries the entity-config manage row and its two cells`() {
        assertEquals(permission(real, "entity-config.manage").row, permission(real, eca).row)
        assertEquals(PermissionKind.WRITE, permission(real, eca).kind)
        assertEquals(
            setOf(Triple("tax.approver", DeploymentForm.SAAS, RoleAllowance.PERMITTED), Triple("tax.approver", DeploymentForm.PRIVATE, RoleAllowance.PERMITTED)),
            permission(real, eca).cells.map { Triple(it.role, it.form, it.allowance) }.toSet(),
        )
        assertEquals(34, RolePermissionsChecker.publishedRows.toSet().size)
    }

    @Test
    fun `probe election-change approve cells and rows changed alone are each reported`() {
        listOf("entity.admin", "tenant.admin").forEach { r ->
            assertReported(cellAdd(eca, r, DeploymentForm.SAAS), "role $r key $eca form SAAS", "extra")
        }
        assertReported(cellRemove(eca, "tax.approver", DeploymentForm.PRIVATE), "role tax.approver key $eca form PRIVATE")
        assertReported(withPermission(eca) { p -> p.copy(cells = p.cells.map { if (it.form == DeploymentForm.SAAS) it.copy(allowance = RoleAllowance.WITHIN_SCOPE) else it }) }, eca)
        assertReported(cellAdd(eca, "auditor", DeploymentForm.SAAS), "key $eca")
        assertReported(withPermission(eca) { it.copy(row = it.row + " ") }, "key $eca")
        assertReported(withPermission(eca) { it.copy(row = "Administer override policy") }, "key $eca")
    }

    @Test
    fun `probe election-change approve renamed or removed is reported missing`() {
        listOf("$eca ", "election-change.approver", "election-changes.approve", "Election-change.approve").forEach { name ->
            assertReported(withPermission(eca) { it.copy(key = name) }, "key $eca: missing")
        }
        assertReported(real.copy(permissions = real.permissions.filterNot { it.key == eca }), "key $eca: missing")
    }

    @Test
    fun `probe 50 the approve row changed to a look-alike is reported and the true label has no key`() {
        val t = withPermission("regulatory-pack.approve") { it.copy(row = "Approve a regulatory packs") }
        assertReported(t, "key regulatory-pack.approve", "Approve a regulatory packs", "neither one of the 34")
        assertReported(t, "row 'Approve a regulatory pack'", "no key")
    }

    @Test
    fun `probe 51 the author row changed to a look-alike is reported and the true label has no key`() {
        val t = withPermission("regulatory-pack.author") { it.copy(row = "Author regulatory pack") }
        assertReported(t, "key regulatory-pack.author", "Author regulatory pack", "neither one of the 34")
        assertReported(t, "row 'Author a regulatory pack'", "no key")
    }

    @Test
    fun `probe 52 the approve label with a trailing space is reported`() {
        assertReported(withPermission("regulatory-pack.approve") { it.copy(row = "Approve a regulatory pack ") }, "key regulatory-pack.approve", "neither one of the 34")
    }

    @Test
    fun `probe 53 a regulatory author cell added to approve is reported`() {
        assertReported(cellAdd("regulatory-pack.approve", "regulatory.author", DeploymentForm.SAAS), "role regulatory.author key regulatory-pack.approve form SAAS", "extra")
    }

    @Test
    fun `probe 54 the PRIVATE cell of the regulatory approver removed from approve is reported`() {
        assertReported(cellRemove("regulatory-pack.approve", "regulatory.approver", DeploymentForm.PRIVATE), "role regulatory.approver key regulatory-pack.approve form PRIVATE", "missing")
    }

    @Test
    fun `probe 55 the author key removed is reported as a missing key and as a label with no key`() {
        val t = real.copy(permissions = real.permissions.filterNot { it.key == "regulatory-pack.author" })
        assertReported(t, "key regulatory-pack.author: missing")
        assertReported(t, "row 'Author a regulatory pack'", "no key")
    }

    @Test
    fun `probe 56 the author key spelled authorize is reported`() {
        assertReported(withPermission("regulatory-pack.author") { it.copy(key = "regulatory-pack.authorize") }, "key regulatory-pack.author: missing")
    }

    @Test
    fun `probe 57 the kind of approve set to READ is reported`() {
        assertReported(withPermission("regulatory-pack.approve") { it.copy(kind = PermissionKind.READ) }, "key regulatory-pack.approve: kind READ")
    }

    private val byAck = "enquiry-lookup.by-acknowledgement"
    private val reverse = "enquiry-lookup.reverse"
    private val sample = "rendition-sample.record"
    private val saasF = DeploymentForm.SAAS
    private val privF = DeploymentForm.PRIVATE

    private fun withRow(
        key: String,
        row: String,
    ) = withPermission(key) { it.copy(row = row) }

    private fun withKind(
        key: String,
        kind: PermissionKind,
    ) = withPermission(key) { it.copy(kind = kind) }

    private fun allowWithinScope(key: String) =
        withPermission(key) { p -> p.copy(cells = p.cells.map { if (it.form == saasF) it.copy(allowance = RoleAllowance.WITHIN_SCOPE) else it }) }

    private fun duplicateOnRow(
        key: String,
        newKey: String,
    ) = real.copy(permissions = real.permissions + permission(real, key).copy(key = newKey))

    private fun swapRows(
        a: String,
        b: String,
    ): RolePermissionTable {
        val ra = permission(real, a).row
        val rb = permission(real, b).row
        return real.copy(permissions = real.permissions.map { if (it.key == a) it.copy(row = rb) else if (it.key == b) it.copy(row = ra) else it })
    }

    @Test
    fun `the three lookup and sample keys carry their pinned pairs, kinds and cells`() {
        assertEquals(RolePermissionsChecker.lookupSamplePairs.keys, setOf(byAck, reverse, sample))
        RolePermissionsChecker.lookupSamplePairs.forEach { (k, row) -> assertEquals(row, permission(real, k).row, k) }
        assertEquals(PermissionKind.READ, permission(real, byAck).kind)
        assertEquals(PermissionKind.READ, permission(real, reverse).kind)
        assertEquals(PermissionKind.WRITE, permission(real, sample).kind)
        assertEquals(6, permission(real, byAck).cells.size)
        assertEquals(4, permission(real, reverse).cells.size)
        assertEquals(2, permission(real, sample).cells.size)
    }

    @Test
    fun `probe 58 by-acknowledgement cells and fields changed alone are each reported`() {
        listOf("tenant.admin", "finance.viewer", "security.officer", "platform.engineer").forEach { r ->
            assertReported(cellAdd(byAck, r, saasF), "role $r key $byAck form SAAS")
        }
        assertReported(cellAdd(byAck, "agent.operator", saasF, RoleAllowance.WITHIN_SCOPE), "key $byAck")
        listOf(saasF, privF).forEach { assertReported(cellRemove(byAck, "tax.operator", it), "role tax.operator key $byAck form $it") }
        assertReported(cellRemove(byAck, "auditor", saasF), "role auditor key $byAck form SAAS")
        assertReported(cellRemove(byAck, "tax.approver", privF), "role tax.approver key $byAck form PRIVATE")
        assertReported(allowWithinScope(byAck), byAck)
        assertReported(withKind(byAck, PermissionKind.WRITE), byAck)
        listOf("Export audit pack", "View audit log", "Reverse-look up an enquiry", "Look up an enquiry by Acknowledgement ID ").forEach {
            assertReported(withRow(byAck, it), "key $byAck")
        }
        assertReported(duplicateOnRow(byAck, "$byAck" + "2"), "enquiry-lookup.by-acknowledgement2")
    }

    @Test
    fun `probe 59 by-acknowledgement renamed or removed is reported missing`() {
        listOf("$byAck ", "enquiry-lookups.by-acknowledgement", "enquiry-lookup.by-acknowledgements", "enquiry-lookup.run", "Enquiry-lookup.by-acknowledgement").forEach { name ->
            assertReported(withPermission(byAck) { it.copy(key = name) }, "key $byAck: missing")
        }
        assertReported(real.copy(permissions = real.permissions.filterNot { it.key == byAck }), "key $byAck: missing")
    }

    @Test
    fun `probe 60 reverse cells and fields changed alone are each reported`() {
        listOf("tenant.admin", "finance.viewer", "security.officer").forEach { r ->
            assertReported(cellAdd(reverse, r, saasF), "role $r key $reverse form SAAS")
        }
        assertReported(cellAdd(reverse, "agent.operator", saasF, RoleAllowance.WITHIN_SCOPE), "key $reverse")
        listOf(saasF, privF).forEach { assertReported(cellAdd(reverse, "tax.operator", it), "role tax.operator key $reverse form $it") }
        assertReported(cellRemove(reverse, "auditor", saasF), "role auditor key $reverse form SAAS")
        assertReported(cellRemove(reverse, "tax.approver", privF), "role tax.approver key $reverse form PRIVATE")
        assertReported(allowWithinScope(reverse), reverse)
        assertReported(withKind(reverse, PermissionKind.WRITE), reverse)
        listOf("Look up an enquiry by Acknowledgement ID", "Reverse-look up an enquiry ").forEach { assertReported(withRow(reverse, it), "key $reverse") }
        assertReported(duplicateOnRow(reverse, "enquiry-lookup.reverse2"), "enquiry-lookup.reverse2")
        listOf("$reverse ", "enquiry-lookups.reverse", "enquiry-lookup.reverses", "Enquiry-lookup.reverse").forEach { name ->
            assertReported(withPermission(reverse) { it.copy(key = name) }, "key $reverse: missing")
        }
        assertReported(real.copy(permissions = real.permissions.filterNot { it.key == reverse }), "key $reverse: missing")
    }

    @Test
    fun `probe 61 sample cells and fields changed alone are each reported`() {
        listOf("entity.admin", "tenant.admin", "tax.approver", "auditor").forEach { r ->
            assertReported(cellAdd(sample, r, saasF), "key $sample")
        }
        assertReported(cellAdd(sample, "agent.operator", saasF, RoleAllowance.WITHIN_SCOPE), "key $sample")
        assertReported(cellRemove(sample, "tax.operator", privF), "role tax.operator key $sample form PRIVATE")
        assertReported(allowWithinScope(sample), sample)
        assertReported(withKind(sample, PermissionKind.READ), sample)
        listOf(
            permission(real, "election-change.approve").row,
            "Look up an enquiry by Acknowledgement ID",
            "Record a rendition sample check ",
        ).forEach { assertReported(withRow(sample, it), "key $sample") }
        assertReported(duplicateOnRow(sample, "rendition-sample.run"), "rendition-sample.run")
        listOf("$sample ", "rendition-samples.record", "rendition-sample.records", "Rendition-sample.record").forEach { name ->
            assertReported(withPermission(sample) { it.copy(key = name) }, "key $sample: missing")
        }
        assertReported(real.copy(permissions = real.permissions.filterNot { it.key == sample }), "key $sample: missing")
    }

    @Test
    fun `probe 62 swapped rows between the new keys are reported naming both keys`() {
        listOf(byAck to reverse, byAck to sample).forEach { (a, b) ->
            assertReported(swapRows(a, b), a, b)
        }
    }

    @Test
    fun `probe 63 each new label removed from addedRows while its key stays fails naming the key and the label`() {
        RolePermissionsChecker.lookupSamplePairs.forEach { (key, label) ->
            val v = RolePermissionsChecker.check(real, added = RolePermissionsChecker.addedRows - label)
            assertTrue(v.any { "key $key" in it && "row '$label'" in it }, "$v")
        }
    }

    private val purge = "audit-entry.purge"

    @Test
    fun `probe 70 the purge row changed to Purge audit entry is reported`() {
        assertReported(withRow(purge, "Purge audit entry"), "key $purge", "Purge audit entry", "neither one of the 34")
        assertReported(withRow(purge, "Purge audit entries "), "key $purge", "neither one of the 34")
    }

    @Test
    fun `probe 71 the purge label with no key is reported`() {
        assertReported(real.copy(permissions = real.permissions.filterNot { it.key == purge }), "key $purge: missing")
        assertReported(withPermission(purge) { it.copy(key = "audit-entries.purge") }, "key $purge: missing")
    }

    @Test
    fun `probe 72 extra purge cells are reported`() {
        assertReported(cellAdd(purge, "deployment.operator", saasF), "key $purge", "extra")
        assertReported(cellAdd(purge, "platform.engineer", privF), "key $purge", "extra")
    }

    @Test
    fun `probe 73 the purge SAAS cell removed or the kind set to READ is reported`() {
        assertReported(cellRemove(purge, "platform.engineer", saasF), "key $purge", "missing")
        assertReported(withKind(purge, PermissionKind.READ), "key $purge: kind READ")
    }

    private val scKey = "security-case.record"

    private fun scRow() = permission(real, "privacy-request.handle").row

    @Test
    fun `INV-T-11695 the key is WRITE with the row of privacy-request-handle held by security officer only`() {
        val p = permission(real, scKey)
        assertEquals(PermissionKind.WRITE, p.kind)
        assertEquals(scRow(), p.row)
        assertEquals(
            setOf(CellRow("security.officer", DeploymentForm.SAAS, RoleAllowance.PERMITTED), CellRow("security.officer", DeploymentForm.PRIVATE, RoleAllowance.PERMITTED)),
            p.cells.toSet(),
        )
        assertEquals(2, p.cells.size)
    }

    @Test
    fun `INV-T-11695 probe a changed or padded row of the key is reported`() {
        assertReported(withPermission(scKey) { it.copy(row = scRow().removeSuffix("requests") + "request") }, "key $scKey")
        assertReported(withPermission(scKey) { it.copy(row = scRow() + " ") }, "key $scKey")
    }

    @Test
    fun `INV-T-11695 probe extra cells of the key are reported`() {
        val tax = withPermission(scKey) { it.copy(cells = it.cells + CellRow("tax.approver", DeploymentForm.SAAS, RoleAllowance.PERMITTED)) }
        assertReported(tax, "role tax.approver key $scKey form SAAS", "extra")
        val dep = withPermission(scKey) { it.copy(cells = it.cells + CellRow("deployment.operator", DeploymentForm.PRIVATE, RoleAllowance.PERMITTED)) }
        assertReported(dep, "role deployment.operator key $scKey form PRIVATE", "extra")
    }

    @Test
    fun `INV-T-11695 probe a removed cell of the key is reported`() {
        assertReported(withPermission(scKey) { p -> p.copy(cells = p.cells.filter { it.form != DeploymentForm.PRIVATE }) }, "role security.officer key $scKey form PRIVATE", "missing")
        assertReported(withPermission(scKey) { p -> p.copy(cells = p.cells.filter { it.form != DeploymentForm.SAAS }) }, "role security.officer key $scKey form SAAS", "missing")
    }

    @Test
    fun `INV-T-11695 probe a READ kind of the key is reported`() {
        assertReported(withPermission(scKey) { it.copy(kind = PermissionKind.READ) }, "key $scKey")
    }

    @Test
    fun `INV-T-11695 probe a removed key and look-alike keys are reported as missing`() {
        assertReported(real.copy(permissions = real.permissions.filter { it.key != scKey }), "key $scKey: missing")
        for (alias in listOf("security-cases.record", "security-case.record ", "Security-case.record", "security.case.record")) {
            assertReported(withPermission(scKey) { it.copy(key = alias) }, "key $scKey: missing")
        }
    }

    private val scReq = "sample-copy.request"
    private val scApp = "sample-copy.approve"

    @Test
    fun `INV-T-11910 the two keys are WRITE with their pinned rows and exact cells`() {
        RolePermissionsChecker.sampleCopyPairs.forEach { (k, row) ->
            assertEquals(row, permission(real, k).row, k)
            assertEquals(PermissionKind.WRITE, permission(real, k).kind, k)
        }
        assertEquals(setOf(scReq, scApp), RolePermissionsChecker.sampleCopyPairs.keys)
        fun cells(role: String) = setOf(CellRow(role, DeploymentForm.SAAS, RoleAllowance.PERMITTED), CellRow(role, DeploymentForm.PRIVATE, RoleAllowance.PERMITTED))
        assertEquals(cells("tenant.admin"), permission(real, scReq).cells.toSet())
        assertEquals(cells("security.officer"), permission(real, scApp).cells.toSet())
        assertEquals(2, permission(real, scReq).cells.size)
        assertEquals(2, permission(real, scApp).cells.size)
    }

    @Test
    fun `INV-T-11910 probe a security officer cell on request is reported naming the key`() {
        assertReported(cellAdd(scReq, "security.officer", saasF), "role security.officer key $scReq form SAAS", "extra")
    }

    @Test
    fun `INV-T-11910 probe a tenant admin cell on approve is reported naming the key`() {
        assertReported(cellAdd(scApp, "tenant.admin", saasF), "role tenant.admin key $scApp form SAAS", "extra")
    }

    @Test
    fun `INV-T-11910 probe the security officer PRIVATE cell removed from approve is reported`() {
        assertReported(cellRemove(scApp, "security.officer", privF), "role security.officer key $scApp form PRIVATE", "missing")
    }

    @Test
    fun `INV-T-11910 probe the allowance WITHIN_SCOPE is reported on each key`() {
        listOf(scReq to "tenant.admin", scApp to "security.officer").forEach { (k, role) ->
            val t = withPermission(k) { p -> p.copy(cells = p.cells.map { if (it.role == role && it.form == DeploymentForm.SAAS) it.copy(allowance = RoleAllowance.WITHIN_SCOPE) else it }) }
            assertReported(t, "role $role key $k form SAAS", "WITHIN_SCOPE")
        }
    }

    @Test
    fun `INV-T-11910 probe the kind READ is reported on each key`() {
        assertReported(withKind(scReq, PermissionKind.READ), "key $scReq: kind READ")
        assertReported(withKind(scApp, PermissionKind.READ), "key $scApp: kind READ")
    }

    @Test
    fun `INV-T-11910 probe the row of one key written as the row of the other is reported naming the key`() {
        val reqRow = RolePermissionsChecker.sampleCopyPairs.getValue(scReq)
        val appRow = RolePermissionsChecker.sampleCopyPairs.getValue(scApp)
        assertReported(withPermission(scReq) { it.copy(row = appRow) }, "key $scReq", "differs")
        assertReported(withPermission(scApp) { it.copy(row = reqRow) }, "key $scApp", "differs")
    }

    @Test
    fun `INV-T-11910 probe a renamed key is reported missing and the new name is reported`() {
        assertReported(withPermission(scReq) { it.copy(key = "sample-copy.requests") }, "key $scReq: missing")
        assertReported(withPermission(scReq) { it.copy(key = "sample-copy.requests") }, "key sample-copy.requests")
    }

    @Test
    fun `INV-T-11910 probe a third key sample-copy cancel is reported naming the key`() {
        val third = permission(real, scReq).copy(key = "sample-copy.cancel")
        assertReported(real.copy(permissions = real.permissions + third), "key sample-copy.cancel")
        val ownRow = permission(real, scReq).copy(key = "sample-copy.cancel", row = permission(real, "ap-credential.manage").row)
        assertReported(real.copy(permissions = real.permissions + ownRow), "key sample-copy.cancel")
    }

    @Test
    fun `INV-T-11910 each sample copy label removed from addedRows while its key stays fails naming the key`() {
        RolePermissionsChecker.sampleCopyPairs.forEach { (key, label) ->
            val v = RolePermissionsChecker.check(real, added = RolePermissionsChecker.addedRows - label)
            assertTrue(v.any { "key $key" in it && "row '$label'" in it }, "$v")
        }
    }

    private val cm = "compliance-metrics.view"

    @Test
    fun `INV-T-12840 the key is READ with its pinned row and exactly four cells`() {
        assertEquals(mapOf(cm to "View coverage, deadline and correction metrics per entity and period"), RolePermissionsChecker.complianceMetricsPairs)
        assertEquals("View coverage, deadline and correction metrics per entity and period", permission(real, cm).row)
        assertEquals(PermissionKind.READ, permission(real, cm).kind)
        val expected = listOf("tax.approver", "finance.viewer").flatMap { r -> listOf(saasF, privF).map { CellRow(r, it, RoleAllowance.PERMITTED) } }.toSet()
        assertEquals(expected, permission(real, cm).cells.toSet())
        assertEquals(4, permission(real, cm).cells.size)
    }

    @Test
    fun `INV-T-12840 probe a tax operator cell is reported naming the key`() {
        assertReported(cellAdd(cm, "tax.operator", saasF), "role tax.operator key $cm form SAAS", "extra")
    }

    @Test
    fun `INV-T-12840 probe an auditor cell is reported naming the key`() {
        assertReported(cellAdd(cm, "auditor", saasF), "role auditor key $cm form SAAS", "extra")
    }

    @Test
    fun `INV-T-12840 probe the finance viewer PRIVATE cell removed is reported`() {
        assertReported(cellRemove(cm, "finance.viewer", privF), "role finance.viewer key $cm form PRIVATE", "missing")
    }

    @Test
    fun `INV-T-12840 probe the allowance WITHIN_SCOPE is reported`() {
        val t = withPermission(cm) { p -> p.copy(cells = p.cells.map { if (it.role == "tax.approver" && it.form == saasF) it.copy(allowance = RoleAllowance.WITHIN_SCOPE) else it }) }
        assertReported(t, "role tax.approver key $cm form SAAS", "WITHIN_SCOPE")
    }

    @Test
    fun `INV-T-12840 probe the kind WRITE is reported`() {
        assertReported(withKind(cm, PermissionKind.WRITE), "key $cm: kind WRITE")
    }

    @Test
    fun `INV-T-12840 probe the row of submission view-status is reported naming the key`() {
        assertReported(withRow(cm, "View submission status"), "key $cm", "differs")
    }

    @Test
    fun `INV-T-12840 probe a renamed key is reported missing and the new name is reported`() {
        assertReported(withPermission(cm) { it.copy(key = "compliance-metric.view") }, "key $cm: missing")
        assertReported(withPermission(cm) { it.copy(key = "compliance-metric.view") }, "key compliance-metric.view")
    }

    @Test
    fun `INV-T-12840 probe a second key compliance-metrics export is reported naming the key`() {
        val second = permission(real, cm).copy(key = "compliance-metrics.export")
        assertReported(real.copy(permissions = real.permissions + second), "key compliance-metrics.export")
        val ownRow = permission(real, cm).copy(key = "compliance-metrics.export", row = permission(real, "ap-credential.manage").row)
        assertReported(real.copy(permissions = real.permissions + ownRow), "key compliance-metrics.export")
    }

    @Test
    fun `INV-T-12840 the label removed from addedRows while its key stays fails naming the key`() {
        RolePermissionsChecker.complianceMetricsPairs.forEach { (key, label) ->
            val v = RolePermissionsChecker.check(real, added = RolePermissionsChecker.addedRows - label)
            assertTrue(v.any { "key $key" in it && "row '$label'" in it }, "$v")
        }
    }
}
