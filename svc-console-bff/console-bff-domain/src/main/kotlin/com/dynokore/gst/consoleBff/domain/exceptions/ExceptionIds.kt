package com.dynokore.gst.consoleBff.domain.exceptions

import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.UUID

/** Fixed namespace of the exception ids (RFC 9562 name-based UUID); declared once. */
const val EXCEPTION_NAMESPACE = "5c0b4d8e-3a6f-4f0e-9d2b-7e1a64c8b3f5"

private const val VERSION_5 = 0x50
private const val VERSION_MASK = 0x0f
private const val VARIANT_RFC = 0x80
private const val VARIANT_MASK = 0x3f
private const val UUID_BYTES = 16
private const val VERSION_BYTE = 6
private const val VARIANT_BYTE = 8

/**
 * RFC 9562 version 5 (SHA-1) UUID of [EXCEPTION_NAMESPACE] and of the name built from the four values, each written as
 * its UTF-8 byte length in decimal, a colon and the value, joined without a separator (no two tuples give one name).
 */
fun exceptionId(
    tenant: String,
    entityRef: String,
    docUuid: String,
    code: String,
): UUID {
    val name =
        listOf(tenant, entityRef, docUuid, code).joinToString("") { value ->
            "${value.toByteArray(Charsets.UTF_8).size}:$value"
        }
    val namespace = UUID.fromString(EXCEPTION_NAMESPACE)
    val digest =
        MessageDigest.getInstance("SHA-1").run {
            val ns = ByteBuffer.allocate(UUID_BYTES)
            ns.putLong(namespace.mostSignificantBits).putLong(namespace.leastSignificantBits)
            update(ns.array())
            update(name.toByteArray(Charsets.UTF_8))
            digest()
        }
    val bytes = digest.copyOf(UUID_BYTES)
    bytes[VERSION_BYTE] = ((bytes[VERSION_BYTE].toInt() and VERSION_MASK) or VERSION_5).toByte()
    bytes[VARIANT_BYTE] = ((bytes[VARIANT_BYTE].toInt() and VARIANT_MASK) or VARIANT_RFC).toByte()
    val buffer = ByteBuffer.wrap(bytes)
    return UUID(buffer.long, buffer.long)
}
