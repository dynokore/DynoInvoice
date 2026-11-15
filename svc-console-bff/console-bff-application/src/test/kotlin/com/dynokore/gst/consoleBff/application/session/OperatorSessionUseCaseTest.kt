package com.dynokore.gst.consoleBff.application.session

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

/** INV-AC-23363: the case reference rule and the open/read use case, over test doubles of the store and access-svc. */
class OperatorSessionUseCaseTest {
    private val hash = "a".repeat(64)
    private val tenant = "22222222-2222-2222-2222-222222222222"
    private val secret = "SECRET-OPERATOR-TOKEN-4155"
    private val t0 = Instant.parse("2026-11-15T10:00:00Z")
    private val expires = t0.plusSeconds(900)

    private class MovingClock(
        var now: Instant,
    ) : Clock() {
        override fun getZone(): ZoneId = ZoneOffset.UTC

        override fun withZone(zone: ZoneId): Clock = this

        override fun instant(): Instant = now
    }

    private class Store(
        var row: SessionRow?,
        val clock: MovingClock,
    ) : SessionStore {
        var attaches = 0

        override fun find(hash: String): SessionRow? =
            row?.takeIf { it.hash == hash }?.let { r ->
                r.copy(operator = r.operator?.takeIf { it.expiresAt.isAfter(clock.now) })
            }

        override fun touch(
            hash: String,
            now: Instant,
        ) = Unit

        override fun delete(hash: String) {
            row = null
        }

        override fun attachOperatorSession(
            hash: String,
            operator: OperatorSession,
        ): Boolean {
            val current = row?.takeIf { it.hash == hash } ?: return false
            attaches++
            row = current.copy(operator = operator)
            return true
        }
    }

    private class Access(
        var answer: AccessAnswer,
    ) : AccessOperatorSessions {
        val calls = mutableListOf<Pair<String, String>>()

        override fun open(
            tenantRef: String,
            caseId: String,
        ): AccessAnswer {
            calls += tenantRef to caseId
            return answer
        }
    }

    private val clock = MovingClock(t0)
    private val consoleRow = SessionRow(hash, "p1", "11111111-1111-1111-1111-111111111111", 1, t0, t0)

    private fun opened(mode: ContentMode = ContentMode.MASKED) = AccessAnswer.Opened(secret, expires, mode)

    private fun useCase(
        store: Store,
        access: Access,
    ) = OperatorSessionUseCase(store, access, clock)

    private fun case(vararg points: Int) = String(points, 0, points.size)

    @Test
    fun `INV-AC-23363 every refused case reference makes no call to access-svc`() {
        val refused =
            mapOf(
                "missing" to null,
                "empty" to "",
                "only the whitespace set" to
                    " " + case(0x00A0, 0x1680, 0x2000, 0x200A, 0x2028, 0x2029, 0x202F, 0x205F, 0x3000, 0xFEFF),
                "65 code points" to "a".repeat(65),
                "one colon" to "case:1",
                "U+0007" to "ab" + case(0x07),
                "U+009F" to "ab" + case(0x9F),
                "U+007F" to "ab" + case(0x7F),
                "65 supplementary code points" to case(*IntArray(65) { 0x1F600 }),
            )
        for ((name, value) in refused) {
            val access = Access(opened())
            val store = Store(consoleRow, clock)
            useCase(store, access).open(hash, tenant, value) shouldBe OpenOperatorSessionResult.InvalidCaseReference
            check(access.calls.isEmpty()) { "$name reached access-svc" }
            store.attaches shouldBe 0
        }
    }

    @Test
    fun `INV-AC-23363 a 64 code point reference is accepted and a supplementary character counts as one`() {
        CaseReference.isValid("a".repeat(64)) shouldBe true
        CaseReference.isValid(case(*IntArray(64) { 0x1F600 })) shouldBe true
        CaseReference.isValid(case(*IntArray(65) { 0x1F600 })) shouldBe false
        CaseReference.isValid(" a") shouldBe true
        CaseReference.isValid("a b") shouldBe true
    }

