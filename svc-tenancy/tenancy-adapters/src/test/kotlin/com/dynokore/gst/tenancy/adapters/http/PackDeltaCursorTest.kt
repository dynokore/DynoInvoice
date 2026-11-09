package com.dynokore.gst.tenancy.adapters.http

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

/**
 * The cursor of getCalendarPackDelta (INV-Q-16810 C): built like the cursor of listPeriodRecords with its own layout
 * label; the tag covers the entityRef, the user ID, packIdentifier and version. Each probe is its own test.
 */
class PackDeltaCursorTest {
    @TempDir
    lateinit var mount: Path

    private val key = "k".repeat(32)
    private val other = "o".repeat(32)
    private val at = Instant.parse("2026-08-15T02:00:00Z")
    private val last = "2026-06"

    private fun put(
        path: String,
        value: String,
    ) {
        val file = mount.resolve(path)
        Files.createDirectories(file.parent)
        Files.writeString(file, value)
    }

    private fun cursors() = PackDeltaCursor(MountedFileSecretStore(mount))

    private fun issued(now: Instant = at): String {
        put(PeriodListCursor.CURRENT_SECRET, key)
        return cursors().issue(scope(), last, now)
    }

    private fun scope(
        user: String = "USR-1",
        entity: String = "ENT-1",
        pack: String = "SG-CAL",
        version: String = "2026.2",
    ) = PackDeltaScope(entity, user, pack, version)

    private fun open(
        token: String,
        scope: PackDeltaScope = scope(),
        now: Instant = at,
    ) = cursors().open(token, scope, now)

    private fun refusalOf(block: () -> Unit): ApiRefusal? =
        try {
            block()
            null
        } catch (e: ApiRefusal) {
            e
        }

    private fun badRequest(block: () -> Unit) {
        val refusal = refusalOf(block)
        refusal?.code shouldBe ClosedErrorCode.CONTRACT_VIOLATION
        refusal?.detail shouldBe PackDeltaCursor.REFUSED
    }

    @Test
    fun `a cursor opens to the last periodRef it was issued for`() {
        open(issued()) shouldBe last
    }

    @Test
    fun `the token has the layout pd1 and three parts and carries neither the key nor the user nor the entity`() {
        val token = issued()
        token.split('.').size shouldBe 3
        token.startsWith("pd1.") shouldBe true
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
    fun `the same cursor with another user ID is refused`() {
        badRequest { open(issued(), scope(user = "USR-2")) }
    }

    @Test
    fun `the same cursor with another entityRef is refused`() {
        badRequest { open(issued(), scope(entity = "ENT-2")) }
    }

    @Test
    fun `the same cursor with another packIdentifier is refused`() {
        badRequest { open(issued(), scope(pack = "SG-CAL2")) }
    }

    @Test
    fun `the same cursor with another version is refused`() {
        badRequest { open(issued(), scope(version = "2026.3")) }
    }

    @Test
    fun `a cursor issued PT24H plus one second ago is refused`() {
        badRequest { open(issued(), now = at.plus(Duration.ofHours(24)).plusSeconds(1)) }
    }

    @Test
    fun `a cursor issued PT23H59M59S ago is accepted`() {
        open(issued(), now = at.plus(Duration.ofHours(24)).minusSeconds(1)) shouldBe last
    }

    @Test
    fun `a cursor signed with the previous key only is accepted and the previous key never signs`() {
        val token = issued()
        put(PeriodListCursor.CURRENT_SECRET, other)
        put(PeriodListCursor.PREVIOUS_SECRET, key)
        open(token) shouldBe last
        val fresh = cursors().issue(scope(), last, at)
        put(PeriodListCursor.PREVIOUS_SECRET, "p".repeat(32))
        open(fresh) shouldBe last
    }

    @Test
    fun `no current key is a 5xx for issuing and for opening, never an unsigned page`() {
        val token = issued()
        Files.delete(mount.resolve(PeriodListCursor.CURRENT_SECRET))
        refusalOf {
            cursors().issue(scope(), last, at)
        }?.code shouldBe ClosedErrorCode.INTERNAL_ERROR
        refusalOf { open(token) }?.code shouldBe ClosedErrorCode.INTERNAL_ERROR
    }

    @Test
    fun `a 31 byte key is a 5xx and a 32 byte key is accepted`() {
        put(PeriodListCursor.CURRENT_SECRET, "k".repeat(31))
        refusalOf {
            cursors().issue(scope(), last, at)
        }?.code shouldBe ClosedErrorCode.INTERNAL_ERROR
        put(PeriodListCursor.CURRENT_SECRET, "k".repeat(32))
        open(cursors().issue(scope(), last, at)) shouldBe last
    }

    @Test
    fun `a listPeriodRecords token is refused here and a pack delta token is refused there`() {
        put(PeriodListCursor.CURRENT_SECRET, key)
        val listToken =
            PeriodListCursor(MountedFileSecretStore(mount))
                .issue(
                    "ENT-1",
                    "USR-1",
                    com.dynokore.gst.tenancy.application.period
                        .PagePosition(java.time.LocalDate.parse("2026-06-01"), last),
                    at,
                )
        badRequest { open(listToken) }
        refusalOf {
            PeriodListCursor(MountedFileSecretStore(mount)).open(issued(), "ENT-1", "USR-1", at)
        }?.code shouldBe ClosedErrorCode.CONTRACT_VIOLATION
    }

    @Test
    fun `malformed tokens are refused with the one detail`() {
        val parts = issued().split('.')
        listOf("", "pd1", "pd1.${parts[1]}", "v1.${parts[1]}.${parts[2]}", "pd1..", "pd1.%%%.***").forEach { bad ->
            badRequest { open(bad) }
        }
    }
}
