package com.dynokore.gst.access.adapters.persistence

import com.dynokore.gst.access.application.link.SignatoryAction
import com.dynokore.gst.access.application.link.SignatoryActionKind
import com.dynokore.gst.access.domain.grant.EntityRef
import com.dynokore.gst.access.domain.link.LinkRef
import com.dynokore.platform.tenancykernel.TenantId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

class SignatoryEvidenceRefsTest {
    private val tenant = TenantId(UUID.fromString("11111111-2222-3333-4444-555555555555"))

    private fun action(
        link: String = "LNK-1",
        kind: SignatoryActionKind = SignatoryActionKind.ACKNOWLEDGE,
        pack: String = "2026.3",
    ) = SignatoryAction(LinkRef(link), kind, pack, null, EntityRef("CLI-A"), "link:$link", Instant.EPOCH)

    @Test
    fun `evidenceRef is EVD and a version 5 UUID, 40 characters, a repeat answers the same value`() {
        val ref = SignatoryEvidenceRefs.refOf(tenant, action())
        assertEquals(40, ref.length)
        assertEquals(5, SignatoryEvidenceRefs.idOf(tenant, action()).version())
        assertEquals(ref, SignatoryEvidenceRefs.refOf(tenant, action()))
    }

    @Test
    fun `evidenceRef known answer`() {
        assertEquals("EVD-c1c43ce7-9546-5500-a89d-b4813301b405", SignatoryEvidenceRefs.refOf(tenant, action()))
    }

    @Test
    fun `evidenceRef differs in tenant, link, kind or pack version`() {
        val base = SignatoryEvidenceRefs.refOf(tenant, action())
        val others =
            listOf(
                SignatoryEvidenceRefs.refOf(TenantId(UUID.randomUUID()), action()),
                SignatoryEvidenceRefs.refOf(tenant, action(link = "LNK-2")),
                SignatoryEvidenceRefs.refOf(tenant, action(kind = SignatoryActionKind.QUERY)),
                SignatoryEvidenceRefs.refOf(tenant, action(pack = "2026.4")),
            )
        others.forEach { assertNotEquals(base, it) }
    }
}
