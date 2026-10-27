package com.dynokore.gst.canonical.acceptance

import com.dynokore.gst.canonical.domain.dating.DateBasis
import com.dynokore.gst.canonical.domain.dating.Direction
import com.dynokore.gst.canonical.domain.dating.HoldCode
import com.dynokore.gst.catalogue.Catalogue
import com.dynokore.gst.catalogue.ErrorCode
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.yaml.snakeyaml.Yaml
import java.io.File

/**
 * The dating enums equal their sources, each read from the contract or the generated catalogue, not copied
 * (INV-T-6935): `RecordDocumentRequest.direction` from the pinned canonical/openapi.yaml staged by
 * canonical-contract; VP-22's option keys and the PER-009 / PER-011 codes from gst-catalogue, the generated form
 * of variation-points.yaml and error-codes.yaml.
 */
class DatingEnumsTest {
    @Test
    fun `Direction equals the enum of RecordDocumentRequest direction in the canonical contract`() {
        val openapi = File(System.getProperty("gst.contract.dir"), "openapi.yaml").readText()
        val schemas = ((Yaml().load<Map<String, Any?>>(openapi)["components"] as Map<*, *>)["schemas"] as Map<*, *>)
        val request = schemas["RecordDocumentRequest"] as Map<*, *>
        val direction = (request["properties"] as Map<*, *>)["direction"] as Map<*, *>
        val contract = (direction["enum"] as List<*>).map { it.toString() }.toSet()
        contract.isEmpty() shouldBe false
        Direction.entries.map { it.name }.toSet() shouldBe contract
    }

    @Test
    fun `DateBasis equals the VP-22 option keys of the variation point catalogue`() {
        val keys = Catalogue.optionKeys("VP-22").toSet()
        keys.isEmpty() shouldBe false
        DateBasis.entries.map { it.name }.toSet() shouldBe keys
    }

    @Test
    fun `HoldCode strings equal the PER-009 and PER-011 codes of the error catalogue`() {
        val codes = ErrorCode.entries.map { it.code }.toSet()
        val expected = setOf("PER-009", "PER-011")
        (codes.containsAll(expected)) shouldBe true
        HoldCode.entries.map { it.code }.toSet() shouldBe expected
        ErrorCode.entries
            .filter { it.code in expected }
            .map { it.severity.name }
            .toSet() shouldBe setOf("HOLD")
    }
}
