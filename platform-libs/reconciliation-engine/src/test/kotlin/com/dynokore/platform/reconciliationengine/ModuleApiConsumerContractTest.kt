package com.dynokore.platform.reconciliationengine

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant

/**
 * Consumer-driven contract test of the module API (the ModuleApiConsumerContractTest pattern): a stub written the
 * way `reconciliation-svc` runs and attests a reconciliation — it instantiates [ReconciliationEngine] with its own
 * taxonomy and drives only the public surface.
 */
class ModuleApiConsumerContractTest {
    private class StubService {
        private val engine = ReconciliationEngine(setOf(EXCLUDED), listOf(rule(EXCLUDED, "BOX_3", "exempt")))

        fun run(
            ret: Map<String, BigDecimal>,
            sub: Map<String, BigDecimal>,
            ev: Map<String, List<EvidenceRecord>>,
        ) = engine.reconcile(ret, sub, ev)

        fun paper(r: ReconciliationResult) =
            WorkPaper.build(
                WorkPaperSpec(setOf("entity")),
                mapOf("entity" to "E"),
                r,
                listOf(AttestationEntry("APPROVER", "u2", Instant.EPOCH)),
                "return-v2",
            )
    }

    @Test
    fun `the service raises on hasUnexplained and exports rows`() {
        val svc = StubService()
        val r =
            svc.run(
                mapOf("BOX_3" to d("500"), "BOX_1" to d("1")),
                emptyMap(),
                ev("BOX_3", Rec("EV", d("500"), "exempt")),
            )
        assertTrue(r.hasUnexplained)
        val w = svc.paper(r)
        assertTrue(w.attested)
        assertEquals(
            5,
            w
                .toRows()
                .map { it.section }
                .distinct()
                .size,
        )
    }
}
