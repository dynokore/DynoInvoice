package com.dynokore.gst.access.application

import com.dynokore.gst.access.application.grant.AuthorityChangePublisher
import com.dynokore.gst.access.application.grant.EntityDirectory
import com.dynokore.gst.access.application.grant.GrantAudit
import com.dynokore.gst.access.application.grant.GrantRepository
import com.dynokore.gst.access.application.grant.GrantRoleCommand
import com.dynokore.gst.access.application.grant.GrantRoleEffects
import com.dynokore.gst.access.application.grant.GrantRoleOutcome
import com.dynokore.gst.access.application.grant.GrantRoleRules
import com.dynokore.gst.access.application.grant.GrantRoleUseCase
import com.dynokore.gst.access.application.idp.DirectoryGroupMembers
import com.dynokore.gst.access.application.idp.GroupGrantReconciler
import com.dynokore.gst.access.application.idp.IdpGroupCollaborators
import com.dynokore.gst.access.application.idp.IdpGroupMapping
import com.dynokore.gst.access.application.idp.IdpGroupMappings
import com.dynokore.gst.access.application.idp.IdpProvisioningEffects
import com.dynokore.gst.access.application.idp.MapIdpGroupCommand
import com.dynokore.gst.access.application.idp.MapIdpGroupOutcome
import com.dynokore.gst.access.application.idp.MapIdpGroupUseCase
import com.dynokore.gst.access.application.idp.MappedGrants
import com.dynokore.gst.access.application.separation.SoleOperatorAcks
import com.dynokore.gst.access.application.separation.SoleOperatorScopePolicy
import com.dynokore.gst.access.domain.grant.GranteeKind
import com.dynokore.gst.access.domain.grant.IdentityRef
import com.dynokore.gst.access.domain.grant.Scope
import com.dynokore.gst.access.domain.grant.TenantRef
import com.dynokore.gst.access.domain.grant.ValidityWindow
import com.dynokore.gst.access.domain.role.PermissionMatrix
import com.dynokore.gst.access.domain.role.Role
import com.dynokore.gst.access.domain.role.RoleCatalogue
import com.dynokore.platform.tenancykernel.TransactionRunner
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.io.path.name
import kotlin.io.path.readText
import kotlin.io.path.writeText

/** INV-AC-12018 (INV-Q-9069 A, INV-Q-12007, INV-Q-12011, INV-Q-12013): the Operator roles are refused before any write. */
class OperatorRoleRefusalTest {
    private class Marker : RuntimeException("the transaction runner was reached")

    private class Called(
        what: String,
    ) : AssertionError("a collaborator was called: $what")

    private val refused =
        listOf(
            "platform.support",
            "platform.engineer",
            "release.manager",
            "commercial.admin",
            "regulatory.author",
            "regulatory.approver",
        )
    private val tenant = TenantRef("T-1")
    private val scope = Scope.Tenant(tenant)
    private val tenantScope: Scope = scope
    private val matrix = PermissionMatrix(emptyList(), emptyMap())
    private val allRoles = RoleCatalogue.ALL

