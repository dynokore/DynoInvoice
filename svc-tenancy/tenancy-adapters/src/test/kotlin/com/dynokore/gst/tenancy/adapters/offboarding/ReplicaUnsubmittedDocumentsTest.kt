package com.dynokore.gst.tenancy.adapters.offboarding

import com.dynokore.gst.tenancy.adapters.events.EntityUnsubmittedCount
import com.dynokore.gst.tenancy.adapters.events.UnsubmittedCountMeasurement
import com.dynokore.gst.tenancy.application.offboarding.UnavailableInput
import com.dynokore.gst.tenancy.domain.OffboardingScope
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset

/** INV-AC-22872 on the adapter alone: fake replica, fake entity check, fixed clock, maximum age 15 minutes. */
class ReplicaUnsubmittedDocumentsTest {
    private val now = Instant.parse("2026-11-20T10:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)
    private val maxAge = Duration.ofMinutes(15)

    private fun row(
        ago: Duration,
        total: Int = 5,
        items: List<EntityUnsubmittedCount> = listOf(EntityUnsubmittedCount("E1", 3), EntityUnsubmittedCount("E2", 2)),
    ) = StoredUnsubmittedCount(UnsubmittedCountMeasurement(now.minus(ago), total, items), now.minus(ago))

    private fun port(
        entity: Boolean = true,
        read: () -> StoredUnsubmittedCount?,
        check: () -> Boolean = { entity },
    ) = ReplicaUnsubmittedDocuments({ read() }, { check() }, clock, maxAge)

    private fun refused(block: () -> Unit) {
        shouldThrow<UnavailableInput> { block() }.input shouldBe "unsubmitted-documents-count"
    }

    private fun count(
        p: ReplicaUnsubmittedDocuments,
        scope: OffboardingScope = OffboardingScope.TENANT,
        ref: String? = null,
    ) = p.count(scope, ref)

    @Test
    fun `INV-AC-22872 a row 14 minutes 59 seconds old gives total, entity count and 0 for an absent entity`() {
        val p = port(read = { row(Duration.ofSeconds(899)) })
        (count(p)) shouldBe 5
        (count(p, OffboardingScope.ENTITY, "E1")) shouldBe 3
        (count(p, OffboardingScope.ENTITY, "E9")) shouldBe 0
    }

    @Test
    fun `INV-AC-22872 a row received exactly 15 minutes ago is current`() {
        (count(port(read = { row(Duration.ofMinutes(15)) }))) shouldBe 5
    }

    @Test
    fun `INV-AC-22872 a row received 15 minutes 1 second ago is refused naming the input`() {
        refused { count(port(read = { row(Duration.ofSeconds(901)) })) }
        refused { count(port(read = { row(Duration.ofSeconds(901)) }), OffboardingScope.ENTITY, "E1") }
    }

    @Test
    fun `INV-AC-22872 no row is refused naming the input`() {
        refused { count(port(read = { null })) }
    }

    @Test
    fun `INV-AC-22872 a row received after the clock instant is refused naming the input`() {
        refused { count(port(read = { row(Duration.ofSeconds(-1)) })) }
    }

    @Test
    fun `INV-AC-22872 a replica read that throws is refused naming the input and never 0`() {
        refused { count(port(read = { error("replica down") })) }
        refused { count(port(entity = false, read = { error("replica down") })) }
    }

    @Test
    fun `INV-AC-22872 a tenant with no entity row of any status and no count row returns 0`() {
        (count(port(entity = false, read = { null }))) shouldBe 0
        (count(port(entity = false, read = { null }), OffboardingScope.ENTITY, "E1")) shouldBe 0
    }

    @Test
    fun `INV-AC-22872 a tenant with an entity row and no count row is refused naming the input`() {
        refused { count(port(entity = true, read = { null })) }
    }

    @Test
    fun `INV-AC-22872 an old row is refused even when the tenant has no entity row`() {
        refused { count(port(entity = false, read = { row(Duration.ofHours(1)) })) }
    }

    @Test
    fun `INV-AC-22872 an entity read that throws is refused naming the input and never 0`() {
        refused { count(port(read = { null }, check = { error("entity store down") })) }
        refused { count(port(read = { row(Duration.ZERO) }, check = { error("entity store down") })) }
    }

    @Test
    fun `INV-AC-22872 the currency rule on its boundaries`() {
        (isCurrent(now, now, maxAge)) shouldBe true
        (isCurrent(now.minus(maxAge), now, maxAge)) shouldBe true
        (isCurrent(now.minus(maxAge).minusSeconds(1), now, maxAge)) shouldBe false
        (isCurrent(now.plusSeconds(1), now, maxAge)) shouldBe false
    }
}
