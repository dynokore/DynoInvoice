package com.dynokore.gst.access.bootstrap

import com.dynokore.gst.access.adapters.http.GatewayAuthentication
import com.dynokore.gst.access.application.principal.AuthenticationDecision
import com.dynokore.gst.access.application.principal.PrincipalAuthenticator
import com.dynokore.gst.access.application.principal.PrincipalTenantResolver
import com.dynokore.gst.access.contract.model.AuthenticatePrincipalResponse.Decision
import com.dynokore.gst.access.domain.principal.CredentialMethod
import com.dynokore.platform.tenancykernel.PlatformTenantBinding
import com.dynokore.platform.tenancykernel.TenantContext
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.io.File
import java.sql.Connection
import java.sql.SQLException
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/**
 * The gateway call `authenticatePrincipal` carries no tenant (INV-Q-8982 A; ARC 4.3 step 1a): the tenant of the
 * presented reference is resolved by `access.resolve_principal_tenant`, bound, and the use case runs. The catalogue
 * rows of the function and of the policies are asserted on the live database; each probe breaks one of them inside a
 * transaction that is rolled back.
 */
class GatewayAuthenticationTest {
    private val migration =
        File("../access-adapters/src/main/resources/db/migration/V202610250900__resolve_principal_tenant.sql")

    @Test
    fun `principals of two tenants are each authenticated with no tenant bound before the call`() =
        PrincipalKit.run { first ->
            val second = PrincipalKit(first.context)
            val key1 = first.newKey("erp-1")
            val key2 = second.newKey("erp-2")
            val p1 = first.create("INGEST", method = PrincipalKit.JWT, key = key1)
            val p2 = second.create("INGEST", method = PrincipalKit.JWT, key = key2)
            val tenants = first.context.getBean(TenantContext::class.java)
            assertThat(runCatching { tenants.current() }.isFailure).isTrue()
            val d1 = first.authenticate(p1.principalRef, PrincipalKit.AUTH_JWT, first.proof(key1, p1.principalRef))
            val d2 = second.authenticate(p2.principalRef, PrincipalKit.AUTH_JWT, second.proof(key2, p2.principalRef))
            assertThat(d1.decision).isEqualTo(Decision.AUTHENTICATED)
            assertThat(d1.principalRef).isEqualTo(p1.principalRef)
            assertThat(d2.decision).isEqualTo(Decision.AUTHENTICATED)
            assertThat(d2.principalRef).isEqualTo(p2.principalRef)
        }

    @Test
    fun `an unknown reference answers 200 REFUSED UNKNOWN and the use case runs 0 times`() =
        PrincipalKit.run { kit ->
            val runs = AtomicInteger()
            val counting =
                PrincipalAuthenticator { ref, method, proof ->
                    runs.incrementAndGet()
                    AuthenticationDecision.Refused("$ref$method$proof")
                }
            val adapter = adapter(kit, counting)
            val decision = adapter.authenticate("PRN-99999999", CredentialMethod.PRIVATE_KEY_JWT, "proof")
            assertThat(decision).isEqualTo(AuthenticationDecision.Refused("UNKNOWN"))
            assertThat(runs.get()).isZero()
            val body = kit.authenticate("PRN-99999999", PrincipalKit.AUTH_JWT, "proof")
            assertThat(body.decision).isEqualTo(Decision.REFUSED)
            assertThat(body.reasonCode).isEqualTo("UNKNOWN")
        }

    @Test
    fun `the proof of the principal of one tenant presented with the reference of another tenant is refused`() =
        PrincipalKit.run { first ->
            val second = PrincipalKit(first.context)
            val key1 = first.newKey("erp-1")
            val key2 = second.newKey("erp-2")
            val p1 = first.create("INGEST", method = PrincipalKit.JWT, key = key1)
            val p2 = second.create("INGEST", method = PrincipalKit.JWT, key = key2)
            val body = first.authenticate(p2.principalRef, PrincipalKit.AUTH_JWT, first.proof(key1, p1.principalRef))
            assertThat(body.decision).isEqualTo(Decision.REFUSED)
        }

    @Test
    fun `a second row with an existing principal_ref under another tenant is refused by the unique index`() =
        PrincipalKit.run { kit ->
            val p = kit.create("INGEST")
            val failure =
                runCatching {
                    BoundaryFixture.asSuperuser { connection ->
                        connection.createStatement().use {
                            it.execute(
                                "INSERT INTO access.service_principal (id, tenant_id, principal_ref, principal_type," +
                                    " scope, status, created_at, created_by, updated_at, updated_by, version)" +
                                    " VALUES ('${UUID.randomUUID()}', '${UUID.randomUUID()}', '${p.principalRef}'," +
                                    " 'INGEST', 'installation', 'ACTIVE', now(), 't', now(), 't', 0)",
                            )
                        }
                    }
                }.exceptionOrNull()
            assertThat(failure).isInstanceOf(SQLException::class.java)
            assertThat((failure as SQLException).sqlState).isEqualTo("23505")
        }

