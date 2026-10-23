package com.dynokore.gst.access.bootstrap

import com.dynokore.gst.access.adapters.idp.KeycloakDirectoryGroupMembers
import com.dynokore.gst.access.domain.grant.IdentityRef
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.boot.context.properties.bind.Binder
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource
import java.io.File

/**
 * The Keycloak adapter of `DirectoryGroupMembers` against the published identity-provider simulator (INV-Q-8833 A,
 * INV-T-8824), the digest of its image, and the required `access.identity-provider.directory.*` properties.
 */
class DirectoryGroupReaderTest {
    private fun reader(simulator: IdentityProviderSimulator = shared) =
        KeycloakDirectoryGroupMembers(
            simulator.baseUri,
            IdentityProviderSimulator.REALM,
            IdentityProviderSimulator.CLIENT_ID,
        ) { IdentityProviderSimulator.CLIENT_SECRET }

    @Test
    fun `the adapter returns exactly tenant-admin-1 for tenant admin, then an empty set once the user leaves`() {
        val simulator = IdentityProviderSimulator().start()
        simulator.use {
            val directory = reader(it)
            assertThat(directory.members("tenant.admin")).isEqualTo(setOf(IdentityRef("tenant-admin-1")))
            assertThat(directory.members("security.officer")).isEqualTo(setOf(IdentityRef("security-officer-1")))
            it.removeFromGroup("tenant-admin-1", "tenant.admin")
            assertThat(directory.members("tenant.admin")).isEmpty()
            // a group the directory does not hold is a failed read, never an empty group
            assertThatThrownBy { directory.members("no.such.group") }.isInstanceOf(IllegalStateException::class.java)
        }
    }

    @Test
    fun `a wrong client secret is a failed read`() {
        val directory =
            KeycloakDirectoryGroupMembers(
                shared.baseUri,
                IdentityProviderSimulator.REALM,
                IdentityProviderSimulator.CLIENT_ID,
            ) { "not-the-secret" }
        assertThatThrownBy { directory.members("tenant.admin") }.isInstanceOf(IllegalStateException::class.java)
    }

    private val expectedDigest = "sha256:b4bea10a288d18eff1e2a38dd3677a953cb9398765d35a370045085b1cb7e2a4"

    /** True when [reference] pulls the image by exactly [digest] (a tag reference or any other digest is false). */
    private fun pinnedTo(
        reference: String,
        digest: String,
    ): Boolean = reference.substringAfter('@', "") == digest && Regex("""sha256:[0-9a-f]{64}""").matches(digest)

    @Test
    fun `the simulator image of the test configuration is the digest the publish job printed`() {
        assertThat(pinnedTo(IdentityProviderSimulator.imageReference(), expectedDigest)).isTrue()
        val tag = IdentityProviderSimulator.imageReference().substringBefore('@') + ":dev"
        assertThat(pinnedTo(tag, expectedDigest)).isFalse()
        val oneOff = expectedDigest.dropLast(1) + (if (expectedDigest.last() == '4') "5" else "4")
        assertThat(
            pinnedTo(IdentityProviderSimulator.imageReference().substringBefore('@') + "@" + oneOff, expectedDigest),
        ).isFalse()
    }

    private fun bind(values: Map<String, String>) =
        Binder(MapConfigurationPropertySource(values))
            .bindOrCreate("access.identity-provider.directory", DirectoryProperties::class.java)

    private val all =
        mapOf(
            "access.identity-provider.directory.base-uri" to "http://keycloak:8080",
            "access.identity-provider.directory.realm" to "inv-cert",
            "access.identity-provider.directory.client-id" to "access-svc-directory-reader",
            "access.identity-provider.directory.client-secret-ref" to "idp/directory-reader-secret",
        )

    @Test
    fun `the four properties bind, and a missing key, a blank value or a look-alike key refuses the start`() {
        assertThat(bind(all).realm).isEqualTo("inv-cert")
        all.keys.forEach { key ->
            assertThatThrownBy { bind(all - key) }.`as`("without $key").isInstanceOf(Exception::class.java)
            assertThatThrownBy { bind(all + (key to "")) }.`as`("blank $key").isInstanceOf(Exception::class.java)
        }
        val lookAlike =
            (all - "access.identity-provider.directory.realm") +
                ("access.identity-provider.directories.realm" to "inv-cert")
        assertThatThrownBy { bind(lookAlike) }.isInstanceOf(Exception::class.java)
        // the key of the yaml names the same four properties, each from the environment and without a default
        val yaml = File("src/main/resources/application.yaml").readText()
        listOf("base-uri", "realm", "client-id", "client-secret-ref").forEach {
            assertThat(yaml).contains("      $it: \${ACCESS_IDP_DIRECTORY_${it.uppercase().replace('-', '_')}}")
        }
    }

    @Test
    fun `no source file outside src-test holds a literal default for the directory properties`() {
        val offenders =
            File("..")
                .absoluteFile
                .normalize()
                .walkTopDown()
                .onEnter { it.name != "build" && it.name != ".git" && it.name != ".gradle" }
                .filter { it.isFile && it.extension == "kt" && !it.invariantSeparatorsPath.contains("/src/test/") }
                .filter { it.readText().contains(Regex("""identity-provider\.directory|DirectoryProperties""")) }
                .filter {
                    it.readText().contains(
                        Regex("""(baseUri|realm|clientId|clientSecretRef)\s*:\s*String\??\s*="""),
                    )
                }.toList()
        assertThat(offenders).isEmpty()
        // the probe: a Kotlin default would be flagged
        val probe = "data class DirectoryProperties(val realm: String = \"inv-cert\")"
        assertThat(probe.contains(Regex("""(baseUri|realm|clientId|clientSecretRef)\s*:\s*String\??\s*="""))).isTrue()
    }

    private companion object {
        val shared: IdentityProviderSimulator by lazy {
            IdentityProviderSimulator().start().also { Runtime.getRuntime().addShutdownHook(Thread { it.close() }) }
        }
    }
}
