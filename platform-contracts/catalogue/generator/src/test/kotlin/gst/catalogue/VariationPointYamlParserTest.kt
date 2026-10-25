package gst.catalogue

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files

class VariationPointYamlParserTest {
    private val keys = setOf("vp-01.set", "vp-09.set", "vp-22.set", "vp-39.set", "vp-99.set", "vp-02.set")

    private fun parse(file: java.io.File) = VariationPointYamlParser.parse(file, keys)

    private fun yamlFile(text: String) =
        Files.createTempFile("variation-points", ".yaml").toFile().apply { writeText(text) }

    @Test
    fun `parses a plain POLICY row with a default`() {
        val file =
            yamlFile(
                """
                variation_points:
                - id: VP-01
                  requirement: INV-REQ-1262
                  revision: "75a5b5c6-2dad-47c5-af77-6d56885d2252"
                  title: "Identities come from the platform or the customer's directory"
                  class: POLICY
                  class_modifiers: []
                  scope:
                    - tenant
                  owner: access-svc
                  set_permission: "vp-01.set"
                  must_be_elected: false
                  default: "Platform identity with MFA"
                  options:
                    - "Platform identity with MFA"
                    - "customer OIDC directory"
                  bounds: "The break-glass account always exists"
                  status: available
                  status_note: null
                  governs:
                    - INV-REQ-882
                  form_dependence: null
                """.trimIndent(),
            )
        val row = parse(file).single()
        assertEquals("VP-01", row.id)
        assertEquals("INV-REQ-1262", row.requirement)
        assertEquals(VpClass.POLICY, row.vpClass)
        assertTrue(row.classModifiers.isEmpty())
        assertEquals(listOf("tenant"), row.scope)
        assertEquals("access-svc", row.owner)
        assertEquals("vp-01.set", row.setPermission)
        assertFalse(row.mustBeElected)
        assertEquals("Platform identity with MFA", row.default)
        assertEquals(2, row.options.size)
        assertEquals(VpStatus.AVAILABLE, row.status)
        assertNull(row.statusNote)
        assertNull(row.formDependence)
    }

    @Test
    fun `a must-be-elected row needs no default`() {
        val file =
            yamlFile(
                """
                variation_points:
                - id: VP-22
                  requirement: INV-REQ-1283
                  revision: "ca01a0d6-8a34-44b8-91eb-3c614de25f89"
                  title: "Every entity elects its purchase transaction date basis"
                  class: POLICY
                  class_modifiers: []
                  scope:
                    - entity
                  owner: tenancy-svc
                  set_permission: "vp-22.set"
                  must_be_elected: true
                  default: null
                  options:
                    - "Invoice date"
                    - "posting date"
                  bounds: "Applied consistently"
                  status: available
                  status_note: null
                  governs: []
                  form_dependence: null
                """.trimIndent(),
            )
        val row = parse(file).single()
        assertTrue(row.mustBeElected)
        assertNull(row.default)
    }

    @Test
    fun `splits a status parenthetical into status and status_note`() {
        val file =
            yamlFile(
                """
                variation_points:
                - id: VP-39
                  requirement: INV-REQ-1300
                  revision: "x"
                  title: "t"
                  class: POLICY
                  class_modifiers: []
                  scope:
                    - tenant
                  owner: rules-svc
                  set_permission: "vp-39.set"
                  must_be_elected: false
                  default: "d"
                  options:
                    - "d"
                    - "o"
                  bounds: "b"
                  status: available
                  status_note: "ruling INV-Q-2219; the register row still says planned"
                  governs: []
                  form_dependence: null
                """.trimIndent(),
            )
        val row = parse(file).single()
        assertEquals(VpStatus.AVAILABLE, row.status)
        assertEquals("ruling INV-Q-2219; the register row still says planned", row.statusNote)
    }

