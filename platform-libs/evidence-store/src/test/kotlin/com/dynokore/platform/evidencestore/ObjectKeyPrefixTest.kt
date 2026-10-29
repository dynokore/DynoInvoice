package com.dynokore.platform.evidencestore

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.time.Instant

class ObjectKeyPrefixTest {
    @Test
    fun `refuses a prefix without a trailing slash`() {
        assertThrows(IllegalArgumentException::class.java) { ObjectKeyPrefix("period-digests/T/E1") }
    }

    @Test
    fun `refuses a blank prefix`() {
        assertThrows(IllegalArgumentException::class.java) { ObjectKeyPrefix("") }
        assertThrows(IllegalArgumentException::class.java) { ObjectKeyPrefix("  ") }
    }

    @Test
    fun `equality is by value`() {
        assertEquals(ObjectKeyPrefix("a/"), ObjectKeyPrefix("a/"))
        assertNotEquals(ObjectKeyPrefix("a/"), ObjectKeyPrefix("a//"))
    }

    @Test
    fun `ObjectStore declares exactly put, get and bytesUnder`() {
        assertEquals(
            setOf("put", "get", "bytesUnder"),
            ObjectStore::class.java.declaredMethods
                .filterNot { it.isSynthetic }
                .map { it.name.substringBefore('-') } // value-class parameters mangle the JVM name
                .toSet(),
        )
    }

    @Test
    fun `the default bytesUnder refuses and names the implementing class`() {
        val store =
            object : ObjectStore {
                override fun put(
                    key: ObjectKey,
                    bytes: ByteArray,
                    retainUntil: Instant,
                ) = ObjectRef(key, retainUntil)

                override fun get(key: ObjectKey) = ByteArray(0)
            }
        val e = assertThrows(UnsupportedOperationException::class.java) { store.bytesUnder(ObjectKeyPrefix("a/")) }
        assertEquals(true, e.message!!.contains(store.javaClass.name))
    }
}
