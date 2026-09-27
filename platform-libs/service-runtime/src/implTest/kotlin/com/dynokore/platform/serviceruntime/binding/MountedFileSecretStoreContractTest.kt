package com.dynokore.platform.serviceruntime.binding

import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class MountedFileSecretStoreContractTest : SecretStoreContractTest() {
    @TempDir
    lateinit var mountRoot: Path

    @BeforeEach
    fun seedMount() {
        Files.writeString(mountRoot.resolve(seedRef.value), seedValue)
    }

    override fun store(): SecretStore = MountedFileSecretStore(mountRoot)

    override fun unboundRef(): SecretRef = SecretRef("never-mounted")
}
