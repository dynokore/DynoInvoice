package com.dynokore.gst.consoleBff.adapters.exceptions

import com.dynokore.platform.outboxandqueue.ContractViolation
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class JacksonFailureReadersTest {
    private val rejected = JacksonPayloadRejectedReader()
    private val failed = JacksonSubmissionFailedReader()
    private val doc = "22222222-2222-2222-2222-222222222222"

    private fun json(
        base: Map<String, String>,
        drop: Set<String> = emptySet(),
        set: Map<String, String> = emptyMap(),
    ): String {
        val members = LinkedHashMap(base)
        members.putAll(set)
        drop.forEach { members.remove(it) }
        return members.entries.joinToString(",", "{", "}") { "\"${it.key}\":${it.value}" }
    }

    private val prBase =
        mapOf(
            "docUuid" to "\"$doc\"",
            "version" to "1",
            "docsequence" to "5",
            "validationTrace" to "{}",
            "code" to "\"PAY-001\"",
            "remediationOwner" to "\"OWN-A\"",
            "disposition" to "\"TERMINAL\"",
            "entityRef" to "\"ENT-P\"",
        )
    private val sfBase =
        mapOf(
            "docUuid" to "\"$doc\"",
            "version" to "2",
            "failureClass" to "\"SRT-006\"",
            "retryDisposition" to "\"MANUAL\"",
        )

    private fun pr(
        drop: Set<String> = emptySet(),
        set: Map<String, String> = emptyMap(),
        ref: String? = "ENV",
        seq: Long? = 9,
    ) = rejected.read(json(prBase, drop, set), ref, seq)

    private fun sf(
        drop: Set<String> = emptySet(),
        set: Map<String, String> = emptyMap(),
        ref: String? = "ENV",
        seq: Long? = 3,
    ) = failed.read(json(sfBase, drop, set), ref, seq)

    private fun bad(block: () -> Any) {
        shouldThrow<ContractViolation> { block() }
    }

    @Test
    fun `INV-AC-21057 PayloadRejected reads all members`() {
        val f = pr()
        f.code shouldBe "PAY-001"
        f.remediationOwner shouldBe "OWN-A"
        f.disposition shouldBe "TERMINAL"
        f.docUuid shouldBe doc
    }

    @Test
    fun `INV-AC-21057 PayloadRejected missing blank and non-string code are violations`() {
        bad { pr(drop = setOf("code")) }
        bad { pr(set = mapOf("code" to "\"  \"")) }
        bad { pr(set = mapOf("code" to "null")) }
        bad { pr(set = mapOf("code" to "7")) }
    }

    @Test
    fun `INV-AC-21057 SubmissionFailed non-blank code wins over failureClass`() {
        sf(set = mapOf("code" to "\"SRT-009\"")).code shouldBe "SRT-009"
    }

    @Test
    fun `INV-AC-21057 SubmissionFailed blank and null code give failureClass`() {
        sf(set = mapOf("code" to "\"\"")).code shouldBe "SRT-006"
        sf(set = mapOf("code" to "null")).code shouldBe "SRT-006"
        sf().code shouldBe "SRT-006"
    }

    @Test
    fun `INV-AC-21057 SubmissionFailed non-string code is a violation`() {
        bad { sf(set = mapOf("code" to "7")) }
    }

    @Test
    fun `INV-AC-21057 SubmissionFailed missing and blank failureClass are violations`() {
        bad { sf(drop = setOf("failureClass")) }
        bad { sf(set = mapOf("failureClass" to "\" \"")) }
        bad { sf(set = mapOf("failureClass" to "3")) }
    }

    @Test
    fun `INV-AC-21057 payload docsequence wins over the envelope`() {
        pr(seq = 9).docsequence shouldBe 5
        sf(set = mapOf("docsequence" to "4"), seq = 3).docsequence shouldBe 4
    }

    @Test
    fun `INV-AC-21057 envelope docSequence is used when the payload has none`() {
        pr(drop = setOf("docsequence"), seq = 9).docsequence shouldBe 9
        sf(seq = 3).docsequence shouldBe 3
        pr(set = mapOf("docsequence" to "null"), seq = 9).docsequence shouldBe 9
    }

    @Test
    fun `INV-AC-21057 no docsequence anywhere is a violation`() {
        bad { pr(drop = setOf("docsequence"), seq = null) }
        bad { sf(seq = null) }
    }

    @Test
    fun `INV-AC-21057 docsequence 0 1_5 and string are violations`() {
        for (v in listOf("0", "1.5", "\"3\"", "-1")) {
            bad { pr(set = mapOf("docsequence" to v)) }
            bad { sf(set = mapOf("docsequence" to v)) }
        }
    }

    @Test
    fun `INV-AC-21057 entityRef payload wins and envelope is the fallback`() {
        pr().entityRef shouldBe "ENT-P"
        pr(drop = setOf("entityRef")).entityRef shouldBe "ENV"
        pr(set = mapOf("entityRef" to "null")).entityRef shouldBe "ENV"
        sf().entityRef shouldBe "ENV"
        sf(set = mapOf("entityRef" to "\"ENT-S\"")).entityRef shouldBe "ENT-S"
    }

    @Test
    fun `INV-AC-21057 entityRef none number or blank is a violation`() {
        bad { pr(drop = setOf("entityRef"), ref = null) }
        bad { sf(ref = null) }
        bad { pr(drop = setOf("entityRef"), ref = " ") }
        bad { pr(set = mapOf("entityRef" to "5")) }
        bad { sf(set = mapOf("entityRef" to "\" \"")) }
        bad { sf(set = mapOf("entityRef" to "7")) }
    }

    @Test
    fun `INV-AC-21057 missing and blank docUuid are violations`() {
        bad { pr(drop = setOf("docUuid")) }
        bad { pr(set = mapOf("docUuid" to "\" \"")) }
        bad { sf(drop = setOf("docUuid")) }
        bad { sf(set = mapOf("docUuid" to "\"\"")) }
    }

    @Test
    fun `INV-AC-21057 non-object and non-JSON data are violations`() {
        for (d in listOf("[]", "\"x\"", "null", "{", "")) {
            bad { rejected.read(d, "E", 1) }
            bad { failed.read(d, "E", 1) }
        }
    }

    @Test
    fun `INV-AC-21057 absent owner and disposition give the empty string`() {
        val f = pr(drop = setOf("remediationOwner", "disposition"))
        f.remediationOwner shouldBe ""
        f.disposition shouldBe ""
        sf().remediationOwner shouldBe ""
        sf().disposition shouldBe ""
        sf(set = mapOf("remediationOwner" to "null")).remediationOwner shouldBe ""
    }
}