    @Test
    fun `INV-AC-23363 a valid open makes exactly one call with the chosen tenant and case and stores the answer`() {
        val access = Access(opened(ContentMode.ELEVATED))
        val store = Store(consoleRow, clock)
        val result = useCase(store, access).open(hash, tenant, "CASE-77")
        result shouldBe OpenOperatorSessionResult.Opened(expires, ContentMode.ELEVATED)
        access.calls shouldBe listOf(tenant to "CASE-77")
        store.row?.operator shouldBe OperatorSession(tenant, secret, expires, ContentMode.ELEVATED)
    }

    @Test
    fun `INV-AC-23363 the result and the answer hold no token`() {
        val result = useCase(Store(consoleRow, clock), Access(opened())).open(hash, tenant, "CASE-77")
        result.toString().contains(secret) shouldBe false
        opened().toString().contains(secret) shouldBe false
        OperatorSession(tenant, secret, expires, ContentMode.MASKED).toString().contains(secret) shouldBe false
        consoleRow
            .copy(operator = OperatorSession(tenant, secret, expires, ContentMode.MASKED))
            .toString()
            .contains(secret) shouldBe false
    }

    @Test
    fun `INV-AC-23363 a refused or failed answer stores nothing and keeps an earlier operator session`() {
        val earlier = OperatorSession(tenant, "earlier", expires, ContentMode.MASKED)
        val answers = listOf(403, 404, 400).map { AccessAnswer.Refused(it) } + AccessAnswer.Failed
        for (answer in answers) {
            val store = Store(consoleRow.copy(operator = earlier), clock)
            val access = Access(answer)
            val result = useCase(store, access).open(hash, tenant, "CASE-77")
            val expected =
                if (answer is AccessAnswer.Refused) {
                    OpenOperatorSessionResult.Refused(answer.status)
                } else {
                    OpenOperatorSessionResult.Unavailable
                }
            result shouldBe expected
            store.attaches shouldBe 0
            store.row?.operator shouldBe earlier
        }
    }

    @Test
    fun `INV-AC-23363 with no console session no call is made and nothing is stored`() {
        val access = Access(opened())
        val store = Store(null, clock)
        useCase(store, access).open(hash, tenant, "CASE-77") shouldBe OpenOperatorSessionResult.NoConsoleSession
        access.calls shouldBe emptyList()
        store.attaches shouldBe 0
    }

    @Test
    fun `INV-AC-23363 a tenant that is not a uuid is refused before any call`() {
        val access = Access(opened())
        val store = Store(consoleRow, clock)
        for (bad in listOf("", "t-1", "2222", "22222222-2222-2222-2222-22222222222")) {
            useCase(store, access).open(hash, bad, "CASE-77") shouldBe OpenOperatorSessionResult.InvalidTenant
        }
        access.calls shouldBe emptyList()
    }

    @Test
    fun `INV-AC-23363 the operator calls read the stored token and tenant until expiresAt and then get nothing`() {
        val store = Store(consoleRow, clock)
        val use = useCase(store, Access(opened()))
        use.current(hash) shouldBe null
        use.open(hash, tenant, "CASE-77")
        use.current(hash) shouldBe OperatorSession(tenant, secret, expires, ContentMode.MASKED)
        clock.now = expires.minusSeconds(1)
        (use.current(hash) != null) shouldBe true
        clock.now = expires
        use.current(hash) shouldBe null
        clock.now = expires.plusSeconds(1)
        use.current(hash) shouldBe null
    }

    @Test
    fun `INV-AC-23363 a second open replaces the first`() {
        val store = Store(consoleRow, clock)
        val access = Access(opened())
        val use = useCase(store, access)
        use.open(hash, tenant, "CASE-1")
        access.answer = AccessAnswer.Opened("second", expires.plusSeconds(60), ContentMode.ELEVATED)
        use.open(hash, tenant, "CASE-2")
        use.current(hash)?.token shouldBe "second"
        access.calls.map { it.second } shouldBe listOf("CASE-1", "CASE-2")
    }
}
