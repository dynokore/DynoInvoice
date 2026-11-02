package com.dynokore.gst.submission.domain.readiness

import com.dynokore.gst.submission.domain.readiness.ItemSeverity.BLOCK
import com.dynokore.gst.submission.domain.readiness.ItemSeverity.HOLD
import com.dynokore.gst.submission.domain.readiness.ItemSeverity.INFO
import com.dynokore.gst.submission.domain.readiness.ItemSeverity.WARN
import com.dynokore.gst.submission.domain.readiness.NotReadyChoice.Accepted
import com.dynokore.gst.submission.domain.readiness.NotReadyChoice.Refused
import com.dynokore.gst.submission.domain.readiness.NotReadyOption.ADD_WARN_CODES
import com.dynokore.gst.submission.domain.readiness.NotReadyOption.BLOCK_ONLY
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class NotReadyConditionTest {
    private val blockOnly = NotReadyCondition.DEFAULT
    private val added = NotReadySetting(ADD_WARN_CODES, setOf("SUB-005", "PER-003"))

    private fun item(
        code: String,
        severity: ItemSeverity,
    ) = NotReadyItem(code, severity)

    private fun notReady(
        setting: NotReadySetting,
        vararg items: NotReadyItem,
    ) = NotReadyCondition.isNotReady(setting, items.toList())

    @Test
    fun `INV-AC-16253 a BLOCK item is not ready under both settings, named or not`() {
        notReady(blockOnly, item("SUB-004", BLOCK)) shouldBe true
        notReady(added, item("SUB-004", BLOCK)) shouldBe true
        notReady(added, item("SUB-005", BLOCK)) shouldBe true
        notReady(NotReadySetting(ADD_WARN_CODES, setOf("SUB-004")), item("SUB-004", BLOCK)) shouldBe true
    }

    @Test
    fun `INV-AC-16253 BLOCK_ONLY counts no WARN, HOLD or INFO item and an empty list is ready`() {
        notReady(
            blockOnly,
            item("SUB-005", WARN),
            item("ING-017", HOLD),
            item("SUB-001", INFO),
        ) shouldBe false
        notReady(blockOnly) shouldBe false
        notReady(added) shouldBe false
    }

    @Test
    fun `INV-AC-16253 ADD_WARN_CODES counts a named WARN item only`() {
        notReady(added, item("SUB-005", WARN)) shouldBe true
        notReady(added, item("PER-003", WARN)) shouldBe true
        notReady(added, item("SUB-009", WARN)) shouldBe false
        notReady(added, item("SUB-005", INFO)) shouldBe false
        notReady(added, item("PER-003", HOLD)) shouldBe false
        notReady(added, item("sub-005", WARN)) shouldBe false
    }

    @Test
    fun `INV-AC-16253 every list holding a BLOCK item is not ready under every setting`() {
        val settings = listOf(blockOnly, added, NotReadySetting(ADD_WARN_CODES, setOf("SUB-004")))
        for (setting in settings) {
            for (severity in ItemSeverity.entries) {
                for (code in listOf("SUB-004", "SUB-005", "XXX-000")) {
                    notReady(setting, item(code, severity), item("SUB-004", BLOCK)) shouldBe true
                    notReady(setting, item("SUB-004", BLOCK), item(code, severity)) shouldBe true
                }
            }
        }
    }

    private val warnMap = mapOf("SUB-005" to WARN, "SUB-004" to BLOCK)

    @Test
    fun `INV-AC-16253 choose refuses ADD_WARN_CODES while planned and accepts when available`() {
        NotReadyCondition.choose(ADD_WARN_CODES, setOf("SUB-005"), false, warnMap) shouldBe
            Refused(NotReadyRefusal.POINT_PLANNED)
        NotReadyCondition.choose(ADD_WARN_CODES, setOf("SUB-005"), true, warnMap) shouldBe
            Accepted(NotReadySetting(ADD_WARN_CODES, setOf("SUB-005")))
    }

    @Test
    fun `INV-AC-16253 choose accepts BLOCK_ONLY with no codes whatever the point`() {
        for (available in listOf(true, false)) {
            NotReadyCondition.choose(BLOCK_ONLY, emptySet(), available, warnMap) shouldBe
                Accepted(NotReadyCondition.DEFAULT)
        }
    }

    @Test
    fun `INV-AC-16253 choose refuses an empty set, a non-WARN code and codes without the option`() {
        NotReadyCondition.choose(ADD_WARN_CODES, emptySet(), true, warnMap) shouldBe
            Refused(NotReadyRefusal.NO_CODES)
        NotReadyCondition.choose(ADD_WARN_CODES, setOf("SUB-004"), true, warnMap) shouldBe
            Refused(NotReadyRefusal.CODE_NOT_WARN)
        NotReadyCondition.choose(ADD_WARN_CODES, setOf("XXX-000"), true, warnMap) shouldBe
            Refused(NotReadyRefusal.CODE_NOT_WARN)
        NotReadyCondition.choose(ADD_WARN_CODES, setOf("sub-005"), true, warnMap) shouldBe
            Refused(NotReadyRefusal.CODE_NOT_WARN)
        NotReadyCondition.choose(ADD_WARN_CODES, setOf("SUB-005", "SUB-004"), true, warnMap) shouldBe
            Refused(NotReadyRefusal.CODE_NOT_WARN)
        NotReadyCondition.choose(BLOCK_ONLY, setOf("SUB-005"), true, warnMap) shouldBe
            Refused(NotReadyRefusal.CODES_WITHOUT_OPTION)
    }

    @Test
    fun `INV-AC-16253 fromKey takes the exact name only`() {
        NotReadyOption.fromKey("BLOCK_ONLY") shouldBe BLOCK_ONLY
        NotReadyOption.fromKey("ADD_WARN_CODES") shouldBe ADD_WARN_CODES
        for (bad in listOf("block_only", "BLOCK_ONLY ", "")) {
            assertThrows<IllegalArgumentException> { NotReadyOption.fromKey(bad) }
        }
    }

    @Test
    fun `INV-AC-16253 the setting and the item refuse invalid content`() {
        assertThrows<IllegalArgumentException> { NotReadySetting(BLOCK_ONLY, setOf("SUB-005")) }
        assertThrows<IllegalArgumentException> { NotReadySetting(ADD_WARN_CODES, emptySet()) }
        assertThrows<IllegalArgumentException> { NotReadySetting(ADD_WARN_CODES, setOf("SUB-005", " ")) }
        assertThrows<IllegalArgumentException> { NotReadyItem(" ", BLOCK) }
    }

    @Test
    fun `INV-AC-16253 the enum members are fixed`() {
        ItemSeverity.entries.map { it.name } shouldBe listOf("BLOCK", "HOLD", "WARN", "INFO")
        NotReadyOption.entries.map { it.name } shouldBe listOf("BLOCK_ONLY", "ADD_WARN_CODES")
    }
}
