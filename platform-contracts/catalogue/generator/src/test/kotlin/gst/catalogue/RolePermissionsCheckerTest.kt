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
        assertEquals(38, RolePermissionsChecker.addedRows.toSet().size)
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

    private val upload = "filed-return.upload"

    @Test
    fun `probe 25a the real upload key pins WRITE and the four cells`() {
        val p = permission(real, upload)
        assertEquals(PermissionKind.WRITE, p.kind)
        assertEquals(
            setOf(
                Triple("agent.operator", DeploymentForm.SAAS, RoleAllowance.WITHIN_SCOPE),
                Triple("agent.operator", DeploymentForm.PRIVATE, RoleAllowance.WITHIN_SCOPE),
                Triple("tax.approver", DeploymentForm.SAAS, RoleAllowance.PERMITTED),
                Triple("tax.approver", DeploymentForm.PRIVATE, RoleAllowance.PERMITTED),
            ),
            p.cells.map { Triple(it.role, it.form, it.allowance) }.toSet(),
        )
        assertEquals(4, p.cells.size)
        assertTrue(RolePermissionsChecker.check(real).isEmpty())
    }

    @Test
    fun `probe 25b a removed tax approver cell of the upload key is reported per form`() {
        DeploymentForm.values().forEach { f ->
            val table = withPermission(upload) { p -> p.copy(cells = p.cells.filter { !(it.role == "tax.approver" && it.form == f) }) }
            assertReported(table, "role tax.approver key $upload form $f", "missing")
        }
    }

    @Test
    fun `probe 25c a tax approver upload cell changed to WITHIN_SCOPE is reported`() {
        val table =
            withPermission(upload) { p ->
                p.copy(cells = p.cells.map { if (it.role == "tax.approver" && it.form == DeploymentForm.SAAS) it.copy(allowance = RoleAllowance.WITHIN_SCOPE) else it })
            }
        assertReported(table, "role tax.approver key $upload form SAAS", "required PERMITTED")
    }

    @Test
    fun `probe 25d client signatory and auditor cells on the upload key are reported`() {
        assertReported(cellAdd(upload, "client.signatory", DeploymentForm.PRIVATE), "role client.signatory key $upload form PRIVATE", "extra")
        assertReported(cellAdd(upload, "auditor", DeploymentForm.SAAS), "role auditor key $upload form SAAS", "extra")
    }

    @Test
    fun `probe 25e tax approver on reconciliation run is still reported`() {
        assertReported(cellAdd("reconciliation.run", "tax.approver", DeploymentForm.SAAS), "role tax.approver key reconciliation.run form SAAS", "extra")
    }

    @Test
    fun `probe 25f a renamed upload key is reported as missing and as unknown`() {
        listOf("filed-return.uploads", "$upload ").forEach { name ->
            val table = withPermission(upload) { it.copy(key = name) }
            assertReported(table, "key $upload: missing")
            assertReported(table, "key $name: not one of the keys the criteria require")
        }
        val extra =
            real.copy(
                permissions =
                    real.permissions +
                        permission(real, "reconciliation.run").copy(
                            key = "filed-return.uploads",
                            cells = listOf(CellRow("tax.approver", DeploymentForm.SAAS, RoleAllowance.PERMITTED)),
                        ),
            )
        assertReported(extra, "key filed-return.uploads: not one of the keys the criteria require")
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
            setOf("Set a variation point", "Acknowledge a control relaxation", "Run the authority incarnation command", "Author a regulatory pack", "Approve a regulatory pack") + opLabels.values + lookupLabels + "Purge audit entries" + sampleCopyLabels + "View coverage, deadline and correction metrics per entity and period" + "Approve a backfill reconciliation before transmission" + "Defer a document or group in the exception queue" + "Request elevation" + "Invite operator diagnostics, time-boxed" + "Reseal a break-glass session" + "Open a tenant-scoped operator session" + "Release a document held for manual review" + "Request an implementation consultant grant" + "View the exception queue" + "Record an IRAS-granted filing extension" + RolePermissionsChecker.gapReportPairs.values + RolePermissionsChecker.sandboxCertificationPairs.values + RolePermissionsChecker.accreditationReadPairs.values + RolePermissionsChecker.invitationReissuePairs.values + RolePermissionsChecker.commercialReadPairs.values,
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
            ) + lookupLabels + "Purge audit entries" + sampleCopyLabels + "View coverage, deadline and correction metrics per entity and period" + "Approve a backfill reconciliation before transmission" + "Defer a document or group in the exception queue" + "Request elevation" + "Invite operator diagnostics, time-boxed" + "Reseal a break-glass session" + "Open a tenant-scoped operator session" + "Release a document held for manual review" + "Request an implementation consultant grant" + "View the exception queue" + "Record an IRAS-granted filing extension" + RolePermissionsChecker.gapReportPairs.values + RolePermissionsChecker.sandboxCertificationPairs.values + RolePermissionsChecker.accreditationReadPairs.values + RolePermissionsChecker.invitationReissuePairs.values + RolePermissionsChecker.commercialReadPairs.values,
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

    private val ecw = "election-change.withdraw"
    private val ecl = "election-change.list"

    @Test
    fun `election-change withdraw and list carry the entity-config confirm row and cells`() {
        listOf(ecw to PermissionKind.WRITE, ecl to PermissionKind.READ).forEach { (k, kind) ->
            assertEquals(permission(real, "entity-config.confirm").row, permission(real, k).row)
            assertEquals(kind, permission(real, k).kind)
            assertEquals(
                permission(real, "entity-config.confirm").cells.map { Triple(it.role, it.form, it.allowance) }.toSet(),
                permission(real, k).cells.map { Triple(it.role, it.form, it.allowance) }.toSet(),
            )
        }
        assertEquals(34, RolePermissionsChecker.publishedRows.toSet().size)
    }

    @Test
    fun `probe election-change list and withdraw cells kinds and rows changed alone are each reported`() {
        assertReported(cellAdd(ecl, "auditor", DeploymentForm.SAAS), "key $ecl")
        assertReported(cellAdd(ecw, "client.signatory", DeploymentForm.SAAS), "key $ecw")
        listOf(ecw, ecl).forEach { k ->
            assertReported(cellRemove(k, "entity.admin", DeploymentForm.PRIVATE), "role entity.admin key $k form PRIVATE")
            assertReported(withPermission(k) { it.copy(row = it.row + " ") }, "key $k")
        }
        assertReported(withPermission(ecl) { it.copy(kind = PermissionKind.WRITE) }, "key $ecl")
    }

    @Test
    fun `probe election-change withdraw and list renamed or removed are reported missing`() {
        listOf("$ecw ", "election-change.withdrawn", "election-change.lists", "Election-change.list").forEach { name ->
            val k = if (name.contains("list", ignoreCase = true)) ecl else ecw
            assertReported(withPermission(k) { it.copy(key = name) }, "key $k: missing")
        }
        listOf(ecw, ecl).forEach { k ->
            assertReported(real.copy(permissions = real.permissions.filterNot { it.key == k }), "key $k: missing")
        }
    }

    @Test
    fun `probe election-change approve given the cells of entity-config confirm is reported`() {
        assertReported(
            withPermission(eca) { p -> p.copy(cells = permission(real, "entity-config.confirm").cells) },
            "role entity.admin key $eca",
        )
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

    private val bf = "backfill.approve"
    private val bfRow = "Approve a backfill reconciliation before transmission"

    @Test
    fun `INV-T-13356 the key is WRITE with its pinned row and exactly two cells`() {
        assertEquals(mapOf(bf to bfRow), RolePermissionsChecker.backfillPairs)
        assertEquals(bfRow, permission(real, bf).row)
        assertEquals(PermissionKind.WRITE, permission(real, bf).kind)
        assertEquals(setOf(CellRow("tax.approver", saasF, RoleAllowance.PERMITTED), CellRow("tax.approver", privF, RoleAllowance.PERMITTED)), permission(real, bf).cells.toSet())
        assertEquals(2, permission(real, bf).cells.size)
        assertEquals(emptyList<String>(), RolePermissionsChecker.check(real))
    }

    @Test
    fun `INV-T-13356 probe a tax operator cell is reported naming the key`() {
        assertReported(cellAdd(bf, "tax.operator", saasF), "role tax.operator key $bf form SAAS", "extra")
    }

    @Test
    fun `INV-T-13356 probe the PRIVATE cell removed is reported`() {
        assertReported(cellRemove(bf, "tax.approver", privF), "role tax.approver key $bf form PRIVATE", "missing")
    }

    @Test
    fun `INV-T-13356 probe finance viewer in place of tax approver is reported`() {
        val t = withPermission(bf) { p -> p.copy(cells = p.cells.map { it.copy(role = "finance.viewer") }) }
        assertReported(t, "role finance.viewer key $bf", "extra")
        assertReported(t, "role tax.approver key $bf", "missing")
    }

    @Test
    fun `INV-T-13356 probe a renamed key is reported missing and the new name is reported`() {
        val t = withPermission(bf) { it.copy(key = "backfill.approved") }
        assertReported(t, "key $bf: missing")
        assertReported(t, "key backfill.approved")
    }

    @Test
    fun `INV-T-13356 probe a second key backfill run with the same row is reported`() {
        val second = permission(real, bf).copy(key = "backfill.run")
        assertReported(real.copy(permissions = real.permissions + second), "key backfill.run")
    }

    @Test
    fun `INV-T-13356 the label removed from addedRows while its key stays fails naming the key`() {
        RolePermissionsChecker.backfillPairs.forEach { (key, label) ->
            val v = RolePermissionsChecker.check(real, added = RolePermissionsChecker.addedRows - label)
            assertTrue(v.any { "key $key" in it && "row '$label'" in it }, "$v")
        }
    }

    private val df = "exception.defer"
    private val dfRow = "Defer a document or group in the exception queue"

    @Test
    fun `INV-T-13835 the key is WRITE with its pinned row and exactly four cells`() {
        assertEquals(mapOf(df to dfRow), RolePermissionsChecker.deferPairs)
        assertEquals(dfRow, permission(real, df).row)
        assertEquals(PermissionKind.WRITE, permission(real, df).kind)
        assertEquals(
            setOf(
                CellRow("tax.operator", saasF, RoleAllowance.PERMITTED),
                CellRow("tax.operator", privF, RoleAllowance.PERMITTED),
                CellRow("agent.operator", saasF, RoleAllowance.WITHIN_SCOPE),
                CellRow("agent.operator", privF, RoleAllowance.WITHIN_SCOPE),
            ),
            permission(real, df).cells.toSet(),
        )
        assertEquals(4, permission(real, df).cells.size)
        assertEquals(emptyList<String>(), RolePermissionsChecker.check(real))
    }

    @Test
    fun `INV-T-13835 probe a tax approver cell is reported as extra`() {
        assertReported(cellAdd(df, "tax.approver", saasF), "role tax.approver key $df form SAAS", "extra")
    }

    @Test
    fun `INV-T-13835 probe the tax operator PRIVATE cell removed is reported`() {
        assertReported(cellRemove(df, "tax.operator", privF), "role tax.operator key $df form PRIVATE", "missing")
    }

    @Test
    fun `INV-T-13835 probe the agent operator SAAS cell PERMITTED is reported`() {
        val t = withPermission(df) { p -> p.copy(cells = p.cells.map { if (it.role == "agent.operator" && it.form == saasF) it.copy(allowance = RoleAllowance.PERMITTED) else it }) }
        assertReported(t, "role agent.operator key $df form SAAS", "WITHIN_SCOPE")
    }

    @Test
    fun `INV-T-13835 probe tax approver cells in place of agent operator cells are reported`() {
        val t = withPermission(df) { p -> p.copy(cells = p.cells.map { if (it.role == "agent.operator") it.copy(role = "tax.approver") else it }) }
        assertReported(t, "role tax.approver key $df", "extra")
        assertReported(t, "role agent.operator key $df", "missing")
    }

    @Test
    fun `INV-T-13835 probe a renamed key is reported missing and the new name is reported`() {
        val t = withPermission(df) { it.copy(key = "exception.deferred") }
        assertReported(t, "key $df: missing")
        assertReported(t, "key exception.deferred")
    }

    @Test
    fun `INV-T-13835 probe a second key exception undefer with the same row is reported`() {
        val second = permission(real, df).copy(key = "exception.undefer")
        assertReported(real.copy(permissions = real.permissions + second), "key exception.undefer")
    }

    @Test
    fun `INV-T-13835 the label removed from addedRows while its key stays fails naming the key`() {
        RolePermissionsChecker.deferPairs.forEach { (key, label) ->
            val v = RolePermissionsChecker.check(real, added = RolePermissionsChecker.addedRows - label)
            assertTrue(v.any { "key $key" in it && "row '$label'" in it }, "$v")
        }
    }

    private val er = "elevation.request"
    private val di = "diagnostic.invite"
    private val erRow = "Request elevation"
    private val diRow = "Invite operator diagnostics, time-boxed"

    @Test
    fun `INV-T-15959 the two keys are WRITE with their pinned rows and exactly one cell each`() {
        assertEquals(mapOf(er to erRow, di to diRow), RolePermissionsChecker.elevationRequestPairs)
        assertEquals(erRow, permission(real, er).row)
        assertEquals(diRow, permission(real, di).row)
        assertEquals(PermissionKind.WRITE, permission(real, er).kind)
        assertEquals(PermissionKind.WRITE, permission(real, di).kind)
        assertEquals(listOf(CellRow("platform.support", saasF, RoleAllowance.PERMITTED)), permission(real, er).cells)
        assertEquals(listOf(CellRow("deployment.operator", privF, RoleAllowance.PERMITTED)), permission(real, di).cells)
        assertEquals(
            listOf(
                CellRow("security.officer", saasF, RoleAllowance.PERMITTED),
                CellRow("security.officer", privF, RoleAllowance.PERMITTED),
            ),
            permission(real, "elevation.decide").cells,
        )
        assertEquals(emptyList<String>(), RolePermissionsChecker.check(real))
    }

    @Test
    fun `INV-T-15959 probe a renamed key is reported missing and the new name is reported`() {
        assertReported(withPermission(er) { it.copy(key = "elevation.requests") }, "key $er: missing")
        assertReported(withPermission(er) { it.copy(key = "elevation.requests") }, "key elevation.requests")
        assertReported(withPermission(di) { it.copy(key = "diagnostic.invitation") }, "key $di: missing")
        assertReported(withPermission(di) { it.copy(key = "diagnostic.invitation") }, "key diagnostic.invitation")
    }

    @Test
    fun `INV-T-15959 probe a second key carrying either label or either name prefix is reported`() {
        listOf(er to erRow, di to diRow).forEach { (key, row) ->
            val second = permission(real, key).copy(key = "third.key")
            assertReported(real.copy(permissions = real.permissions + second), "key third.key")
            val prefixed = permission(real, key).copy(key = "${key}s", row = "Approve a backfill reconciliation before transmission")
            assertReported(real.copy(permissions = real.permissions + prefixed), "key ${key}s")
            assertEquals(row, permission(real, key).row)
        }
    }

    @Test
    fun `INV-T-15959 probe a cell in a form the role does not have is reported`() {
        assertReported(cellAdd(er, "platform.support", privF), "role platform.support key $er form PRIVATE")
        assertReported(cellAdd(di, "deployment.operator", saasF), "role deployment.operator key $di form SAAS")
    }

    @Test
    fun `INV-T-15959 probe an extra platform engineer or security officer cell is reported on either key`() {
        listOf(er to saasF, di to privF).forEach { (key, form) ->
            listOf("platform.engineer", "security.officer").forEach { role ->
                assertReported(cellAdd(key, role, form), "role $role key $key form $form", "extra")
            }
        }
    }

    @Test
    fun `INV-T-15959 probe the one cell removed from either key is reported`() {
        assertReported(cellRemove(er, "platform.support", saasF), "role platform.support key $er form SAAS", "missing")
        assertReported(cellRemove(di, "deployment.operator", privF), "role deployment.operator key $di form PRIVATE", "missing")
    }

    @Test
    fun `INV-T-15959 probe the kind READ instead of WRITE is reported`() {
        assertReported(withKind(er, PermissionKind.READ), "key $er")
        assertReported(withKind(di, PermissionKind.READ), "key $di")
    }

    @Test
    fun `INV-T-15959 probe a changed cell of elevation decide is reported`() {
        assertReported(cellAdd("elevation.decide", "platform.support", saasF), "role platform.support key elevation.decide form SAAS", "extra")
        assertReported(cellRemove("elevation.decide", "security.officer", privF), "role security.officer key elevation.decide form PRIVATE", "missing")
    }

    @Test
    fun `INV-T-15959 each label removed from addedRows while its key stays fails naming the key`() {
        RolePermissionsChecker.elevationRequestPairs.forEach { (key, label) ->
            val v = RolePermissionsChecker.check(real, added = RolePermissionsChecker.addedRows - label)
            assertTrue(v.any { "key $key" in it && "row '$label'" in it }, "$v")
        }
    }

    private val rs = "break-glass.reseal"
    private val rsRow = "Reseal a break-glass session"
    private val bgUnlock = "break-glass.unlock"

    @Test
    fun `INV-T-16106 the key is WRITE with its pinned row and exactly one cell`() {
        assertEquals(mapOf(rs to rsRow), RolePermissionsChecker.resealPairs)
        assertEquals(rsRow, permission(real, rs).row)
        assertEquals(PermissionKind.WRITE, permission(real, rs).kind)
        assertEquals(setOf(CellRow("deployment.operator", privF, RoleAllowance.PERMITTED)), permission(real, rs).cells.toSet())
        assertEquals(1, permission(real, rs).cells.size)
        assertEquals(emptyList<String>(), RolePermissionsChecker.check(real))
    }

    @Test
    fun `INV-T-16106 probe a tenant admin cell is reported naming the key`() {
        assertReported(cellAdd(rs, "tenant.admin", privF), "role tenant.admin key $rs form PRIVATE", "extra")
    }

    @Test
    fun `INV-T-16106 probe a tax approver cell is reported naming the key`() {
        assertReported(cellAdd(rs, "tax.approver", privF), "role tax.approver key $rs form PRIVATE", "extra")
    }

    @Test
    fun `INV-T-16106 probe a deployment operator SAAS cell is reported naming the key`() {
        assertReported(cellAdd(rs, "deployment.operator", saasF), "role deployment.operator key $rs form SAAS", "extra")
    }

    @Test
    fun `INV-T-16106 probe a platform engineer SAAS cell is reported naming the key`() {
        assertReported(cellAdd(rs, "platform.engineer", saasF), "role platform.engineer key $rs form SAAS", "extra")
    }

    @Test
    fun `INV-T-16106 probe the allowance WITHIN_SCOPE is reported`() {
        val t = withPermission(rs) { p -> p.copy(cells = p.cells.map { it.copy(allowance = RoleAllowance.WITHIN_SCOPE) }) }
        assertReported(t, "role deployment.operator key $rs form PRIVATE", "WITHIN_SCOPE")
    }

    @Test
    fun `INV-T-16106 probe the kind READ is reported`() {
        assertReported(withKind(rs, PermissionKind.READ), "key $rs: kind READ")
    }

    @Test
    fun `INV-T-16106 probe the row of break-glass unlock is reported naming the key`() {
        assertReported(withRow(rs, permission(real, bgUnlock).row), "key $rs", "differs")
    }

    @Test
    fun `INV-T-16106 probe the cell removed is reported`() {
        assertReported(cellRemove(rs, "deployment.operator", privF), "role deployment.operator key $rs form PRIVATE", "missing")
    }

    @Test
    fun `INV-T-16106 probe a renamed key is reported missing and the new name is reported`() {
        listOf("break-glass.reseals", "break-glass.reseal ", "Break-glass.reseal").forEach { name ->
            val t = withPermission(rs) { it.copy(key = name) }
            assertReported(t, "key $rs: missing")
            assertReported(t, "key $name")
        }
    }

    @Test
    fun `INV-T-16106 probe a second key break-glass sign-in is reported naming the key`() {
        val second = permission(real, rs).copy(key = "break-glass.sign-in")
        assertReported(real.copy(permissions = real.permissions + second), "key break-glass.sign-in")
        val ownRow = permission(real, rs).copy(key = "break-glass.sign-in", row = permission(real, bgUnlock).row)
        assertReported(real.copy(permissions = real.permissions + ownRow), "key break-glass.sign-in")
    }

    @Test
    fun `INV-T-16106 the label removed from addedRows while its key stays fails naming the key`() {
        RolePermissionsChecker.resealPairs.forEach { (key, label) ->
            val v = RolePermissionsChecker.check(real, added = RolePermissionsChecker.addedRows - label)
            assertTrue(v.any { "key $key" in it && "row '$label'" in it }, "$v")
        }
    }

    private val os = "operator-session.open"
    private val osRow = "Open a tenant-scoped operator session"

    @Test
    fun `INV-T-16477 the key is WRITE with its pinned row and exactly the three cells`() {
        assertEquals(mapOf(os to osRow), RolePermissionsChecker.operatorSessionPairs)
        assertEquals(osRow, permission(real, os).row)
        assertEquals(PermissionKind.WRITE, permission(real, os).kind)
        assertEquals(
            setOf(
                CellRow("platform.support", saasF, RoleAllowance.PERMITTED),
                CellRow("platform.engineer", saasF, RoleAllowance.PERMITTED),
                CellRow("deployment.operator", privF, RoleAllowance.PERMITTED),
            ),
            permission(real, os).cells.toSet(),
        )
        assertEquals(3, permission(real, os).cells.size)
        assertEquals(emptyList<String>(), RolePermissionsChecker.check(real))
    }

    @Test
    fun `INV-T-16477 probe a renamed key is reported`() {
        assertReported(withPermission(os) { it.copy(key = "operator-session.opens") }, "key $os: missing")
        assertReported(withPermission(os) { it.copy(key = "operator-session.opens") }, "key operator-session.opens")
        assertReported(withPermission(os) { it.copy(key = "operator-sessions.open") }, "key $os: missing")
        assertReported(withPermission(os) { it.copy(key = "operator-sessions.open") }, "key operator-sessions.open")
    }

    @Test
    fun `INV-T-16477 probe a cell in a form the role does not have or an extra role is reported`() {
        assertReported(cellAdd(os, "platform.support", privF), "role platform.support key $os form PRIVATE", "extra")
        assertReported(cellAdd(os, "platform.engineer", privF), "role platform.engineer key $os form PRIVATE", "extra")
        assertReported(cellAdd(os, "deployment.operator", saasF), "role deployment.operator key $os form SAAS")
        assertReported(cellAdd(os, "security.officer", saasF), "role security.officer key $os form SAAS", "extra")
        assertReported(cellAdd(os, "tax.approver", saasF), "role tax.approver key $os form SAAS", "extra")
    }

    @Test
    fun `INV-T-16477 probe each cell removed is reported`() {
        assertReported(cellRemove(os, "platform.engineer", saasF), "role platform.engineer key $os form SAAS", "missing")
        assertReported(cellRemove(os, "platform.support", saasF), "role platform.support key $os form SAAS", "missing")
        assertReported(cellRemove(os, "deployment.operator", privF), "role deployment.operator key $os form PRIVATE", "missing")
    }

    @Test
    fun `INV-T-16477 probe the kind READ instead of WRITE is reported`() {
        assertReported(withKind(os, PermissionKind.READ), "key $os")
    }

    @Test
    fun `INV-T-16477 probe a second new key is reported`() {
        val second = permission(real, os).copy(key = "operator-session.close")
        assertReported(real.copy(permissions = real.permissions + second), "key operator-session.close")
        val other = permission(real, os).copy(key = "operator.other", row = "Another new row")
        assertReported(real.copy(permissions = real.permissions + other), "operator.other")
    }

    @Test
    fun `INV-T-16477 the label removed from addedRows while its key stays fails naming the key`() {
        RolePermissionsChecker.operatorSessionPairs.forEach { (key, label) ->
            val v = RolePermissionsChecker.check(real, added = RolePermissionsChecker.addedRows - label)
            assertTrue(v.any { "key $key" in it && "row '$label'" in it }, "$v")
        }
    }

    private val hd = "held-document.release"
    private val hdRow = "Release a document held for manual review"

    @Test
    fun `INV-T-16540 the key is WRITE with its pinned row and exactly the two tax approver cells`() {
        assertEquals(mapOf(hd to hdRow), RolePermissionsChecker.heldDocumentPairs)
        assertTrue(hdRow in RolePermissionsChecker.addedRows)
        assertEquals(hdRow, permission(real, hd).row)
        assertEquals(PermissionKind.WRITE, permission(real, hd).kind)
        assertEquals(
            setOf(
                CellRow("tax.approver", saasF, RoleAllowance.PERMITTED),
                CellRow("tax.approver", privF, RoleAllowance.PERMITTED),
            ),
            permission(real, hd).cells.toSet(),
        )
        assertEquals(2, permission(real, hd).cells.size)
        assertEquals(emptyList<String>(), RolePermissionsChecker.check(real))
    }

    @Test
    fun `INV-T-16540 probe a renamed key is reported`() {
        assertReported(withPermission(hd) { it.copy(key = "held-document.releases") }, "key $hd: missing")
        assertReported(withPermission(hd) { it.copy(key = "held-document.releases") }, "key held-document.releases")
        assertReported(withPermission(hd) { it.copy(key = "held-documents.release") }, "key $hd: missing")
        assertReported(withPermission(hd) { it.copy(key = "held-documents.release") }, "key held-documents.release")
    }

    @Test
    fun `INV-T-16540 probe an extra cell or role is reported`() {
        assertReported(cellAdd(hd, "tax.operator", saasF), "role tax.operator key $hd form SAAS", "extra")
        assertReported(cellAdd(hd, "entity.admin", saasF), "role entity.admin key $hd form SAAS", "extra")
        assertReported(cellAdd(hd, "tenant.admin", privF), "role tenant.admin key $hd form PRIVATE", "extra")
        assertReported(
            withPermission(hd) { p ->
                p.copy(cells = p.cells.map { if (it.role == "tax.approver" && it.form == saasF) it.copy(allowance = RoleAllowance.WITHIN_SCOPE) else it })
            },
            "role tax.approver key $hd form SAAS",
        )
    }

    @Test
    fun `INV-T-16540 probe each cell removed is reported`() {
        assertReported(cellRemove(hd, "tax.approver", saasF), "role tax.approver key $hd form SAAS", "missing")
        assertReported(cellRemove(hd, "tax.approver", privF), "role tax.approver key $hd form PRIVATE", "missing")
    }

    @Test
    fun `INV-T-16540 probe the kind READ instead of WRITE is reported`() {
        assertReported(withKind(hd, PermissionKind.READ), "key $hd")
    }

    @Test
    fun `INV-T-16540 probe a second new key is reported`() {
        val second = permission(real, hd).copy(key = "held-document.discard")
        assertReported(real.copy(permissions = real.permissions + second), "key held-document.discard")
        val other = permission(real, hd).copy(key = "held.other", row = "Another new row")
        assertReported(real.copy(permissions = real.permissions + other), "held.other")
    }

    @Test
    fun `INV-T-16540 the label removed from addedRows while its key stays fails naming the key`() {
        RolePermissionsChecker.heldDocumentPairs.forEach { (key, label) ->
            val v = RolePermissionsChecker.check(real, added = RolePermissionsChecker.addedRows - label)
            assertTrue(v.any { "key $key" in it && "row '$label'" in it }, "$v")
        }
    }

    private val pe = "period-extension.record"
    private val peRow = "Record an IRAS-granted filing extension"

    @Test
    fun `INV-T-16683 the key is WRITE with its pinned row and exactly the two tax approver cells`() {
        assertEquals(mapOf(pe to peRow), RolePermissionsChecker.periodExtensionPairs)
        assertTrue(peRow in RolePermissionsChecker.addedRows)
        assertEquals(peRow, permission(real, pe).row)
        assertEquals(PermissionKind.WRITE, permission(real, pe).kind)
        assertEquals(
            setOf(
                CellRow("tax.approver", saasF, RoleAllowance.PERMITTED),
                CellRow("tax.approver", privF, RoleAllowance.PERMITTED),
            ),
            permission(real, pe).cells.toSet(),
        )
        assertEquals(2, permission(real, pe).cells.size)
        assertEquals(emptyList<String>(), RolePermissionsChecker.check(real))
    }

    @Test
    fun `INV-T-16683 probe a renamed key is reported`() {
        listOf("period-extensions.record", "period-extension.recorded", "Period-extension.record").forEach { k ->
            assertReported(withPermission(pe) { it.copy(key = k) }, "key $pe: missing")
            assertReported(withPermission(pe) { it.copy(key = k) }, "key $k")
        }
    }

    @Test
    fun `INV-T-16683 probe an extra cell or role is reported`() {
        assertReported(cellAdd(pe, "entity.admin", saasF), "role entity.admin key $pe form SAAS", "extra")
        assertReported(cellAdd(pe, "tenant.admin", privF), "role tenant.admin key $pe form PRIVATE", "extra")
        assertReported(cellAdd(pe, "tax.operator", saasF), "role tax.operator key $pe form SAAS", "extra")
        assertReported(
            withPermission(pe) { p ->
                p.copy(cells = p.cells.map { if (it.role == "tax.approver" && it.form == saasF) it.copy(allowance = RoleAllowance.WITHIN_SCOPE) else it })
            },
            "role tax.approver key $pe form SAAS",
        )
    }

    @Test
    fun `INV-T-16683 probe each cell removed is reported`() {
        assertReported(cellRemove(pe, "tax.approver", saasF), "role tax.approver key $pe form SAAS", "missing")
        assertReported(cellRemove(pe, "tax.approver", privF), "role tax.approver key $pe form PRIVATE", "missing")
    }

    @Test
    fun `INV-T-16683 probe the kind READ instead of WRITE is reported`() {
        assertReported(withKind(pe, PermissionKind.READ), "key $pe")
    }

    @Test
    fun `INV-T-16683 probe a second new key is reported`() {
        val second = permission(real, pe).copy(key = "period-extension.revoke")
        assertReported(real.copy(permissions = real.permissions + second), "key period-extension.revoke")
        val other = permission(real, pe).copy(key = "extension.other", row = "Another new row")
        assertReported(real.copy(permissions = real.permissions + other), "extension.other")
    }

    @Test
    fun `INV-T-16683 the label removed from addedRows while its key stays fails naming the key`() {
        RolePermissionsChecker.periodExtensionPairs.forEach { (key, label) ->
            val v = RolePermissionsChecker.check(real, added = RolePermissionsChecker.addedRows - label)
            assertTrue(v.any { "key $key" in it && "row '$label'" in it }, "$v")
        }
    }

    private val sc = "sandbox-certification.record"
    private val scRow = "Record a sandbox certification result"

    @Test
    fun `INV-T-17503 the key is WRITE with its pinned row and exactly the two integration engineer cells`() {
        assertEquals(mapOf(sc to scRow), RolePermissionsChecker.sandboxCertificationPairs)
        assertTrue(scRow in RolePermissionsChecker.addedRows)
        assertEquals(scRow, permission(real, sc).row)
        assertEquals(PermissionKind.WRITE, permission(real, sc).kind)
        assertEquals(
            setOf(
                CellRow("integration.engineer", saasF, RoleAllowance.PERMITTED),
                CellRow("integration.engineer", privF, RoleAllowance.PERMITTED),
            ),
            permission(real, sc).cells.toSet(),
        )
        assertEquals(2, permission(real, sc).cells.size)
        assertEquals(emptyList<String>(), RolePermissionsChecker.check(real))
    }

    @Test
    fun `INV-T-17503 probe a renamed key is reported`() {
        listOf("sandbox-certification-record", "sandbox-certification.recorded", "Sandbox-certification.record").forEach { k ->
            assertReported(withPermission(sc) { it.copy(key = k) }, "key $sc: missing")
            assertReported(withPermission(sc) { it.copy(key = k) }, "key $k")
        }
    }

    @Test
    fun `INV-T-17503 probe an extra cell or role is reported`() {
        assertReported(cellAdd(sc, "entity.admin", saasF), "role entity.admin key $sc form SAAS", "extra")
        assertReported(cellAdd(sc, "entity.admin", privF), "role entity.admin key $sc form PRIVATE", "extra")
        assertReported(cellAdd(sc, "auditor", saasF), "role auditor key $sc form SAAS", "extra")
        assertReported(
            withPermission(sc) { p ->
                p.copy(cells = p.cells.map { if (it.role == "integration.engineer" && it.form == saasF) it.copy(allowance = RoleAllowance.WITHIN_SCOPE) else it })
            },
            "role integration.engineer key $sc form SAAS",
        )
    }

    @Test
    fun `INV-T-17503 probe each cell removed is reported`() {
        assertReported(cellRemove(sc, "integration.engineer", saasF), "role integration.engineer key $sc form SAAS", "missing")
        assertReported(cellRemove(sc, "integration.engineer", privF), "role integration.engineer key $sc form PRIVATE", "missing")
    }

    @Test
    fun `INV-T-17503 probe the kind READ instead of WRITE is reported`() {
        assertReported(withKind(sc, PermissionKind.READ), "key $sc")
    }

    @Test
    fun `INV-T-17503 probe a second new key is reported`() {
        val second = permission(real, sc).copy(key = "sandbox-certification.revoke")
        assertReported(real.copy(permissions = real.permissions + second), "key sandbox-certification.revoke")
    }

    @Test
    fun `INV-T-17503 the label removed from addedRows while its key stays fails naming the key`() {
        RolePermissionsChecker.sandboxCertificationPairs.forEach { (key, label) ->
            val v = RolePermissionsChecker.check(real, added = RolePermissionsChecker.addedRows - label)
            assertTrue(v.any { "key $key" in it && "row '$label'" in it }, "$v")
        }
    }

    private val ti = "tenant-invitation.reissue"
    private val tiRow = "Reissue the first administrator invitation"

    @Test
    fun `INV-T-16220 the key is WRITE with its pinned row and exactly the commercial admin SAAS cell`() {
        assertEquals(mapOf(ti to tiRow), RolePermissionsChecker.invitationReissuePairs)
        assertTrue(tiRow in RolePermissionsChecker.addedRows)
        assertEquals(tiRow, permission(real, ti).row)
        assertEquals(PermissionKind.WRITE, permission(real, ti).kind)
        assertEquals(setOf(CellRow("commercial.admin", saasF, RoleAllowance.PERMITTED)), permission(real, ti).cells.toSet())
        assertEquals(1, permission(real, ti).cells.size)
        assertEquals(emptyList<String>(), RolePermissionsChecker.check(real))
    }

    @Test
    fun `INV-T-16220 probe release manager as an extra holder is reported`() {
        assertReported(cellAdd(ti, "release.manager", saasF), "role release.manager key $ti form SAAS", "extra")
    }

    @Test
    fun `INV-T-16220 probe the commercial admin SAAS cell removed is reported`() {
        assertReported(cellRemove(ti, "commercial.admin", saasF), "role commercial.admin key $ti form SAAS", "missing")
    }

    @Test
    fun `INV-T-16220 probe the kind READ instead of WRITE is reported`() {
        assertReported(withKind(ti, PermissionKind.READ), "key $ti")
    }

    @Test
    fun `INV-T-16220 probe a renamed key is reported`() {
        assertReported(withPermission(ti) { it.copy(key = "tenant-invitation.reissued") }, "key tenant-invitation.reissued")
        assertReported(withPermission(ti) { it.copy(key = "tenant-invitation.reissued") }, "key $ti: missing")
    }

    @Test
    fun `INV-T-16220 probe a second new key is reported`() {
        val second = permission(real, ti).copy(key = "tenant-invitation.revoke")
        assertReported(real.copy(permissions = real.permissions + second), "key tenant-invitation.revoke")
    }

    @Test
    fun `INV-T-16220 the label removed from addedRows while its key stays fails naming the key`() {
        RolePermissionsChecker.invitationReissuePairs.forEach { (key, label) ->
            val v = RolePermissionsChecker.check(real, added = RolePermissionsChecker.addedRows - label)
            assertTrue(v.any { "key $key" in it && "row '$label'" in it }, "$v")
        }
    }

    private val lv = "licence.view"
    private val tv = "tenant-provisioning-request.view"
    private val cr = listOf(lv, tv)

    @Test
    fun `INV-T-16273 the two keys are READ with their pinned rows and exactly the commercial admin SAAS cell`() {
        assertEquals(
            mapOf(lv to "View a licence", tv to "View a tenant provisioning request"),
            RolePermissionsChecker.commercialReadPairs,
        )
        RolePermissionsChecker.commercialReadPairs.forEach { (key, label) ->
            assertTrue(label in RolePermissionsChecker.addedRows)
            assertEquals(label, permission(real, key).row)
            assertEquals(PermissionKind.READ, permission(real, key).kind)
            assertEquals(setOf(CellRow("commercial.admin", saasF, RoleAllowance.PERMITTED)), permission(real, key).cells.toSet())
            assertEquals(1, permission(real, key).cells.size)
        }
        assertEquals(emptyList<String>(), RolePermissionsChecker.check(real))
    }

    @Test
    fun `INV-T-16273 probe release manager as an extra holder is reported`() {
        cr.forEach { assertReported(cellAdd(it, "release.manager", saasF), "role release.manager key $it form SAAS", "extra") }
    }

    @Test
    fun `INV-T-16273 probe the commercial admin SAAS cell removed is reported`() {
        cr.forEach { assertReported(cellRemove(it, "commercial.admin", saasF), "role commercial.admin key $it form SAAS", "missing") }
    }

    @Test
    fun `INV-T-16273 probe the kind WRITE instead of READ is reported`() {
        cr.forEach { assertReported(withKind(it, PermissionKind.WRITE), "key $it") }
    }

    @Test
    fun `INV-T-16273 probe a renamed key is reported`() {
        listOf(lv to "licence.views", tv to "tenant-provisioning-requests.view").forEach { (key, renamed) ->
            assertReported(withPermission(key) { it.copy(key = renamed) }, "key $renamed")
            assertReported(withPermission(key) { it.copy(key = renamed) }, "key $key: missing")
        }
    }

    @Test
    fun `INV-T-16273 the label removed from addedRows while its key stays fails naming the key`() {
        RolePermissionsChecker.commercialReadPairs.forEach { (key, label) ->
            val v = RolePermissionsChecker.check(real, added = RolePermissionsChecker.addedRows - label)
            assertTrue(v.any { "key $key" in it && "row '$label'" in it }, "$v")
        }
    }

    @Test
    fun `INV-T-16273 probe licence manage listed for the label of licence view is reported`() {
        assertReported(withPermission("licence.manage") { it.copy(row = "View a licence") }, "key licence.manage")
    }

    private val gv = "gap-report.view"
    private val ge = "gap-report.export"
    private val ci = "counterparty-import.run"

    @Test
    fun `INV-T-17222 the three keys carry their pinned kind, row and exact cells`() {
        assertEquals(
            mapOf(
                gv to "Gap report and progress",
                ge to "Export the gap report for round-trip remediation",
                ci to "Import counterparties: dry run, then apply",
            ),
            RolePermissionsChecker.gapReportPairs,
        )
        assertTrue(RolePermissionsChecker.gapReportPairs.values.all { it in RolePermissionsChecker.addedRows })
        assertTrue(RolePermissionsChecker.gapReportPairs.values.none { it in RolePermissionsChecker.publishedRows })
        fun cellsOf(vararg roles: String) =
            roles.flatMap { r -> listOf(saasF, privF).map { CellRow(r, it, RoleAllowance.PERMITTED) } }.toSet()
        assertEquals(PermissionKind.READ, permission(real, gv).kind)
        assertEquals(cellsOf("tax.operator", "tax.approver"), permission(real, gv).cells.toSet())
        assertEquals(4, permission(real, gv).cells.size)
        assertEquals(PermissionKind.READ, permission(real, ge).kind)
        assertEquals(cellsOf("tax.operator"), permission(real, ge).cells.toSet())
        assertEquals(2, permission(real, ge).cells.size)
        assertEquals(PermissionKind.WRITE, permission(real, ci).kind)
        assertEquals(cellsOf("tax.operator", "implementation.consultant"), permission(real, ci).cells.toSet())
        assertEquals(4, permission(real, ci).cells.size)
        assertEquals(emptyList<String>(), RolePermissionsChecker.check(real))
    }

    @Test
    fun `INV-T-17222 probe an extra cell, a removed cell or a changed allowance is reported naming the key`() {
        assertReported(cellAdd(ge, "tax.approver", saasF), "role tax.approver key $ge form SAAS", "extra")
        assertReported(cellAdd(gv, "implementation.consultant", privF), "role implementation.consultant key $gv form PRIVATE", "extra")
        assertReported(cellAdd(ci, "tenant.admin", saasF), "role tenant.admin key $ci form SAAS", "extra")
        assertReported(cellRemove(gv, "tax.approver", privF), "role tax.approver key $gv form PRIVATE", "missing")
        assertReported(
            withPermission(gv) { p ->
                p.copy(cells = p.cells.map { if (it.role == "tax.operator" && it.form == saasF) it.copy(allowance = RoleAllowance.WITHIN_SCOPE) else it })
            },
            "role tax.operator key $gv form SAAS",
        )
    }

    @Test
    fun `INV-T-17222 probe a wrong kind is reported naming the key`() {
        assertReported(withKind(ci, PermissionKind.READ), "key $ci")
        assertReported(withKind(gv, PermissionKind.WRITE), "key $gv")
    }

    @Test
    fun `INV-T-17222 probe a renamed key or an extra key under either prefix is reported`() {
        assertReported(withPermission(gv) { it.copy(key = "gap-report.views") }, "key $gv: missing")
        assertReported(withPermission(gv) { it.copy(key = "gap-report.views") }, "key gap-report.views")
        assertReported(withPermission(ci) { it.copy(key = "counterparty-import.runs") }, "key counterparty-import.runs")
        val fourth = permission(real, gv).copy(key = "gap-report.run", row = "Another new row")
        assertReported(real.copy(permissions = real.permissions + fourth), "key gap-report.run")
        val fifth = permission(real, ci).copy(key = "counterparty-import.undo", row = "Another new row")
        assertReported(real.copy(permissions = real.permissions + fifth), "key counterparty-import.undo")
    }

    @Test
    fun `INV-T-17222 probe a label under another key is reported naming the key`() {
        assertReported(withPermission(ge) { it.copy(row = "Gap report and progress") }, "key $ge", "row 'Gap report and progress'")
        val copy = permission(real, ci).copy(key = "import.other")
        assertReported(real.copy(permissions = real.permissions + copy), "key import.other")
    }

    @Test
    fun `INV-T-17222 the label removed from addedRows while its key stays fails naming the key`() {
        RolePermissionsChecker.gapReportPairs.forEach { (key, label) ->
            val v = RolePermissionsChecker.check(real, added = RolePermissionsChecker.addedRows - label)
            assertTrue(v.any { "key $key" in it && "row '$label'" in it }, "$v")
        }
    }

    private val av = "accreditation.view"
    private val atl = "accreditation-task.list"
    private val atv = "accreditation-task.view"

    @Test
    fun `INV-T-15460 the three keys carry their pinned kind, row and the single release manager SAAS cell`() {
        assertEquals(
            mapOf(
                av to "View the accreditation record and its currency",
                atl to "List the re-accreditation assessment tasks",
                atv to "View a re-accreditation assessment task",
            ),
            RolePermissionsChecker.accreditationReadPairs,
        )
        assertTrue(RolePermissionsChecker.accreditationReadPairs.values.all { it in RolePermissionsChecker.addedRows })
        assertTrue(RolePermissionsChecker.accreditationReadPairs.values.none { it in RolePermissionsChecker.publishedRows })
        listOf(av, atl, atv).forEach { k ->
            assertEquals(PermissionKind.READ, permission(real, k).kind, k)
            assertEquals(setOf(CellRow("release.manager", saasF, RoleAllowance.PERMITTED)), permission(real, k).cells.toSet(), k)
            assertEquals(1, permission(real, k).cells.size, k)
        }
        assertEquals(emptyList<String>(), RolePermissionsChecker.check(real))
    }

    @Test
    fun `INV-T-15460 probe an extra role, a removed cell or a wrong kind is reported naming the key`() {
        listOf(av, atl, atv).forEach { k ->
            assertReported(cellAdd(k, "tax.approver", saasF), "role tax.approver key $k form SAAS", "extra")
        }
        assertReported(cellRemove(atl, "release.manager", saasF), "role release.manager key $atl form SAAS", "missing")
        assertReported(withKind(atv, PermissionKind.WRITE), "key $atv")
    }

    @Test
    fun `INV-T-15460 probe a renamed key or a fourth key is reported`() {
        assertReported(withPermission(av) { it.copy(key = "accreditation.read") }, "key $av: missing")
        assertReported(withPermission(av) { it.copy(key = "accreditation.read") }, "key accreditation.read")
        val fourth = permission(real, "usage.view").copy(key = "accreditation-task.delete")
        assertReported(real.copy(permissions = real.permissions + fourth), "key accreditation-task.delete: not one of the keys")
    }

    @Test
    fun `INV-T-15460 probe the row of a view key under the record key is reported naming the key`() {
        assertReported(
            withPermission("accreditation.record") { it.copy(row = "View the accreditation record and its currency") },
            "key accreditation.record: not one of the keys",
        )
    }

    @Test
    fun `INV-T-15460 the label removed from addedRows while its key stays fails naming the key`() {
        RolePermissionsChecker.accreditationReadPairs.forEach { (key, label) ->
            val v = RolePermissionsChecker.check(real, added = RolePermissionsChecker.addedRows - label)
            assertTrue(v.any { "key $key" in it && "row '$label'" in it }, "$v")
        }
    }

    private val cg = "consultant-grant.request"
    private val cgRow = "Request an implementation consultant grant"

    @Test
    fun `INV-T-15797 the key is WRITE with its pinned row and exactly the two cells`() {
        assertEquals(mapOf(cg to cgRow), RolePermissionsChecker.consultantGrantPairs)
        assertEquals(cgRow, permission(real, cg).row)
        assertEquals(PermissionKind.WRITE, permission(real, cg).kind)
        assertEquals(
            setOf(
                CellRow("platform.support", saasF, RoleAllowance.PERMITTED),
                CellRow("deployment.operator", privF, RoleAllowance.PERMITTED),
            ),
            permission(real, cg).cells.toSet(),
        )
        assertEquals(2, permission(real, cg).cells.size)
        assertEquals(emptyList<String>(), RolePermissionsChecker.check(real))
    }

    @Test
    fun `INV-T-15797 probe a renamed key is reported`() {
        assertReported(withPermission(cg) { it.copy(key = "consultant-grant.requests") }, "key $cg: missing")
        assertReported(withPermission(cg) { it.copy(key = "consultant-grant.requests") }, "key consultant-grant.requests")
        assertReported(withPermission(cg) { it.copy(key = "consultant-grants.request") }, "key $cg: missing")
        assertReported(withPermission(cg) { it.copy(key = "consultant-grants.request") }, "key consultant-grants.request")
    }

    @Test
    fun `INV-T-15797 probe a cell in a form the role does not have or an extra role is reported`() {
        assertReported(cellAdd(cg, "platform.support", privF), "role platform.support key $cg form PRIVATE", "extra")
        assertReported(cellAdd(cg, "deployment.operator", saasF), "role deployment.operator key $cg form SAAS", "extra")
        assertReported(cellAdd(cg, "platform.engineer", saasF), "role platform.engineer key $cg form SAAS", "extra")
        assertReported(cellAdd(cg, "tenant.admin", saasF), "role tenant.admin key $cg form SAAS", "extra")
        assertReported(cellAdd(cg, "security.officer", saasF), "role security.officer key $cg form SAAS", "extra")
    }

    @Test
    fun `INV-T-15797 probe each cell removed is reported`() {
        assertReported(cellRemove(cg, "platform.support", saasF), "role platform.support key $cg form SAAS", "missing")
        assertReported(cellRemove(cg, "deployment.operator", privF), "role deployment.operator key $cg form PRIVATE", "missing")
    }

    @Test
    fun `INV-T-15797 probe the kind READ instead of WRITE is reported`() {
        assertReported(withKind(cg, PermissionKind.READ), "key $cg")
    }

    @Test
    fun `INV-T-15797 probe a second new key is reported`() {
        val second = permission(real, cg).copy(key = "consultant-grant.approve")
        assertReported(real.copy(permissions = real.permissions + second), "key consultant-grant.approve")
        val other = permission(real, cg).copy(key = "consultant.other", row = "Another new row")
        assertReported(real.copy(permissions = real.permissions + other), "consultant.other")
    }

    @Test
    fun `INV-T-15797 the label removed from addedRows while its key stays fails naming the key`() {
        RolePermissionsChecker.consultantGrantPairs.forEach { (key, label) ->
            val v = RolePermissionsChecker.check(real, added = RolePermissionsChecker.addedRows - label)
            assertTrue(v.any { "key $key" in it && "row '$label'" in it }, "$v")
        }
    }

    private val ev = "exception.view"
    private val evRow = "View the exception queue"
    private val evHolders = listOf("tenant.admin", "tax.operator", "tax.approver", "agent.operator")

    @Test
    fun `INV-T-16573 the key is READ with its pinned row and exactly the eight cells`() {
        assertEquals(mapOf(ev to evRow), RolePermissionsChecker.exceptionViewPairs)
        assertEquals(evRow, permission(real, ev).row)
        assertEquals(PermissionKind.READ, permission(real, ev).kind)
        val expected =
            evHolders.flatMap { role ->
                val a = if (role == "agent.operator") RoleAllowance.WITHIN_SCOPE else RoleAllowance.PERMITTED
                listOf(CellRow(role, saasF, a), CellRow(role, privF, a))
            }.toSet()
        assertEquals(expected, permission(real, ev).cells.toSet())
        assertEquals(8, permission(real, ev).cells.size)
        assertEquals(expected, permission(real, "masterdata-exception.resolve").cells.toSet())
        assertEquals(emptyList<String>(), RolePermissionsChecker.check(real))
    }

    @Test
    fun `INV-T-16573 probe a renamed key is reported`() {
        assertReported(withPermission(ev) { it.copy(key = "exception.views") }, "key $ev: missing")
        assertReported(withPermission(ev) { it.copy(key = "exception.views") }, "key exception.views")
        assertReported(withPermission(ev) { it.copy(key = "exceptions.view") }, "key $ev: missing")
        assertReported(withPermission(ev) { it.copy(key = "exceptions.view") }, "key exceptions.view")
    }

    @Test
    fun `INV-T-16573 probe the kind WRITE instead of READ is reported`() {
        assertReported(withKind(ev, PermissionKind.WRITE), "key $ev")
    }

    @Test
    fun `INV-T-16573 probe an extra role cell is reported`() {
        assertReported(cellAdd(ev, "finance.viewer", saasF), "role finance.viewer key $ev form SAAS", "extra")
        assertReported(cellAdd(ev, "auditor", privF), "role auditor key $ev form PRIVATE", "extra")
    }

    @Test
    fun `INV-T-16573 probe agent operator PERMITTED instead of WITHIN_SCOPE is reported`() {
        val changed =
            withPermission(ev) { p ->
                p.copy(cells = p.cells.map { if (it.role == "agent.operator") it.copy(allowance = RoleAllowance.PERMITTED) else it })
            }
        assertReported(changed, "role agent.operator key $ev form SAAS")
        assertReported(changed, "role agent.operator key $ev form PRIVATE")
    }

    @Test
    fun `INV-T-16573 probe each cell removed is reported`() {
        evHolders.forEach { role ->
            listOf(saasF to "SAAS", privF to "PRIVATE").forEach { (form, name) ->
                assertReported(cellRemove(ev, role, form), "role $role key $ev form $name", "missing")
            }
        }
    }

    @Test
    fun `INV-T-16573 probe a second new key is reported`() {
        val second = permission(real, ev).copy(key = "exception.view-all")
        assertReported(real.copy(permissions = real.permissions + second), "key exception.view-all")
        val other = permission(real, ev).copy(key = "exception.other", row = "Another new row")
        assertReported(real.copy(permissions = real.permissions + other), "exception.other")
    }

    @Test
    fun `INV-T-16573 the label removed from addedRows while its key stays fails naming the key`() {
        RolePermissionsChecker.exceptionViewPairs.forEach { (key, label) ->
            val v = RolePermissionsChecker.check(real, added = RolePermissionsChecker.addedRows - label)
            assertTrue(v.any { "key $key" in it && "row '$label'" in it }, "$v")
        }
    }

    // INV-T-13568, INV-AC-13562: each probe is its own test against the real tsv.
    private val tsv = File("../../access/schemas/machine-principal-permissions.tsv")
    private val machine = MachinePermissions.parse(tsv)

    private fun machineViolations(
        rows: List<MachinePermissionRow>,
        table: RolePermissionTable = real,
    ) = RolePermissionsChecker.checkMachine(table, rows)

    @Test
    fun `the real tsv reports no machine violation`() {
        assertEquals(emptyList<String>(), machineViolations(machine))
    }

    @Test
    fun `a row of a seventh type fails`() {
        assertTrue(machineViolations(machine + MachinePermissionRow("PAYMENTS", "x.y")).any { "PAYMENTS" in it && "not one of the six" in it })
    }

    @Test
    fun `a type of the six with no row fails`() {
        val v = machineViolations(machine.filterNot { it.principalType == "MONITORING" })
        assertTrue(v.any { "MONITORING" in it && "no row" in it }, "$v")
    }

    @Test
    fun `a permission that already starts with machine dot fails`() {
        assertTrue(machineViolations(machine + MachinePermissionRow("INGEST", "machine.x")).any { "already starts" in it })
    }

    @Test
    fun `a person key starting with machine dot fails`() {
        val bad = real.copy(permissions = real.permissions + real.permissions.first().copy(key = "machine.drain.run"))
        assertTrue(machineViolations(machine, bad).any { "person key machine.drain.run" in it })
    }

    @Test
    fun `a duplicate type and permission row fails`() {
        assertTrue(machineViolations(machine + machine.first()).any { "listed more than once" in it })
    }

    @Test
    fun `a lowercase type fails`() {
        assertTrue(machineViolations(machine + MachinePermissionRow("ingest", "x.y")).any { "'ingest'" in it && "not one of the six" in it })
    }

    @Test
    fun `a type with a trailing space fails`() {
        assertTrue(machineViolations(machine + MachinePermissionRow("INGEST ", "x.y")).any { "'INGEST '" in it })
    }

    @Test
    fun `a padded or empty permission fails`() {
        assertTrue(machineViolations(machine + MachinePermissionRow("INGEST", "x.y ")).any { "empty or padded" in it })
        assertTrue(machineViolations(machine + MachinePermissionRow("INGEST", "")).any { "empty or padded" in it })
    }

    @Test
    fun `a malformed tsv header fails to parse`() {
        val f = File.createTempFile("mach", ".tsv")
        try {
            f.writeText("principalType\tpermission\nINGEST\tx\n")
            org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException::class.java) { MachinePermissions.parse(f) }
        } finally {
            f.delete()
        }
    }
}
