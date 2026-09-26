package com.dynokore.platform.evidencestore

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test

class HashChainTest {
    @Test
    fun `the first record's link has no previous hash`() {
        val content = "first".toByteArray()
        assertEquals(ContentHash.of(content), HashChain.link(null, content))
    }

    @Test
    fun `the same content chains to a different hash depending on what precedes it`() {
        val content = "same content".toByteArray()
        val first = HashChain.link(null, content)
        val second = HashChain.link(first, content)
        assertNotEquals(first, second)
    }

    @Test
    fun `verify accepts a chain that matches what link would produce`() {
        val contents = listOf("a".toByteArray(), "b".toByteArray(), "c".toByteArray())
        val hashes = mutableListOf<ContentHash>()
        var previous: ContentHash? = null
        for (content in contents) {
            previous = HashChain.link(previous, content)
            hashes += previous
        }

        assertEquals(ChainVerificationResult.Valid(3), HashChain.verify(contents, hashes))
    }

    @Test
    fun `verify reports the first sequence whose stored hash does not match`() {
        val contents = listOf("a".toByteArray(), "b".toByteArray(), "c".toByteArray())
        val hashes = mutableListOf<ContentHash>()
        var previous: ContentHash? = null
        for (content in contents) {
            previous = HashChain.link(previous, content)
            hashes += previous
        }
        val tampered = hashes.toMutableList()
        tampered[1] = ContentHash.of("tampered".toByteArray())

        val result = HashChain.verify(contents, tampered)
        check(result is ChainVerificationResult.Broken)
        assertEquals(1L, result.atSequence)
    }
}
