package com.dynokore.gst.access.bootstrap

import com.nimbusds.jose.jwk.Curve
import com.nimbusds.jose.jwk.ECKey
import com.nimbusds.jose.jwk.gen.ECKeyGenerator
import java.nio.file.Files
import java.nio.file.Path

/**
 * A mounted secret directory (STK-29: secrets are mounted files, never properties) with the secrets the service reads.
 */
object TestSecrets {
    const val BINDING_KEY = "00112233445566778899aabbccddeeff00112233445566778899aabbccddeeff"
    const val CURSOR_KEY = "test-cursor-integrity-key-0123456789"
    const val TOKEN_ISSUER = "https://access.test.invalid"
    const val TOKEN_AUDIENCE = "https://api.test.invalid"
    const val ROTATION_INTERVAL = "PT1H"

    /** The token properties every context needs: issuer, audience and rotation interval have no default. */
    val TOKEN_PROPERTIES: Array<String> =
        arrayOf(
            "access.token.issuer=$TOKEN_ISSUER",
            "access.token.audience=$TOKEN_AUDIENCE",
            "access.token.key-rotation-interval=$ROTATION_INTERVAL",
        )

    fun newSigningKey(): ECKey = ECKeyGenerator(Curve.P_256).generate()

    fun mount(
        databasePassword: String = "unused-in-composition-tests",
        bindingKey: String = BINDING_KEY,
        signingKey: ECKey? = newSigningKey(),
    ): Path {
        val root = Files.createTempDirectory("access-secrets")
        write(root, "tenancy/binding-key-current", bindingKey)
        write(root, "access/revocation-cursor-key", CURSOR_KEY)
        write(root, "db/access_runtime", databasePassword)
        signingKey?.let { replaceSigningKey(root, it) }
        root.toFile().deleteOnExit()
        return root
    }

    /** Replaces the token signing key of a mounted directory, as an operator rotates it. */
    fun replaceSigningKey(
        root: Path,
        key: ECKey,
    ) = write(root, "access/token-signing-key", key.toJSONString())

    private fun write(
        root: Path,
        name: String,
        value: String,
    ) {
        val file = root.resolve(name)
        Files.createDirectories(file.parent)
        Files.writeString(file, value)
        file.toFile().deleteOnExit()
    }
}
