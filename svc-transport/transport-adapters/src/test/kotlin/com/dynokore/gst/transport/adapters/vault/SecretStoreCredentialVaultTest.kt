package com.dynokore.gst.transport.adapters.vault

import com.dynokore.gst.transport.application.CredentialRef
import com.dynokore.platform.serviceruntime.binding.MountedFileSecretStore
import com.dynokore.platform.serviceruntime.binding.SecretStore
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/**
 * The vault adapter over the platform `SecretStore` (INV-T-18676; STK-29). The vault double is the mounted-file store
 * over a temporary directory: a file per reference, changed or removed as the vault would change it.
 */
class SecretStoreCredentialVaultTest {
    private val value = "s3cret-value-never-stored"

    private fun vault(dir: Path) = SecretStoreCredentialVault(MountedFileSecretStore(dir))

    private fun put(
        dir: Path,
        ref: String,
        content: String,
    ) {
        val file = dir.resolve(ref)
        Files.createDirectories(file.parent)
        Files.writeString(file, content)
    }

    @Test
    fun `holds answers a boolean and never the value`(
        @TempDir dir: Path,
    ) {
        put(dir, "vault/c2", value)
        val vault = vault(dir)
        vault.holds(CredentialRef("vault/c2")) shouldBe true
        vault.holds(CredentialRef("vault/c3")) shouldBe false
        vault.holds(CredentialRef("vault/c2 ")) shouldBe false
        vault.holds(CredentialRef("Vault/c2")) shouldBe false
    }

    @Test
    fun `read gives the value by reference at call time, nothing is kept between calls`(
        @TempDir dir: Path,
    ) {
        put(dir, "vault/c2", value)
        val vault = vault(dir)
        String(checkNotNull(vault.read(CredentialRef("vault/c2"))).reveal()) shouldBe value
        // the vault changes behind the adapter: the next call sees it, so nothing was cached
        put(dir, "vault/c2", "rotated-value")
        String(checkNotNull(vault.read(CredentialRef("vault/c2"))).reveal()) shouldBe "rotated-value"
        Files.delete(dir.resolve("vault/c2"))
        vault.read(CredentialRef("vault/c2")) shouldBe null
        vault.holds(CredentialRef("vault/c2")) shouldBe false
    }

    @Test
    fun `no secret value is returned by holds, printed by a secret or kept in a field of the adapter`(
        @TempDir dir: Path,
    ) {
        put(dir, "vault/c2", value)
        val vault = vault(dir)
        val secret = checkNotNull(vault.read(CredentialRef("vault/c2")))
        secret.toString() shouldNotContain value
        "$secret" shouldNotContain value
        // the adapter holds only the store: no String, CharArray or Secret field that could keep a value
        SecretStoreCredentialVault::class.java.declaredFields.map { it.type } shouldBe listOf(SecretStore::class.java)
    }

    @Test
    fun `a reference that escapes the mount or is missing is held by nothing and its message holds no value`(
        @TempDir dir: Path,
    ) {
        put(dir, "vault/c2", value)
        val vault = vault(dir)
        vault.holds(CredentialRef("../vault/c2")) shouldBe false
        vault.read(CredentialRef("vault/../../etc/passwd")) shouldBe null
        vault.read(CredentialRef("vault")) shouldBe null
    }
}
