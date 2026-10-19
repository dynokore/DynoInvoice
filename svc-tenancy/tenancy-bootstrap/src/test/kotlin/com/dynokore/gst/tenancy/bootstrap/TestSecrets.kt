package com.dynokore.gst.tenancy.bootstrap

import java.nio.file.Files
import java.nio.file.Path

/** A mounted secret directory (STK-29: secrets are mounted files, never properties) with the secrets the service reads. */
object TestSecrets {
    const val BINDING_KEY = "00112233445566778899aabbccddeeff00112233445566778899aabbccddeeff"

    fun mount(
        databasePassword: String = "unused-in-composition-tests",
        bindingKey: String = BINDING_KEY,
    ): Path {
        val root = Files.createTempDirectory("tenancy-secrets")
        write(root, "tenancy/binding-key-current", bindingKey)
        write(root, "db/tenancy_runtime", databasePassword)
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
