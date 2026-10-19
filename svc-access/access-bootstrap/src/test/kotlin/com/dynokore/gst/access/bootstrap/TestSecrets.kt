package com.dynokore.gst.access.bootstrap

import java.nio.file.Files
import java.nio.file.Path

/** A mounted secret directory (STK-29: secrets are mounted files, never properties) with the secrets the service reads. */
object TestSecrets {
    const val BINDING_KEY = "00112233445566778899aabbccddeeff00112233445566778899aabbccddeeff"
    const val CURSOR_KEY = "test-cursor-integrity-key-0123456789"

    fun mount(
        databasePassword: String = "unused-in-composition-tests",
        bindingKey: String = BINDING_KEY,
    ): Path {
        val root = Files.createTempDirectory("access-secrets")
        write(root, "tenancy/binding-key-current", bindingKey)
        write(root, "access/revocation-cursor-key", CURSOR_KEY)
        write(root, "db/access_runtime", databasePassword)
        root.toFile().deleteOnExit()
        return root
    }

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
