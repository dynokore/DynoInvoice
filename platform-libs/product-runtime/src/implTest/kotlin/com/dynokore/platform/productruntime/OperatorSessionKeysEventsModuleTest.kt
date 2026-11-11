package com.dynokore.platform.productruntime

import com.fasterxml.jackson.annotation.JsonProperty
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import kotlin.reflect.full.memberFunctions
import kotlin.reflect.jvm.isAccessible

/** INV-T-20240: the OperatorSessionKeysChanged payload type generated in its own module (STK-74). */
class OperatorSessionKeysEventsModuleTest {
    private val typeName =
        "com.dynokore.gst.productRuntimeOperatorSessionKeysEvents.adapters.events.model.OperatorSessionKeysChangedPayload"

    @Test
    fun `INV-AC-20239 the generated payload class is loadable and has the property keys`() {
        val type = Class.forName(typeName)
        // The generator names the Kotlin property `propertyKeys` (the bare name collides with Map.keys-style members);
        // the schema property `keys` is its JSON name, carried by @JsonProperty on the getter.
        type.methods.any { it.getAnnotation(JsonProperty::class.java)?.value == "keys" } shouldBe true
    }

    @Test
    fun `INV-AC-20239 a valid example payload reads through ContractJson with first key id k1`() {
        val json =
            """{"keys":[{"kid":"k1","use":"OPERATOR_SESSION","alg":"RS256","publicKey":"U0lHTkVE",""" +
                """"validFrom":"2026-10-01T00:00:00Z","validUntil":"2026-11-15T00:00:00Z"}]}"""
        // ContractJson is internal to the impl module: reached by Kotlin reflection, which resolves the mangled name.
        val reader = Class.forName("com.dynokore.platform.productruntime.feed.ContractJson").kotlin
        val read = reader.memberFunctions.first { it.name == "read" }.also { it.isAccessible = true }
        val payload = read.call(reader.objectInstance, json, Class.forName(typeName))!!
        val keys = payload.javaClass.getMethod("getPropertyKeys").invoke(payload) as List<*>
        val first = keys.first()!!
        first.javaClass.getMethod("getKid").invoke(first) shouldBe "k1"
    }
}
