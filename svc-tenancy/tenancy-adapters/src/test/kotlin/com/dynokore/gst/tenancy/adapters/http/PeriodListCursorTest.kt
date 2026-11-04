package com.dynokore.gst.tenancy.adapters.http

import com.dynokore.gst.tenancy.application.period.PagePosition
import com.dynokore.platform.serviceruntime.binding.MountedFileSecretStore
import com.dynokore.platform.serviceruntime.http.ClosedErrorCode
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.util.Base64

/**
 * The cursor of listPeriodRecords (ruling INV-Q-16673): an HMAC-SHA256 tag from the platform crypto module, the key
 * read through the secret store at every use, the previous key accepted for verification only. Each probe of the
 * ruling is its own test.
 */
class PeriodListCursorTest {
    @TempDir
    lateinit var mount: Path

    private val key = "k".repeat(32)
    private val other = "o".repeat(32)
    private val at = Instant.parse("2026-08-15T02:00:00Z")
    private val last = PagePosition(LocalDate.parse("2026-06-01"), "2026M06")

    private fun put(
        path: String,
        value: String,
    ) {
        val file = mount.resolve(path)
        Files.createDirectories(file.parent)
        Files.writeString(file, value)
    }

    private fun cursors() = PeriodListCursor(MountedFileSecretStore(mount))

    private fun issued(
        user: String = "USR-1",
        entity: String = "ENT-1",
        now: Instant = at,
    ): String {
        put(PeriodListCursor.CURRENT_SECRET, key)
        return cursors().issue(entity, user, last, now)
    }

    private fun refusalOf(block: () -> Unit): ApiRefusal? =
        try {
            block()
            null
        } catch (e: ApiRefusal) {
            e
        }

    private fun open(
        token: String,
        user: String = "USR-1",
        entity: String = "ENT-1",
        now: Instant = at,
    ) = cursors().open(token, entity, user, now)

    private fun badRequest(block: () -> Unit) {
        val refusal = refusalOf(block)
        refusal?.code shouldBe ClosedErrorCode.CONTRACT_VIOLATION
        refusal?.detail shouldBe PeriodListCursor.REFUSED
    }

    @Test
    fun `a cursor opens to the position it was issued for`() {
        open(issued()) shouldBe last
    }

    @Test
    fun `the token has the layout v1 and three parts and carries neither the key nor the user nor the entity`() {
        val token = issued()
        token.split('.').size shouldBe 3
        token.startsWith("v1.") shouldBe true
        token shouldNotContain key
        token shouldNotContain "USR-1"
        token shouldNotContain "ENT-1"
    }

    @Test
    fun `a tag changed by one character is refused`() {
        val token = issued()
        val tag = token.substringAfterLast('.')
        val changed = (if (tag.first() == 'A') 'B' else 'A') + tag.drop(1)
        badRequest { open(token.removeSuffix(tag) + changed) }
    }

    @Test
    fun `a position changed and re-encoded keeps the old tag and is refused`() {
        val token = issued()
        val parts = token.split('.')
        val payload = String(Base64.getUrlDecoder().decode(parts[1]), Charsets.UTF_8).replace("2026M06", "2027M12")
        val forged = Base64.getUrlEncoder().withoutPadding().encodeToString(payload.toByteArray(Charsets.UTF_8))
        badRequest { open("${parts[0]}.$forged.${parts[2]}") }
    }

    @Test
    fun `the same cursor with another user ID is refused`() {
        badRequest { open(issued(), user = "USR-2") }
    }

    @Test
    fun `the same cursor with another entityRef is refused`() {
        badRequest { open(issued(), entity = "ENT-2") }
    }

    @Test
    fun `a cursor issued PT24H plus one second ago is refused and one issued PT23H59M59S ago is accepted`() {
        val token = issued()
        badRequest { open(token, now = at.plus(Duration.ofHours(24)).plusSeconds(1)) }
        open(token, now = at.plus(Duration.ofHours(24)).minusSeconds(1)) shouldBe last
    }

