package com.dynokore.gst.masterdata.adapters.clientsignatory

import com.dynokore.gst.catalogue.DeploymentForm
import com.dynokore.gst.catalogue.OperationRoles
import com.dynokore.gst.catalogue.RoleAllowance
import com.dynokore.gst.masterdata.adapters.permission.ReplicaTenantPermission
import com.dynokore.gst.masterdata.application.clientsignatory.ClientSignatories
import com.dynokore.gst.masterdata.application.clientsignatory.ClientSignatory
import com.dynokore.gst.masterdata.application.clientsignatory.LinkStatus
import com.dynokore.gst.masterdata.application.clientsignatory.RecordClientSignatory
import com.dynokore.gst.masterdata.application.clientsignatory.RecordClientSignatoryCommand
import com.dynokore.gst.masterdata.application.clientsignatory.RecordClientSignatoryOutcome
import com.dynokore.gst.masterdata.application.request.LinkValidityLimits
import com.dynokore.platform.serviceruntime.replica.AuthorityChanged
import com.dynokore.platform.serviceruntime.replica.AuthorityRecord
import com.dynokore.platform.serviceruntime.replica.AuthorityReplica
import com.dynokore.platform.serviceruntime.replica.CellAllowance
import com.dynokore.platform.serviceruntime.replica.GrantScope
import com.dynokore.platform.serviceruntime.replica.OperationRoleMap
import com.dynokore.platform.serviceruntime.replica.RoleGrant
import com.dynokore.platform.serviceruntime.replica.RoleGrantState
import com.dynokore.platform.tenancykernel.TenantContext
import com.dynokore.platform.tenancykernel.TenantId
import com.dynokore.platform.tenancykernel.TransactionRunner
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

/**
 * INV-Q-17446 A, INV-Q-10027: `recordClientSignatory` is decided on the key `client-signatory.record` by the real
 * [AuthorityReplica] over the roles map of the pinned catalogue, which this test reads (it names no role).
 */
private const val GRANTED = "2026-11-16T09:00:00Z"