    @Test
    fun `reads class modifiers`() {
        val file =
            yamlFile(
                """
                variation_points:
                - id: VP-09
                  requirement: INV-REQ-1270
                  revision: "x"
                  title: "t"
                  class: CONTROL
                  class_modifiers:
                    - non_waivable
                  scope:
                    - entity
                  owner: access-svc
                  set_permission: "vp-09.set"
                  must_be_elected: false
                  default: "d"
                  options:
                    - "d"
                    - "o"
                  bounds: "Always two different people"
                  status: available
                  status_note: null
                  governs: []
                  form_dependence: null
                """.trimIndent(),
            )
        val row = parse(file).single()
        assertEquals(VpClass.CONTROL, row.vpClass)
        assertEquals(listOf(VpClassModifier.NON_WAIVABLE), row.classModifiers)
    }

    @Test
    fun `rejects an id not shaped VP-nn`() {
        val file =
            yamlFile(
                """
                variation_points:
                - id: bad-id
                  requirement: INV-REQ-1
                  revision: "x"
                  title: "t"
                  class: POLICY
                  class_modifiers: []
                  scope: []
                  owner: o
                  set_permission: "bad-id.set"
                  must_be_elected: false
                  default: "d"
                  options:
                    - "d"
                  bounds: null
                  status: available
                  status_note: null
                  governs: []
                  form_dependence: null
                """.trimIndent(),
            )
        assertThrows(IllegalStateException::class.java) { parse(file) }
    }

    @Test
    fun `rejects a row with neither a default nor must_be_elected`() {
        val file =
            yamlFile(
                """
                variation_points:
                - id: VP-99
                  requirement: INV-REQ-1
                  revision: "x"
                  title: "t"
                  class: POLICY
                  class_modifiers: []
                  scope: []
                  owner: o
                  set_permission: "vp-99.set"
                  must_be_elected: false
                  default: null
                  options:
                    - "d"
                  bounds: null
                  status: available
                  status_note: null
                  governs: []
                  form_dependence: null
                """.trimIndent(),
            )
        assertThrows(IllegalStateException::class.java) { parse(file) }
    }

    private fun rowWith(setPermissionLine: String) =
        yamlFile(
            """
            variation_points:
            - id: VP-02
              requirement: INV-REQ-1
              revision: "x"
              title: "t"
              class: POLICY
              class_modifiers: []
              scope: []
              owner: o
            $setPermissionLine
              must_be_elected: false
              default: "d"
              options:
                - "d"
              bounds: null
              status: available
              status_note: null
              governs: []
              form_dependence: null
            """.trimIndent(),
        )

    private fun refusal(setPermissionLine: String): String =
        assertThrows(IllegalStateException::class.java) { parse(rowWith(setPermissionLine)) }.message!!

    @Test
    fun `INV-T-10133 accepts the row's own key`() {
        assertEquals("vp-02.set", parse(rowWith("  set_permission: \"vp-02.set\"")).single().setPermission)
    }

    @Test
    fun `INV-T-10133 refuses a row without set_permission, naming the row`() {
        assertTrue(refusal("  set_by: []").contains("VP-02 has no 'set_permission'"))
    }

    @Test
    fun `INV-T-10133 refuses an empty and a null set_permission`() {
        assertTrue(refusal("  set_permission: \"\"").contains("VP-02 has no 'set_permission'"))
        assertTrue(refusal("  set_permission: null").contains("VP-02 has no 'set_permission'"))
    }

    @Test
    fun `INV-T-10133 refuses a key that is not in role-permissions, a role name and a trailing space`() {
        listOf("vp-98.set", "tenant.admin", "vp-02.set ", "VP-02.set").forEach {
            assertTrue(refusal("  set_permission: \"$it\"").contains("VP-02's set_permission"), it)
        }
    }

    @Test
    fun `INV-T-10133 refuses the key of another row`() {
        assertTrue(refusal("  set_permission: \"vp-01.set\"").contains("is not 'vp-02.set'"))
    }

    @Test
    fun `INV-T-10133 refuses a row that still carries set_by only`() {
        assertTrue(refusal("  set_by: [tenant.admin]").contains("VP-02 has no 'set_permission'"))
    }
}
