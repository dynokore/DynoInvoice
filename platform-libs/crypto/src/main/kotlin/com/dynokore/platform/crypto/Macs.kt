package com.dynokore.platform.crypto

import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** Message authentication codes under a named [MacAlgorithm] (CS-53). Pure JDK (ARC-16). */
object Macs {
    /** The MAC tag of [data] under [key]. */
    fun tag(
        key: MacKey,
        data: ByteArray,
    ): ByteArray {
        val mac = Mac.getInstance(key.algorithm.jcaName)
        mac.init(SecretKeySpec(key.material(), key.algorithm.jcaName))
        return mac.doFinal(data)
    }

    /** Whether [expectedTag] is the MAC tag of [data] under [key], compared in constant time (CS-53). */
    fun verify(
        key: MacKey,
        data: ByteArray,
        expectedTag: ByteArray,
    ): Boolean = ConstantTime.equals(tag(key, data), expectedTag)
}
