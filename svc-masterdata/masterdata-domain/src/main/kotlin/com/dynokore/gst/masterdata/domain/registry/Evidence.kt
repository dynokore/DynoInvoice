package com.dynokore.gst.masterdata.domain.registry

import java.time.Instant

private const val HEX = 16
private const val UNICODE_DIGITS = 4

/** Which registry a lookup asked. */
enum class RegistryKind { DIRECTORY, REGISTER }

/**
 * One registry lookup as evidence (INV-REQ-802 b2, INV-REQ-478): the value queried, the response and the time. [bytes]
 * is the text that is stored and hashed, so the hash of the stored record is the hash the event carries.
 */
data class LookupEvidence(
    val kind: RegistryKind,
    val queried: String,
    val response: String,
    val at: Instant,
) {
    val bytes: String
        get() =
            "{\"kind\":${quoted(kind.name)},\"queried\":${quoted(queried)}," +
                "\"response\":${quoted(response)},\"at\":${quoted(at.toString())}}"

    private fun quoted(text: String): String {
        val out = StringBuilder("\"")
        text.forEach {
            when {
                it == '"' -> out.append("\\\"")
                it == '\\' -> out.append("\\\\")
                it < ' ' -> out.append("\\u").append(it.code.toString(HEX).padStart(UNICODE_DIGITS, '0'))
                else -> out.append(it)
            }
        }
        return out.append('"').toString()
    }
}

/** The network-registration item of an entity (INV-REQ-320): outstanding until a lookup finds the entity. */
enum class RegistrationState { OUTSTANDING, CONFIRMED }

data class NetworkRegistration(
    val entity: String,
    val state: RegistrationState,
    val apReference: String?,
    val confirmedAt: Instant?,
    val lastCheckedAt: Instant?,
) {
    init {
        require((state == RegistrationState.CONFIRMED) == (confirmedAt != null)) {
            "a confirmation time is held exactly when the item is confirmed"
        }
        require(state == RegistrationState.CONFIRMED || apReference == null) {
            "only a confirmed item has an AP reference"
        }
    }

    companion object {
        /** An entity never confirmed reads outstanding. */
        fun outstanding(entity: String) = NetworkRegistration(entity, RegistrationState.OUTSTANDING, null, null, null)
    }
}
