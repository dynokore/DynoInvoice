package com.dynokore.platform.crypto

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PrimitivesTest {
    @Test
    fun `SHA-256 of the empty input is the FIPS 180-4 value`() {
        assertEquals(
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            Hashing.hex(HashAlgorithm.SHA_256, ByteArray(0)),
        )
        val digest = Hashing.digest(HashAlgorithm.SHA_256, "abc".toByteArray())
        assertEquals(HashAlgorithm.SHA_256.digestLengthBytes, digest.size)
    }

    @Test
    fun `SHA-256 of abc is the FIPS 180-4 value`() {
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            Hashing.hex(HashAlgorithm.SHA_256, "abc".toByteArray()),
        )
    }

    @Test
    fun `HMAC-SHA-256 matches RFC 4231 test case 6`() {
        val key = MacKey(MacAlgorithm.HMAC_SHA_256, ByteArray(131) { 0xaa.toByte() })
        val data = "Test Using Larger Than Block-Size Key - Hash Key First".toByteArray()
        val tag = Macs.tag(key, data)
        assertEquals("60e431591ee0b67f0d8a26aacbf5b77f8e0bc6213728c5140546040f0ee37f54", Hex.encode(tag))
        assertTrue(Macs.verify(key, data, tag))
        assertFalse(Macs.verify(key, data + 0, tag))
        assertFalse(Macs.verify(key, data, tag.copyOf(16)))
    }

    @Test
    fun `an HMAC key shorter than the tag is refused and never printed`() {
        assertThrows(IllegalArgumentException::class.java) { MacKey(MacAlgorithm.HMAC_SHA_256, ByteArray(31)) }
        val key = MacKey.generate(MacAlgorithm.HMAC_SHA_256)
        assertEquals("MacKey(HMAC_SHA_256, 32B)", key.toString())
    }

    @Test
    fun `tokens are URL-safe, unpadded and of at least 128 bits`() {
        val token = SecureTokens.urlSafeToken()
        assertTrue(Regex("^[A-Za-z0-9_-]{43}$").matches(token), token)
        assertNotEquals(token, SecureTokens.urlSafeToken())
        assertThrows(IllegalArgumentException::class.java) { SecureTokens.urlSafeToken(15) }
        assertThrows(IllegalArgumentException::class.java) { SecureTokens.randomBytes(0) }
    }

    @Test
    fun `numeric passcodes have exactly the requested digits`() {
        repeat(200) { assertTrue(Regex("^[0-9]{6}$").matches(SecureTokens.numericPasscode(6))) }
        assertThrows(IllegalArgumentException::class.java) { SecureTokens.numericPasscode(0) }
        assertThrows(IllegalArgumentException::class.java) { SecureTokens.numericPasscode(19) }
    }

    @Test
    fun `constant-time equality`() {
        assertTrue(ConstantTime.equals(byteArrayOf(1, 2), byteArrayOf(1, 2)))
        assertFalse(ConstantTime.equals(byteArrayOf(1, 2), byteArrayOf(1, 3)))
        assertFalse(ConstantTime.equals(byteArrayOf(1, 2), byteArrayOf(1)))
    }
}
