package com.dynokore.gst.consoleBff.bootstrap

import com.dynokore.gst.consoleBff.adapters.ratelimit.ConsoleRateLimitFilter
import com.dynokore.gst.consoleBff.adapters.session.ConsoleSessionFilter
import com.dynokore.gst.consoleBff.domain.ratelimit.ConsoleRateLimiter
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.springframework.boot.web.servlet.FilterRegistrationBean
import java.nio.file.Path

/** INV-AC-6924 in the composed application (TPL-18): the limit filter follows the session filter, settings bind. */
class RateLimitWiringTest {
    @Test
    fun `INV-AC-6924 the limit filter is registered right after the session filter with the default limits`(
        @TempDir dir: Path,
    ) {
        ConsoleContext.offline(dir).run { context ->
            context.startupFailure shouldBe null
            val p = context.getBean(ConsoleRateLimitProperties::class.java)
            p.perPrincipal shouldBe 300
            p.perTenant shouldBe 3_000
            p.readyReplicas shouldBe 1
            val limit = context.getBean("consoleRateLimitFilterRegistration", FilterRegistrationBean::class.java)
            val session = context.getBean("consoleSessionFilterRegistration", FilterRegistrationBean::class.java)
            (limit.filter is ConsoleRateLimitFilter) shouldBe true
            (limit.order > session.order) shouldBe true
            limit.order shouldBe ConsoleRateLimitFilter.ORDER
            session.order shouldBe ConsoleSessionFilter.ORDER
            context
                .getBean(ConsoleRateLimiter::class.java)
                .check("p", "t")
                .policy("console")
                .quota shouldBe 300
        }
    }

    @Test
    fun `INV-AC-6924 settings, ready replicas and a tenant override come from the installation settings`(
        @TempDir dir: Path,
    ) {
        ConsoleContext
            .offline(
                dir,
                "console.rate-limit.per-principal=100",
                "console.rate-limit.per-tenant=1000",
                "console.rate-limit.ready-replicas=2",
                "console.rate-limit.tenant-overrides.t-big.per-principal=400",
            ).run { context ->
                context.startupFailure shouldBe null
                val limiter = context.getBean(ConsoleRateLimiter::class.java)
                limiter.check("p", "t-1").let {
                    it.policy("console").quota shouldBe 50
                    it.policy("tenant").quota shouldBe 500
                }
                limiter.check("p", "t-big").let {
                    it.policy("console").quota shouldBe 200
                    it.policy("tenant").quota shouldBe 500
                }
            }
    }

    @Test
    fun `INV-AC-6924 a limit or replica count below 1 fails the start naming the property`(
        @TempDir dir: Path,
    ) {
        listOf(
            "console.rate-limit.per-principal=0" to "console.rate-limit.per-principal",
            "console.rate-limit.per-tenant=-1" to "console.rate-limit.per-tenant",
            "console.rate-limit.ready-replicas=0" to "console.rate-limit.ready-replicas",
            "console.rate-limit.tenant-overrides.t.per-tenant=0" to "tenant-overrides[t].per-tenant",
        ).forEach { (setting, name) ->
            ConsoleContext.offline(dir, setting).run { context ->
                context.startupFailure?.stackTraceToString() shouldContain name
            }
        }
    }
}
