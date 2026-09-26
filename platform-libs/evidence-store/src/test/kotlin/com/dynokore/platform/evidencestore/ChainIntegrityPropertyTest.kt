package com.dynokore.platform.evidencestore

import io.kotest.property.Arb
import io.kotest.property.arbitrary.byte
import io.kotest.property.arbitrary.byteArray
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.list
import io.kotest.property.checkAll
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * FR-9.15 behaviour 2/4's own invariant, as a property (STK-38, CS-38, done-when:
 * "property tests for chain integrity"): a chain built by repeatedly calling
 * [HashChain.link] always verifies as [ChainVerificationResult.Valid], for any
 * sequence of record contents whatsoever; and corrupting exactly one stored
 * hash is always caught, at exactly that position, never a later or earlier
 * one. Pure — no database — so this runs at in-memory speed with a fresh seed
 * every run (CS-41), independent of the JDBC-backed proof
 * `EvidenceLedgerPostgresIT` gives against a real table.
 */
class ChainIntegrityPropertyTest {
    private val contentArb: Arb<ByteArray> = Arb.byteArray(Arb.int(0..64), Arb.byte())
    private val chainArb: Arb<List<ByteArray>> = Arb.list(contentArb, 1..30)

    // Block bodies, not `= runBlocking { ... }`: a JUnit5 @Test method must
    // compile to a `void`-returning one, and `checkAll` (unlike plain Unit)
    // returns a result the compiler would otherwise infer as this function's
    // own return type.
    @Test
    fun `a chain built by link always verifies as Valid`() {
        runBlocking {
            checkAll(chainArb) { contents ->
                val hashes = buildChain(contents)
                assertEquals(ChainVerificationResult.Valid(contents.size), HashChain.verify(contents, hashes))
            }
        }
    }

    @Test
    fun `corrupting exactly one stored hash is caught at exactly that position`() {
        runBlocking {
            checkAll(chainArb, Arb.int(0..Int.MAX_VALUE)) { contents, seed ->
                val hashes = buildChain(contents).toMutableList()
                val corruptedIndex = Math.floorMod(seed, contents.size)
                val corrupted = corrupt(hashes[corruptedIndex])
                hashes[corruptedIndex] = corrupted

                val result = HashChain.verify(contents, hashes)
                check(result is ChainVerificationResult.Broken) { "expected Broken, was $result" }
                assertEquals(corruptedIndex.toLong(), result.atSequence)
            }
        }
    }

    private fun buildChain(contents: List<ByteArray>): List<ContentHash> {
        var previous: ContentHash? = null
        val hashes = mutableListOf<ContentHash>()
        for (content in contents) {
            val next = HashChain.link(previous, content)
            hashes += next
            previous = next
        }
        return hashes
    }

    private val hexDigits = "0123456789abcdef"

    /** A hash guaranteed different from [hash]: the next hex digit, cyclically, in place of its first character. */
    private fun corrupt(hash: ContentHash): ContentHash {
        val next = hexDigits[(hexDigits.indexOf(hash.hex[0]) + 1) % hexDigits.length]
        return ContentHash(next + hash.hex.substring(1))
    }
}
