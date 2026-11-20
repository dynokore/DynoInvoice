package com.dynokore.gst.consoleBff.bootstrap

import com.dynokore.gst.catalogue.VariationPoint
import com.dynokore.gst.consoleBff.adapters.ConsoleDb
import com.dynokore.gst.consoleBff.adapters.session.JooqSessionStore
import com.dynokore.gst.consoleBff.adapters.session.PolicyTenantSessionPolicy
import com.dynokore.gst.consoleBff.application.session.PrincipalStanding
import com.dynokore.gst.consoleBff.application.session.RefusalReason
import com.dynokore.gst.consoleBff.application.session.SessionResolution
import com.dynokore.gst.consoleBff.application.session.SessionResolver
import com.dynokore.gst.consoleBff.application.session.Standing
import com.dynokore.gst.consoleBff.application.session.TenantSessionPolicy
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.yaml.snakeyaml.Yaml
import java.nio.file.Path
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset

/** The per-tenant console session timeouts wiring (VP-84, INV-REQ-25100). */
class SessionTenantTimeoutsTest {
    @Test
    fun `INV-AC-25103 the context holds exactly one SessionResolver and one TenantSessionPolicy of the policy class`(
        @TempDir dir: Path,
    ) {
        ConsoleContext.offline(dir).run { context ->
            context.startupFailure shouldBe null
            context.getBeansOfType(SessionResolver::class.java).size shouldBe 1
            val policies = context.getBeansOfType(TenantSessionPolicy::class.java)
            policies.size shouldBe 1
            (policies.values.single() is PolicyTenantSessionPolicy) shouldBe true
        }
    }

    private val tenantA = "11111111-1111-1111-1111-11111111aaaa"
    private val tenantB = "22222222-2222-2222-2222-22222222bbbb"
    private val opened = Instant.parse("2026-06-01T08:00:00Z")

    private fun prepare() {
        ConsoleDb.superuser().use { c ->
            c.createStatement().execute("truncate console_bff.console_session")
            c.createStatement().execute("truncate console_bff.config_setting")
            // the stored setting row of the fixture: tenant A shortens to idle 10 minutes and absolute 2 hours
            c.createStatement().execute(
                "insert into console_bff.config_setting (id, tenant_id, vp_key, scope_level, scope_id, option_keys, " +
                    "option_values, source, effective_from, set_by, reason, recorded_at) values " +
                    "(gen_random_uuid(), '$tenantA', 'VP-84', 'TENANT', '$tenantA', array['SHORTER'], " +
                    "'{\"SHORTER\":{\"idle\":10,\"absolute\":2}}'::jsonb, 'OVERRIDE', '2026-01-01', 'fixture', 'test', now())",
            )
        }
    }

    private fun verdict(
        context: org.springframework.context.ApplicationContext,
        tenant: String,
        after: Duration,
    ): SessionResolution {
        val store = context.getBean(JooqSessionStore::class.java)
        val cookie = store.create(tenant, "p1", 1, opened).cookieValue
        val resolver =
            SessionResolver(
                context.getBean(TenantSessionPolicy::class.java),
                store,
                object : PrincipalStanding {
                    override fun of(
                        tenantId: String,
                        principalId: String,
                        authorityVersionAtOpen: Long,
                    ) = Standing.ACTIVE
                },
                Clock.fixed(opened.plus(after), ZoneOffset.UTC),
            )
        return resolver.resolve(cookie)
    }

    @Test
    fun `INV-AC-25103 with the real PolicyReader a tenant with no setting is accepted at 29 and refused at 31 minutes idle`(
        @TempDir dir: Path,
    ) {
        prepare()
        ConsoleContext.online(dir).run { context ->
            context.startupFailure shouldBe null
            val rd = context.getBean(com.dynokore.platform.configresolver.PolicyReader::class.java)
            val tc = context.getBean(com.dynokore.platform.tenancykernel.TenantContext::class.java)
            val tr = context.getBean(com.dynokore.platform.tenancykernel.TransactionRunner::class.java)
            val tid = com.dynokore.platform.tenancykernel.TenantId.of(tenantA)
            (verdict(context, tenantB, Duration.ofMinutes(29)) is SessionResolution.Resolved) shouldBe true
            verdict(context, tenantB, Duration.ofMinutes(31)) shouldBe SessionResolution.Refused(RefusalReason.EXPIRED)
        }
    }

    @Test
    fun `INV-AC-25103 the shipped preset equals the VP-84 default of the pinned catalogue`() {
        val record = VariationPoint.entries.single { it.id == "VP-84" }.optionSpecs!!.single { it.key == "SHORTER" }.typedValue!!
        val idle = record.fields["idle"]!!.default!!.number!!
        val absolute = record.fields["absolute"]!!.default!!.number!!
        val yaml = ClassLoader.getSystemResourceAsStream("application.yaml")!!.use { Yaml().load<Map<String, Any?>>(it) }

        @Suppress("UNCHECKED_CAST")
        val session = (yaml["console"] as Map<String, Any?>)["session"] as Map<String, Any?>
        Duration.parse(session["idle-timeout"] as String) shouldBe Duration.ofMinutes(idle.toLong())
        Duration.parse(session["absolute-timeout"] as String) shouldBe Duration.ofHours(absolute.toLong())
    }
}
