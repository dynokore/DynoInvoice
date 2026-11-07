package com.dynokore.gst.transport.bootstrap

import com.dynokore.gst.transport.adapters.ap.IrasEnvironment
import com.dynokore.gst.transport.domain.AdapterId
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import java.time.Duration

/** Start-up rules of `transport.ap.poll-interval` and of the id of a POLLING adapter (INV-Q-9684, its rulings). */
class ApPollSettingsTest {
    private fun props(
        interval: String?,
        vararg ids: Pair<String, NotificationMode>,
    ) = ApProperties(
        environment = IrasEnvironment.SANDBOX,
        adapters = ids.map { (id, mode) -> ApProperties.Adapter(id, "primary", mode) },
        pollInterval = interval?.let(Duration::parse),
    )

    private fun polling(
        interval: String,
        id: String = "peppol-ap-1",
    ) = props(interval, id to NotificationMode.POLLING)

    @Test
    fun `INV-AC-1780 poll-interval PT29S, PT30S and PT7M fail start-up naming property and value`() {
        listOf("PT29S", "PT30S", "PT7M").forEach {
            shouldThrow<IllegalArgumentException> { polling(it) }.message.orEmpty().let { m ->
                m shouldContain "transport.ap.poll-interval"
                m shouldContain it
            }
        }
    }

    @Test
    fun `INV-AC-1780 poll-interval PT1M, PT5M and PT1H start`() {
        listOf("PT1M", "PT5M", "PT1H").forEach { polling(it).pollInterval shouldBe Duration.parse(it) }
    }

    @Test
    fun `INV-AC-1780 look-alike POLLING ids fail start-up naming the id and the pattern`() {
        listOf("Primary", "peppol_ap_1", "ap.1", "a".repeat(41), "", "a b").forEach {
            shouldThrow<IllegalArgumentException> { polling("PT1H", it) }.message.orEmpty().let { m ->
                m shouldContain "'$it'"
                m shouldContain "^[a-z0-9-]{1,40}$"
            }
        }
    }

    @Test
    fun `INV-AC-1780 the same look-alike id in a CALLBACK adapter starts`() {
        listOf("Primary", "peppol_ap_1", "ap.1", "a".repeat(41)).forEach {
            props(null, it to NotificationMode.CALLBACK).adapters.single().id shouldBe it
        }
    }

    @Test
    fun `INV-AC-1780 ids peppol-ap-1 and 1 start and give the job names without transformation`() {
        ApPollJobs.jobName(AdapterId("peppol-ap-1")) shouldBe "ap-poll-peppol-ap-1"
        ApPollJobs.jobName(AdapterId("1")) shouldBe "ap-poll-1"
        polling("PT1H", "1").adapters.single().id shouldBe "1"
        polling("PT1H", "a".repeat(40))
            .adapters
            .single()
            .id.length shouldBe 40
    }

    @Test
    fun `INV-AC-1780 a POLLING adapter A together with a POLLING adapter a fails at the first`() {
        val e =
            shouldThrow<IllegalArgumentException> {
                props("PT1H", "A" to NotificationMode.POLLING, "a" to NotificationMode.POLLING)
            }
        e.message.orEmpty() shouldContain "'A'"
    }
}
