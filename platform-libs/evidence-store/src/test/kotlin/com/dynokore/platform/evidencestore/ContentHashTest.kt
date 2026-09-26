package com.dynokore.platform.evidencestore

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.nio.charset.StandardCharsets

class ContentHashTest {
    @Test
    fun `of computes the known SHA-256 of a fixed input`() {
        // echo -n "" | sha256sum
        val hash = ContentHash.of(ByteArray(0))
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", hash.hex)
    }

    @Test
    fun `of is deterministic for the same bytes`() {
        val bytes = "evidence".toByteArray(StandardCharsets.UTF_8)
        assertEquals(ContentHash.of(bytes), ContentHash.of(bytes))
    }

    @Test
    fun `refuses a value that is not 64 lower-case hex characters`() {
        assertThrows(IllegalArgumentException::class.java) { ContentHash("not-a-hash") }
        assertThrows(IllegalArgumentException::class.java) { ContentHash("AB".repeat(32)) } // upper-case refused
    }
}