    /** A collaborator that fails the test on any call. */
    private inline fun <reified T : Any> failing(): T =
        Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, method, _ ->
            throw Called("${T::class.simpleName}.${method.name}")
        } as T

    private val markerTx =
        object : TransactionRunner {
            override fun <T> inTransaction(block: () -> T): T = throw Marker()
        }

    private val reachedRepository: GrantRepository =
        Proxy.newProxyInstance(
            GrantRepository::class.java.classLoader,
            arrayOf(GrantRepository::class.java),
        ) { _, _, _ ->
            throw Marker()
        } as GrantRepository

    private fun grantUseCase() =
        GrantRoleUseCase(
            markerTx,
            failing<GrantRepository>(),
            failing<EntityDirectory>(),
            matrix,
            GrantRoleEffects(failing<AuthorityChangePublisher>(), failing<GrantAudit>()),
            GrantRoleRules(allRoles, failing<SoleOperatorAcks>(), failing<SoleOperatorScopePolicy>()),
        )

    private fun mapEffects(): IdpProvisioningEffects =
        IdpProvisioningEffects(
            failing<AuthorityChangePublisher>(),
            failing<GrantAudit>(),
            allRoles,
            MappedGrants { emptyList() },
        )

    private fun mapUseCase() =
        MapIdpGroupUseCase(
            IdpGroupCollaborators(
                markerTx,
                failing<IdpGroupMappings>(),
                failing<DirectoryGroupMembers>(),
                Clock.fixed(Instant.parse("2026-10-28T00:00:00Z"), ZoneOffset.UTC),
            ),
            failing<GrantRepository>(),
            failing<EntityDirectory>(),
            matrix,
            mapEffects(),
        )

    private fun grantCommand(
        wire: String,
        withScope: Boolean = true,
    ) = GrantRoleCommand(
        tenant,
        "IDN-ADM",
        IdentityRef("IDN-1"),
        GranteeKind.STAFF,
        wire,
        if (withScope) tenantScope else null,
        ValidityWindow(Instant.parse("2026-10-28T00:00:00Z"), null),
    )

    private fun mapCommand(
        wire: String,
        withScope: Boolean = true,
        validTo: LocalDate? = null,
    ) = MapIdpGroupCommand(
        "IDN-ADM",
        tenant,
        "grp",
        wire,
        if (withScope) tenantScope else null,
        LocalDate.parse("2026-10-28"),
        validTo,
    )

    private val others = Role.entries.map { it.wire } - refused.toSet()

    @Test
    fun `INV-AC-12018 the outcome types hold exactly the named members`() {
        fun names(type: Class<*>) = type.permittedSubclasses.map { it.simpleName }.toSet()
        assertEquals(
            setOf(
                "Granted",
                "ScopeMissing",
                "SoleOperatorAckRequired",
                "SecondHolderRefused",
                "UnknownRole",
                "OperatorRoleRefused",
            ),
            names(GrantRoleOutcome::class.java),
        )
        assertEquals(
            setOf("Mapped", "UnknownRole", "ScopeMissing", "WindowInvalid", "OperatorRoleRefused"),
            names(MapIdpGroupOutcome::class.java),
        )
    }

    @Test
    fun `INV-AC-12018 the catalogue splits into six refused and thirteen other wire names`() {
        assertEquals(19, Role.entries.size)
        assertEquals(13, others.size)
    }

    @Test
    fun `INV-AC-12018 grantRole refuses each of the six roles and calls no collaborator`() {
        refused.forEach {
            assertEquals(
                GrantRoleOutcome.OperatorRoleRefused,
                grantUseCase().handle(grantCommand(it)),
                it,
            )
        }
    }

    @Test
    fun `INV-AC-12018 grantRole reaches the transaction runner for each of the other thirteen roles`() {
        others.forEach { assertThrows(Marker::class.java, { grantUseCase().handle(grantCommand(it)) }, it) }
    }

    @Test
    fun `INV-AC-12018 grantRole returns UnknownRole for look-alikes and scope missing for a refused role`() {
        listOf("Platform.Support", "platform.support ", "no.such.role").forEach {
            assertEquals(GrantRoleOutcome.UnknownRole(it), grantUseCase().handle(grantCommand(it)), it)
        }
        refused.forEach {
            assertEquals(GrantRoleOutcome.ScopeMissing, grantUseCase().handle(grantCommand(it, withScope = false)), it)
        }
    }

    @Test
    fun `INV-AC-12018 mapIdpGroup refuses each of the six roles and calls no collaborator`() {
        refused.forEach {
            assertEquals(
                MapIdpGroupOutcome.OperatorRoleRefused,
                mapUseCase().handle(mapCommand(it)),
                it,
            )
        }
    }

    @Test
    fun `INV-AC-12018 mapIdpGroup refuses a refused role before the window test`() {
        val invalidEnd = LocalDate.parse("2026-10-01")
        refused.forEach {
            assertEquals(
                MapIdpGroupOutcome.OperatorRoleRefused,
                mapUseCase().handle(mapCommand(it, validTo = invalidEnd)),
                it,
            )
        }
        assertEquals(
            MapIdpGroupOutcome.WindowInvalid,
            mapUseCase().handle(mapCommand("tenant.admin", validTo = invalidEnd)),
        )
    }

    @Test
    fun `INV-AC-12018 mapIdpGroup reaches the transaction runner for each of the other thirteen roles`() {
        others.forEach { assertThrows(Marker::class.java, { mapUseCase().handle(mapCommand(it)) }, it) }
    }

    @Test
    fun `INV-AC-12018 mapIdpGroup returns UnknownRole for look-alikes and scope missing for a refused role`() {
        listOf("Platform.Support", "platform.support ", "no.such.role").forEach {
            assertEquals(MapIdpGroupOutcome.UnknownRole(it), mapUseCase().handle(mapCommand(it)), it)
        }
        refused.forEach {
            assertEquals(MapIdpGroupOutcome.ScopeMissing, mapUseCase().handle(mapCommand(it, withScope = false)), it)
        }
    }

    private fun mappingOf(role: Role) =
        IdpGroupMapping("MAP-1", "grp", role, tenantScope, LocalDate.parse("2026-10-28"), null)

    private fun reconciler(grants: GrantRepository) =
        GroupGrantReconciler(grants, failing<EntityDirectory>(), matrix, mapEffects())

    @Test
    fun `INV-AC-12018 the directory sync writes no grant for a mapping of an Operator role`() {
        Role.entries.filter { it.wire in refused }.forEach {
            reconciler(
                failing<GrantRepository>(),
            ).reconcile("IDN-ADM", tenant, mappingOf(it), setOf(IdentityRef("IDN-1")))
        }
    }

    @Test
    fun `INV-AC-12018 the directory sync reaches the repository for a mapping of any other role`() {
        Role.entries.filter { it.wire !in refused }.forEach {
            assertThrows(
                Marker::class.java,
                {
                    reconciler(
                        reachedRepository,
                    ).reconcile("IDN-ADM", tenant, mappingOf(it), setOf(IdentityRef("IDN-1")))
                },
                it.wire,
            )
        }
    }

    // the single gate: which main-source files parse a command role, and how many gate calls they hold

    private val parseCall = "Role.ofWire(command.roleWire)"
    private val gateCall = "TenantGrantableRoles.refuses("

    private fun gateFindings(dir: Path): List<String> {
        val files = Files.walk(dir).use { s -> s.filter { it.toString().endsWith(".kt") }.toList() }
        val parsing = files.filter { parseCall in it.readText() }.map { it.name }.toSet()
        val findings = mutableListOf<String>()
        if (parsing !=
            setOf("GrantRoleUseCase.kt", "IdpGroups.kt")
        ) {
            findings += "files that parse a command role: $parsing"
        }
        mapOf("GrantRoleUseCase.kt" to 1, "IdpGroups.kt" to 2).forEach { (name, expected) ->
            val file = files.singleOrNull { it.name == name }
            val count = file?.readText()?.windowed(gateCall.length)?.count { it == gateCall } ?: 0
            if (count != expected) findings += "$name holds $count gate calls, expected $expected"
        }
        return findings
    }

    private fun copyOfMain(into: Path): Path {
        val source = Path.of("src/main/kotlin")
        Files.walk(source).use { s ->
            s.filter { Files.isRegularFile(it) }.forEach {
                val target = into.resolve(source.relativize(it).toString())
                Files.createDirectories(target.parent)
                Files.copy(it, target)
            }
        }
        return into
    }

    private fun Path.single(name: String) = Files.walk(this).use { s -> s.filter { it.name == name }.toList().single() }

    @Test
    fun `INV-AC-12018 the gate holds on the real main sources`(
        @TempDir dir: Path,
    ) {
        assertEquals(emptyList<String>(), gateFindings(copyOfMain(dir)))
    }

    @Test
    fun `INV-AC-12018 the gate fails for a third file that parses a command role`(
        @TempDir dir: Path,
    ) {
        val copy = copyOfMain(dir)
        copy.resolve("InviteUserUseCase.kt").writeText("val role = Role.ofWire(command.roleWire)\n")
        assertEquals(1, gateFindings(copy).size)
    }

    @Test
    fun `INV-AC-12018 the gate fails when a call is removed and ignores the landing screens`(
        @TempDir dir: Path,
    ) {
        val copy = copyOfMain(dir)
        assertEquals(
            "Role.ofWire(roleWire)",
            Regex("Role\\.ofWire\\(roleWire\\)").find(copy.single("LandingScreens.kt").readText())?.value,
        )
        val idp = copy.single("IdpGroups.kt")
        val text = idp.readText()
        idp.writeText(text.replaceFirst("TenantGrantableRoles.refuses(role)", "false"))
        assertEquals(1, gateFindings(copy).size)
        idp.writeText(text.replace("TenantGrantableRoles.refuses(", "other.refuses("))
        assertEquals(1, gateFindings(copy).size)
    }

    @Test
    fun `INV-AC-12018 the gate fails when the call leaves GrantRoleUseCase`(
        @TempDir dir: Path,
    ) {
        val copy = copyOfMain(dir)
        val file = copy.single("GrantRoleUseCase.kt")
        file.writeText(file.readText().replace("TenantGrantableRoles.refuses(", "other.refuses("))
        assertEquals(1, gateFindings(copy).size)
    }

    @Test
    fun `INV-AC-12018 the unreachable handle exception type is not swallowed`() {
        // guards the helper: a Called error from a failing collaborator must surface, not be caught as a marker
        val error =
            assertThrows(Called::class.java) {
                try {
                    failing<GrantAudit>().record(
                        com.dynokore.gst.access.application.grant
                            .GrantAuditEntry("a", "b", "c", null, null),
                    )
                } catch (e: InvocationTargetException) {
                    throw e.targetException
                }
            }
        assertEquals("a collaborator was called: GrantAudit.record", error.message)
    }
}
