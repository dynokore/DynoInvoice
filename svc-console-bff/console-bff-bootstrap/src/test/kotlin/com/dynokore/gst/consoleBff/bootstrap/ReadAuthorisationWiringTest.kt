package com.dynokore.gst.consoleBff.bootstrap

import com.dynokore.gst.consoleBff.adapters.authz.ConsoleReadAuthorisationFilter
import com.dynokore.gst.consoleBff.adapters.ratelimit.ConsoleRateLimitFilter
import com.dynokore.gst.consoleBff.adapters.session.ConsoleSessionFilter
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.springframework.boot.web.servlet.FilterRegistrationBean
import java.nio.file.Path

/** INV-AC-20320 in the composed application (TPL-18): the read filter follows the session and rate-limit filters. */
class ReadAuthorisationWiringTest {
    private data class Reg(
        val filterClass: Class<*>,
        val order: Int,
    )

    private fun registrations(beans: Collection<FilterRegistrationBean<*>>) =
        beans.map { Reg(it.filter!!.javaClass, it.order) }

    /** The assertion of the criterion over a list of registrations; throws naming the offence. */
    private fun assertComposition(regs: List<Reg>) {
        val session = regs.single { it.filterClass == ConsoleSessionFilter::class.java }
        val limit = regs.single { it.filterClass == ConsoleRateLimitFilter::class.java }
        val reads = regs.filter { it.filterClass == ConsoleReadAuthorisationFilter::class.java }
        check(reads.size == 1) { "the read-authorisation filter must be registered once, found ${reads.size}" }
        val read = reads.single()
        check(session.order < limit.order && limit.order < read.order) {
            "orders must be strictly ascending session < rate-limit < read: " +
                "${session.order}, ${limit.order}, ${read.order}"
        }
    }

    @Test
    fun `INV-AC-20320 the read filter is registered once after the session and rate-limit filters`(
        @TempDir dir: Path,
    ) {
        ConsoleContext.offline(dir).run { context ->
            context.startupFailure shouldBe null
            val regs = registrations(context.getBeansOfType(FilterRegistrationBean::class.java).values)
            assertComposition(regs)
            regs.single { it.filterClass == ConsoleReadAuthorisationFilter::class.java }.order shouldBe
                ConsoleReadAuthorisationFilter.ORDER
        }
    }

    @Test
    fun `INV-AC-20320 a second registration of the read filter fails the composition check`() {
        val good = good()
        shouldThrow<IllegalStateException> {
            assertComposition(good + Reg(ConsoleReadAuthorisationFilter::class.java, 1_000))
        }.message shouldContain "once"
    }

    @Test
    fun `INV-AC-20320 a read filter order equal to or below the rate-limit order fails the composition check`() {
        listOf(ConsoleRateLimitFilter.ORDER, ConsoleRateLimitFilter.ORDER - 1, ConsoleSessionFilter.ORDER).forEach {
            shouldThrow<IllegalStateException> {
                assertComposition(
                    good().filter { r -> r.filterClass != ConsoleReadAuthorisationFilter::class.java } +
                        Reg(ConsoleReadAuthorisationFilter::class.java, it),
                )
            }.message shouldContain "ascending"
        }
    }

    @Test
    fun `INV-AC-20320 an unregistered read filter fails the composition check`() {
        shouldThrow<IllegalStateException> {
            assertComposition(good().filter { it.filterClass != ConsoleReadAuthorisationFilter::class.java })
        }.message shouldContain "once"
    }

    private fun good() =
        listOf(
            Reg(ConsoleSessionFilter::class.java, ConsoleSessionFilter.ORDER),
            Reg(ConsoleRateLimitFilter::class.java, ConsoleRateLimitFilter.ORDER),
            Reg(ConsoleReadAuthorisationFilter::class.java, ConsoleReadAuthorisationFilter.ORDER),
        )
}
