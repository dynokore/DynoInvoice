package com.dynokore.platform.crypto

import java.security.MessageDigest

/** Constant-time comparison of MACs, digests and passcodes (CS-53: `MessageDigest.isEqual`). */
object ConstantTime {
    fun equals(
        a: ByteArray,
        b: ByteArray,
    ): Boolean = MessageDigest.isEqual(a, b)
}
