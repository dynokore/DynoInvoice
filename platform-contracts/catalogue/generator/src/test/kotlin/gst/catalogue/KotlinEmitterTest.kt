package gst.catalogue

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class KotlinEmitterTest {
    private val rows =
        listOf(
            ParsedErrorCode(
                code = "ACC-001",
                area = "ACC",
                number = 1,
                conditionRaw = "raw",
                conditionCore = """A "quoted" condition with a \ backslash""",
                revisionNote = null,
                severity = Severity.WARN,
                severityRaw = "WARN",
                disposition = Disposition.MANUAL,
                dispositionRaw = "MANUAL",
                owner = "tenant.admin",
            ),
            ParsedErrorCode(
                code = "ACC-003",
                area = "ACC",
                number = 3,
                conditionRaw = "raw",
                conditionCore = "External grant expired",
                revisionNote = null,
                severity = Severity.INFO,
                severityRaw = "INFO",
                disposition = null,
                dispositionRaw = "None",
                owner = "tenant.admin",
            ),
        )

    @Test
    fun `emits one enum constant per row named AREA_NNN`() {
        val source = KotlinEmitter.emit("com.dynokore.gst.catalogue", rows, "deadbeef")
        assertTrue(source.contains("ACC_001(\"ACC-001\""))
        assertTrue(source.contains("ACC_003(\"ACC-003\""))
    }

    @Test
    fun `escapes quotes and backslashes in the condition literal`() {
        val source = KotlinEmitter.emit("com.dynokore.gst.catalogue", rows, "deadbeef")
        assertTrue(source.contains("""A \"quoted\" condition with a \\ backslash"""))
    }

    @Test
    fun `a null disposition is emitted as the literal null`() {
        val source = KotlinEmitter.emit("com.dynokore.gst.catalogue", rows, "deadbeef")
        assertTrue(source.contains("Severity.INFO, null, \"tenant.admin\""))
    }

    @Test
    fun `records the pinned source commit in the generated header`() {
        val source = KotlinEmitter.emit("com.dynokore.gst.catalogue", rows, "deadbeef")
        assertTrue(source.contains("deadbeef"))
        assertTrue(source.lines().first().startsWith("// GENERATED"))
    }
}