    @Test
    fun `a cursor issued in the future is refused`() {
        badRequest { open(issued(now = at.plusSeconds(60))) }
    }

    @Test
    fun `a cursor signed with the previous key is accepted while the current key signs new ones`() {
        val token = issued()
        put(PeriodListCursor.CURRENT_SECRET, other)
        put(PeriodListCursor.PREVIOUS_SECRET, key)
        open(token) shouldBe last
        val fresh = cursors().issue("ENT-1", "USR-1", last, at)
        put(PeriodListCursor.PREVIOUS_SECRET, "p".repeat(32))
        open(fresh) shouldBe last
    }

    @Test
    fun `the previous key is for verification only and a cursor of no known key is refused`() {
        val token = issued()
        put(PeriodListCursor.CURRENT_SECRET, other)
        put(PeriodListCursor.PREVIOUS_SECRET, key)
        cursors().issue("ENT-1", "USR-1", last, at).let { open(it) shouldBe last }
        Files.delete(mount.resolve(PeriodListCursor.PREVIOUS_SECRET))
        badRequest { open(token) }
    }

    @Test
    fun `no current key is a 5xx for issuing and for opening, never an unsigned page`() {
        val token = issued()
        Files.delete(mount.resolve(PeriodListCursor.CURRENT_SECRET))
        refusalOf { cursors().issue("ENT-1", "USR-1", last, at) }?.code shouldBe ClosedErrorCode.INTERNAL_ERROR
        refusalOf { open(token) }?.code shouldBe ClosedErrorCode.INTERNAL_ERROR
    }

    @Test
    fun `a blank key and a 31 byte key are a 5xx and a 32 byte key is accepted`() {
        put(PeriodListCursor.CURRENT_SECRET, " ")
        refusalOf { cursors().issue("ENT-1", "USR-1", last, at) }?.code shouldBe ClosedErrorCode.INTERNAL_ERROR
        put(PeriodListCursor.CURRENT_SECRET, "k".repeat(31))
        refusalOf { cursors().issue("ENT-1", "USR-1", last, at) }?.code shouldBe ClosedErrorCode.INTERNAL_ERROR
        put(PeriodListCursor.CURRENT_SECRET, "k".repeat(32))
        open(cursors().issue("ENT-1", "USR-1", last, at)) shouldBe last
    }

    @Test
    fun `a secret path that only looks like the key is not read`() {
        put("tenancy/period-list-cursor-key ", key)
        put("tenancy/period-list-cursors-key", key)
        put("tenancy/Period-list-cursor-key", key)
        refusalOf { cursors().issue("ENT-1", "USR-1", last, at) }?.code shouldBe ClosedErrorCode.INTERNAL_ERROR
    }

    @Test
    fun `the key is read at every use, so a rotated file needs no restart`() {
        val instance = cursors()
        put(PeriodListCursor.CURRENT_SECRET, key)
        val token = instance.issue("ENT-1", "USR-1", last, at)
        put(PeriodListCursor.CURRENT_SECRET, other)
        refusalOf { instance.open(token, "ENT-1", "USR-1", at) }?.code shouldBe ClosedErrorCode.CONTRACT_VIOLATION
    }

    @Test
    fun `malformed tokens are refused with the one detail and no part of the token`() {
        val token = issued()
        val parts = token.split('.')
        listOf(
            "",
            "v1",
            "v1.${parts[1]}",
            "$token.x",
            "v2.${parts[1]}.${parts[2]}",
            "V1.${parts[1]}.${parts[2]}",
            "v1.${parts[1]}=.${parts[2]}",
            "v1.${parts[1]}.${parts[2]}=",
            "v1.${parts[1].dropLast(1)}.${parts[2]}",
            "v1..",
            "v1.%%%.***",
        ).forEach { bad ->
            val refusal = refusalOf { open(bad) }
            refusal?.code shouldBe ClosedErrorCode.CONTRACT_VIOLATION
            refusal?.detail shouldBe PeriodListCursor.REFUSED
            refusal?.message?.let { m -> if (bad.length > 8) m shouldNotContain bad }
        }
    }
}