class ClientSignatoryPermissionTest {
    private val key = RecordClientSignatory.PERMISSION_KEY
    private val tenant = TenantId(UUID.fromString("00000000-0000-0000-0000-0000000000b1"))
    private val now = Instant.parse("2026-11-16T10:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)
    private val tenants =
        object : TenantContext {
            override fun current() = tenant

            override fun currentActor(): String? = "test"

            override fun <T> bind(
                tenant: TenantId,
                actor: String,
                block: () -> T,
            ): T = block()
        }

    private class Rig(
        form: DeploymentForm,
        val clock: Clock,
        val tenant: TenantId,
        tenants: TenantContext,
    ) {
        val replica =
            AuthorityReplica(
                clock,
                OperationRoleMap { k ->
                    OperationRoles.rolesFor(form, k).mapValues { CellAllowance.valueOf(it.value.name) }
                },
            ).also { it.onSnapshotComplete(0) }
        val records = linkedMapOf<String, ClientSignatory>()
        val events = mutableListOf<String>()
        private var version = 0L
        private val tx =
            object : TransactionRunner {
                override fun <T> inTransaction(block: () -> T): T = block()
            }
        private val store =
            object : ClientSignatories {
                override fun findByClientAndEmail(
                    clientRef: String,
                    email: String,
                ) = records.values.firstOrNull { it.clientRef == clientRef && it.email == email }

                override fun findByRequestEventId(requestEventId: String) = null

                override fun insert(signatory: ClientSignatory) {
                    records[signatory.signatoryRef] = signatory
                }

                override fun saveRequestEventId(
                    signatoryRef: String,
                    requestEventId: String,
                ) = Unit

                override fun saveLinkStatus(
                    signatoryRef: String,
                    status: LinkStatus,
                ) = Unit

                override fun nextRef() = "SIG-${records.size + 1}"
            }
        val useCase =
            RecordClientSignatory(
                tx,
                store,
                ReplicaTenantPermission(replica, tenants),
                object : LinkValidityLimits {
                    override val defaultDays = 7L
                    override val maxDays = 30L
                },
                { null },
                clock,
                { request ->
                    events += request.sourceRecordId
                    "evt-${events.size}"
                },
            )

        fun hold(
            person: String,
            role: String,
        ) {
            replica.accept(
                AuthorityChanged(
                    tenant,
                    person,
                    ++version,
                    AuthorityRecord.RoleGrantRecord(
                        "g$version",
                        RoleGrantState.ACTIVE,
                        RoleGrant(role, GrantScope.TENANT, tenant.toString(), Instant.parse(GRANTED), null),
                    ),
                ),
            )
            replica.observeHead(version)
        }

        fun record(person: String) =
            useCase.handle(
                RecordClientSignatoryCommand(person, "CLI-204", "Tan", "wm.tan@example.com.sg", null, "CNS-77"),
            )
    }

    private val forms = listOf(DeploymentForm.SAAS, DeploymentForm.PRIVATE)

    private fun rig(form: DeploymentForm) = Rig(form, clock, tenant, tenants)

    /** A role the catalogue lists for [otherKey] and not for the recording key. */
    private fun roleOfOtherKey(
        form: DeploymentForm,
        otherKey: String,
    ): String =
        OperationRoles
            .rolesFor(form, otherKey)
            .keys
            .filter { it !in OperationRoles.rolesFor(form, key).keys }
            .sorted()
            .first()

    @Test
    fun `INV-AC-17444 a permitted caller is recorded and a caller with no decision gets nothing stored`() {
        forms.forEach { form ->
            val rig = rig(form)
            val recorder = OperationRoles.rolesFor(form, key).keys.single()
            rig.hold("identity:P-OK", recorder)
            assertTrue(rig.record("identity:P-OK") is RecordClientSignatoryOutcome.Recorded, "$form")
            val denied = rig(form)
            assertEquals(RecordClientSignatoryOutcome.Forbidden, denied.record("identity:P-NONE"), "$form")
            assertTrue(denied.records.isEmpty() && denied.events.isEmpty(), "$form")
        }
    }

    @Test
    fun `INV-AC-17444 a caller holding only the key of another operation is forbidden`() {
        forms.forEach { form ->
            listOf("consent.grant", "exception.defer").forEach { other ->
                val rig = rig(form)
                rig.hold("identity:P-OTHER", roleOfOtherKey(form, other))
                assertEquals(RecordClientSignatoryOutcome.Forbidden, rig.record("identity:P-OTHER"), "$form $other")
                assertTrue(rig.records.isEmpty() && rig.events.isEmpty(), "$form $other")
            }
        }
    }

    @Test
    fun `INV-AC-17444 a caller holding only a look-alike key is forbidden`() {
        forms.forEach { form ->
            listOf("client-signatory.records", "client-signatories.record").forEach { lookAlike ->
                assertTrue(OperationRoles.rolesFor(form, lookAlike).isEmpty(), "$form $lookAlike is no catalogue key")
                val rig = rig(form)
                rig.hold("identity:P-LIKE", "role-of-$lookAlike")
                assertEquals(RecordClientSignatoryOutcome.Forbidden, rig.record("identity:P-LIKE"), "$form $lookAlike")
                assertTrue(rig.records.isEmpty() && rig.events.isEmpty(), "$form $lookAlike")
            }
        }
    }

    @Test
    fun `INV-AC-17444 the pinned catalogue holds the key for exactly one role WITHIN_SCOPE in SAAS and PRIVATE`() {
        forms.forEach { form ->
            val cells = OperationRoles.rolesFor(form, key)
            assertEquals(1, cells.size, "$form")
            assertEquals(setOf(RoleAllowance.WITHIN_SCOPE), cells.values.toSet(), "$form")
            assertEquals(mapOf(cells.keys.single() to RoleAllowance.WITHIN_SCOPE), cells, "$form")
        }
    }

    private fun minor(version: String): Pair<Int, Int> = version.split('.').let { it[0].toInt() to it[1].toInt() }

    @Test
    fun `INV-AC-17444 the gst-catalogue pin is at or above the minor step of the catalogue task`() {
        val catalogue = Path.of("").toAbsolutePath().resolveSibling("gradle/libs.versions.toml")
        val pinned =
            Files
                .readAllLines(catalogue)
                .firstNotNullOf { Regex("""^gstCatalogue\s*=\s*"(\d+\.\d+\.\d+)"""").find(it)?.groupValues?.get(1) }
        val (major, minorStep) = minor(pinned)
        assertTrue(major > 3 || (major == 3 && minorStep >= 5), "gstCatalogue $pinned is below 3.5.0")
        // an older pin fails closed: this key is absent from every catalogue before 3.5.0
        assertTrue(OperationRoles.rolesFor(DeploymentForm.SAAS, key).isNotEmpty())
    }
}
