package com.dynokore.gst.submission.adapters

import com.dynokore.gst.submission.adapters.events.JacksonEntityNodeChangedDataReader
import com.dynokore.gst.submission.application.gstn.EntityNodeChangedData
import com.dynokore.platform.outboxandqueue.ContractViolation
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * The structure event reader (INV-AC-22283). [SAMPLE] is the verbatim platform-contracts sample
 * tenancy/examples/valid/entity-created-with-uen-gstn.json of commit e378d2cdaeb7c43886a05902a7e25cb9858986f7.
 */
class JacksonEntityNodeChangedDataReaderTest {
    private val structure = JacksonEntityNodeChangedDataReader("nodeRef")
    private val profile = JacksonEntityNodeChangedDataReader("entityRef")

    private val node =
        """{"kind":"ENTITY","parentRef":"GRP-19C2","gstn":"M90000002B","structureVersion":4140,"name":"x"}"""

    private fun profileEvent(node: String) =
        """{"entityRef":"ENT-3F2A9C","changedAttributes":[],"effectiveDate":"2026-07-01","node":$node}"""

    private val expected = EntityNodeChangedData("ENT-3F2A9C", "ENTITY", "GRP-19C2", "M90000002B", 4140)

    @Test
    fun `INV-AC-22283 the contract sample gives node ref kind parent gstn and version`() {
        structure.read(SAMPLE) shouldBe expected
    }

    @Test
    fun `INV-AC-22283 the entityRef reader gives the same members for an EntityProfileChanged node`() {
        profile.read(profileEvent(node)) shouldBe expected
    }

    @Test
    fun `INV-AC-22283 the nodeRef reader does not read entityRef and the entityRef reader does not read nodeRef`() {
        shouldThrow<ContractViolation> { structure.read(profileEvent(node)) }
        shouldThrow<ContractViolation> { profile.read(SAMPLE) }
    }

    @Test
    fun `INV-AC-22283 the payload without node gives a null structure version`() {
        val json =
            """{"entityRef":"E-001","changedAttributes":[{"attribute":"legalName","newValue":"Acme Pte Ltd"}],""" +
                """"effectiveDate":"2026-07-01","constitution":"PRIVATE_LIMITED"}"""
        profile.read(json).structureVersion shouldBe null
    }

    @Test
    fun `INV-AC-22283 an absent gstn and a null parent give null members`() {
        val read = structure.read("""{"nodeRef":"G","node":{"kind":"GROUP","structureVersion":2,"parentRef":null}}""")
        read shouldBe EntityNodeChangedData("G", "GROUP", null, null, 2)
    }

    @Test
    fun `INV-AC-22283 unknown members are ignored`() {
        val json = SAMPLE.replace("\"nodeType\"", "\"future\":{\"a\":1},\"nodeType\"")
        structure.read(json) shouldBe expected
    }

    @Test
    fun `INV-AC-22283 each malformed case is a ContractViolation`() {
        val bad =
            listOf(
                "not json",
                "[]",
                """{"node":{"kind":"ENTITY","structureVersion":1}}""",
                """{"nodeRef":"","node":{"kind":"ENTITY","structureVersion":1}}""",
                """{"nodeRef":"  ","node":{"kind":"ENTITY","structureVersion":1}}""",
                """{"nodeRef":5,"node":{"kind":"ENTITY","structureVersion":1}}""",
                """{"nodeRef":"E","node":"x"}""",
                """{"nodeRef":"E","node":{"structureVersion":1}}""",
                """{"nodeRef":"E","node":{"kind":7,"structureVersion":1}}""",
                """{"nodeRef":"E","node":{"kind":"ENTITY"}}""",
                """{"nodeRef":"E","node":{"kind":"ENTITY","structureVersion":0}}""",
                """{"nodeRef":"E","node":{"kind":"ENTITY","structureVersion":-3}}""",
                """{"nodeRef":"E","node":{"kind":"ENTITY","structureVersion":1.5}}""",
                """{"nodeRef":"E","node":{"kind":"ENTITY","structureVersion":"4"}}""",
                """{"nodeRef":"E","node":{"kind":"ENTITY","structureVersion":1,"gstn":12}}""",
                """{"nodeRef":"E","node":{"kind":"ENTITY","structureVersion":1,"gstn":true}}""",
            )
        bad.forEach { json -> shouldThrow<ContractViolation> { structure.read(json) } }
    }

    private companion object {
        const val SAMPLE =
            """{"nodeType":"ENTITY","nodeRef":"ENT-3F2A9C","changeType":"CREATED","parentRef":"GRP-19C2",""" +
                """"node":{"kind":"ENTITY","parentRef":"GRP-19C2","retired":false,"name":"Acme Trading Pte Ltd",""" +
                """"code":"ACME01","uen":"201912345A","gstn":"M90000002B","profile":[],"structureVersion":4140}}"""
    }
}
