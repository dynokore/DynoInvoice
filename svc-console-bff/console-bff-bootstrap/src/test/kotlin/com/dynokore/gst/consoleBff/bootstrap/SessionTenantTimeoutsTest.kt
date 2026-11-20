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

    private val tenantB = "22222222-2222-2222-2222-22222222bbbb"
    private val opened = Instant.parse("2026-06-01T08:00:00Z")

    private fun prepare() {
        ConsoleDb.superuser().use { c ->
            c.createStatement().execute("truncate console_bff.console_session")
            c.createStatement().execute("truncate console_bff.config_setting")
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
    fun `INV-AC-25103 with the real PolicyReader a tenant with no setting idles 29 accepted 31 refused`(
        @TempDir dir: Path,
    ) {
        prepare()
        ConsoleContext.online(dir).run { context ->
            context.startupFailure shouldBe null
            (verdict(context, tenantB, Duration.ofMinutes(29)) is SessionResolution.Resolved) shouldBe true
            verdict(context, tenantB, Duration.ofMinutes(31)) shouldBe SessionResolution.Refused(RefusalReason.EXPIRED)
        }
    }

    @Test
    fun `INV-AC-25103 the shipped preset equals the VP-84 default of the pinned catalogue`() {
        val spec = VariationPoint.entries.single { it.id == "VP-84" }.optionSpecs!!.single { it.key == "SHORTER" }
        val record = spec.typedValue!!
        val idle = record.fields.getValue("idle").default!!.number!!
        val absolute = record.fields.getValue("absolute").default!!.number!!
        val yaml =
            ClassLoader.getSystemResourceAsStream("application.yaml")!!.use { Yaml().load<Map<String, Any?>>(it) }

        @Suppress("UNCHECKED_CAST")
        val session = (yaml["console"] as Map<String, Any?>)["session"] as Map<String, Any?>
        Duration.parse(session["idle-timeout"] as String) shouldBe Duration.ofMinutes(idle.toLong())
        Duration.parse(session["absolute-timeout"] as String) shouldBe Duration.ofHours(absolute.toLong())
    }
}
