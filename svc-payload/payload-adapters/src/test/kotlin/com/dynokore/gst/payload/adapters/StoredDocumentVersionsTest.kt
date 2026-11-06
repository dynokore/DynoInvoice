package com.dynokore.gst.payload.adapters

import com.dynokore.gst.payload.adapters.persistence.StoredDocumentVersions
import com.dynokore.gst.payload.application.override.DocumentVersionFacts
import com.dynokore.gst.payload.application.version.VersionFactsReader
import com.dynokore.gst.payload.domain.version.VersionFacts
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.util.UUID

class StoredDocumentVersionsTest {
    private val doc = UUID.randomUUID()

    private fun over(vararg stored: Pair<Int, VersionFacts>) =
        StoredDocumentVersions(
            object : VersionFactsReader {
                override fun find(
                    docUuid: UUID,
                    version: Int,
                ) = if (docUuid == doc) stored.toMap()[version] else null
            },
        )

    @Test
    fun `a stored version answers its entity and total`() {
        over(1 to VersionFacts("E-1", BigDecimal("107.00"))).find(doc, 1) shouldBe
            DocumentVersionFacts("E-1", BigDecimal("107.00"))
    }

    @Test
    fun `an unknown version answers null`() {
        over(1 to VersionFacts("E-1", BigDecimal("107.00"))).find(doc, 2) shouldBe null
    }

    @Test
    fun `a stored version with a null total answers null`() {
        over(1 to VersionFacts("E-1", null)).find(doc, 1) shouldBe null
    }
}
