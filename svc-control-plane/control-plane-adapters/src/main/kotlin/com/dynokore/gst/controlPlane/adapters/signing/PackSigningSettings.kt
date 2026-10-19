package com.dynokore.gst.controlPlane.adapters.signing

/**
 * Where the pack signing key lives (INV-Q-7459, STK-13). Every value is configuration; the HSM PIN and the signing
 * certificate are never here: [pinSecret] and [certificateSecret] are references the SecretStore binding resolves.
 *
 * @property ref the one `signingKeyRef` this installation signs with; any other reference is unknown
 * @property keyStoreType `PKCS11` in every profile except `test` (STK-13 rejects software-held keys)
 * @property provider the JCA provider that holds the key; null for the default lookup of [keyStoreType]
 * @property library the PKCS#11 library of the HSM, for the SunPKCS11 provider configured at start-up
 * @property alias the key entry in the key store
 * @property keyStorePath a key store file, read only for a type other than PKCS11 (profile `test`)
 * @property pinSecret the SecretStore reference of the key store PIN
 * @property certificateSecret the SecretStore reference of the signing certificate (Base64 DER)
 */
data class PackSigningSettings(
    val ref: String = "",
    val keyStoreType: String = "PKCS11",
    val provider: String? = null,
    val library: String? = null,
    val alias: String = "",
    val keyStorePath: String? = null,
    val pinSecret: String = "",
    val certificateSecret: String = "",
) {
    companion object {
        const val PREFIX = "control-plane.signing"
        const val PKCS11 = "PKCS11"
        const val TEST_PROFILE = "test"

        /**
         * Start-up check: a key store type other than PKCS11 is refused outside the profile `test`, and the settings a
         * key needs are present. Fails closed with a message that names the setting, never a value.
         */
        fun verify(
            settings: PackSigningSettings,
            activeProfiles: Collection<String>,
        ) {
            check(settings.keyStoreType == PKCS11 || TEST_PROFILE in activeProfiles) {
                "$PREFIX.key-store-type must be $PKCS11 outside the profile $TEST_PROFILE (STK-13: no software key)"
            }
            check(settings.ref.isNotBlank()) { "$PREFIX.ref is not set" }
            check(settings.alias.isNotBlank()) { "$PREFIX.alias is not set" }
            check(settings.pinSecret.isNotBlank()) { "$PREFIX.pin-secret is not set" }
            check(settings.certificateSecret.isNotBlank()) { "$PREFIX.certificate-secret is not set" }
        }
    }
}