    /** The findings of the live catalogue: what the ruling INV-Q-8982 A requires of the function and the policies. */
    private fun findings(connection: Connection): List<String> =
        buildList {
            connection
                .createStatement()
                .executeQuery(
                    "SELECT p.prosecdef, p.proconfig::text, pg_get_userbyid(p.proowner)," +
                        " has_function_privilege('access_runtime', p.oid, 'EXECUTE')," +
                        " has_function_privilege('public', p.oid, 'EXECUTE')" +
                        " FROM pg_proc p JOIN pg_namespace n ON n.oid = p.pronamespace" +
                        " WHERE n.nspname = 'access' AND p.proname = 'resolve_principal_tenant'",
                ).use { rows ->
                    if (!rows.next()) {
                        add("the function does not exist")
                        return@use
                    }
                    if (!rows.getBoolean(1)) add("not SECURITY DEFINER")
                    if (rows.getString(2)?.contains("search_path=") != true) add("no search_path")
                    if (rows.getString(3) != "access_sys") add("not owned by access_sys")
                    if (!rows.getBoolean(4)) add("access_runtime cannot execute")
                    if (rows.getBoolean(5)) add("public can execute")
                }
            connection
                .createStatement()
                .executeQuery(
                    "SELECT policyname, roles::text FROM pg_policies" +
                        " WHERE schemaname = 'access' AND tablename = 'service_principal'",
                ).use { rows ->
                    val reaching = mutableListOf<String>()
                    while (rows.next()) {
                        val roles = rows.getString(2).trim('{', '}').split(',')
                        if ("access_runtime" in roles || "public" in roles) reaching += rows.getString(1)
                        if (rows.getString(1) != "tenant_isolation" && roles != listOf("access_sys")) {
                            add("a policy outside tenant_isolation lists ${rows.getString(2)}")
                        }
                    }
                    if (reaching !=
                        listOf("tenant_isolation")
                    ) {
                        add("policies reaching access_runtime or public: $reaching")
                    }
                }
        }

    private fun probe(statement: String): List<String> =
        BoundaryFixture.asSuperuser { connection ->
            connection.autoCommit = false
            try {
                connection.createStatement().use { it.execute(statement) }
                findings(connection)
            } finally {
                connection.rollback()
            }
        }

    @Test
    fun `the function, its grants and the policies are as ruled and each probe breaks one rule`() {
        assertThat(BoundaryFixture.asSuperuser { findings(it) }).isEmpty()
        assertThat(probe("GRANT EXECUTE ON FUNCTION access.resolve_principal_tenant(text) TO PUBLIC"))
            .contains("public can execute")
        assertThat(
            probe(
                "CREATE POLICY wide ON access.service_principal FOR SELECT TO access_runtime USING (true)",
            ),
        ).anyMatch { it.startsWith("policies reaching access_runtime or public") }
        assertThat(probe("ALTER FUNCTION access.resolve_principal_tenant(text) RESET search_path"))
            .contains("no search_path")
        assertThat(probe("ALTER FUNCTION access.resolve_principal_tenant(text) SECURITY INVOKER"))
            .contains("not SECURITY DEFINER")
    }

    @Test
    fun `the migration grants the two columns to access_sys and one policy for access_sys only`() {
        val sql =
            migration
                .readText()
                .lines()
                .filterNot { it.trimStart().startsWith("--") }
                .joinToString("\n")
        assertThat(sql).contains("GRANT SELECT (principal_ref, tenant_id) ON access.service_principal TO access_sys")
        assertThat(Regex("""(?i)CREATE\s+POLICY""").findAll(sql).count()).isEqualTo(1)
        assertThat(sql).contains("FOR SELECT TO access_sys")
        assertThat(Regex("""(?i)GRANT\s[^;]*\sTO\s+PUBLIC""").containsMatchIn(sql)).isFalse()
    }

    /** The probe: an adapter that reads the principal under the platform binding without the function. */
    @Test
    fun `an adapter that reads the principal under the platform binding without the function refuses (a)`() =
        PrincipalKit.run { first ->
            val key = first.newKey("erp-1")
            val p = first.create("INGEST", method = PrincipalKit.JWT, key = key)
            val platform = first.context.getBean(PlatformTenantBinding::class.java)
            val direct =
                PrincipalAuthenticator { ref, method, proof ->
                    platform.bindPlatform(ACTOR) {
                        first.context
                            .getBean(
                                com.dynokore.gst.access.application.principal.AuthenticatePrincipalUseCase::class.java,
                            ).handle(ref, method, proof)
                    }
                }
            val decision =
                direct.handle(
                    p.principalRef,
                    CredentialMethod.PRIVATE_KEY_JWT,
                    first.proof(key, p.principalRef),
                )
            assertThat(decision).isInstanceOf(AuthenticationDecision.Refused::class.java)
        }

    private fun adapter(
        kit: PrincipalKit,
        authenticator: PrincipalAuthenticator,
    ) = GatewayAuthentication(
        kit.context.getBean(PrincipalTenantResolver::class.java),
        kit.context.getBean(PlatformTenantBinding::class.java),
        kit.tenants,
        authenticator,
        ACTOR,
    )

    private companion object {
        const val ACTOR = "svc:api-gateway"
    }
}
