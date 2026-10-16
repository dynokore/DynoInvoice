package com.dynokore.platform.testcontainerspins

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.testcontainers.utility.DockerImageName
import org.testcontainers.utility.ImageNameSubstitutor
import org.testcontainers.utility.TestcontainersConfiguration
import java.util.Properties

class PinnedImageNameSubstitutorTest {
    private val substitutor = PinnedImageNameSubstitutor()

    private fun resolve(reference: String) = substitutor.apply(DockerImageName.parse(reference)).asCanonicalNameString()

    @Test
    fun `the table keys are exactly the three postgres tags`() {
        assertEquals(setOf("postgres:16", "postgres:17", "postgres:18"), ImagePins.postgres.keys)
    }

    @Test
    fun `each table value is its tag pinned to a 64 hex digest`() {
        ImagePins.postgres.forEach { (key, value) ->
            assertEquals(true, Regex("""${Regex.escape(key)}@sha256:[0-9a-f]{64}""").matches(value), value)
        }
        assertEquals(true, Regex("""testcontainers/ryuk:0\.12\.0@sha256:[0-9a-f]{64}""").matches(ImagePins.RYUK))
    }

    @ParameterizedTest
    @ValueSource(strings = ["postgres:16", "postgres:17", "postgres:18"])
    fun `a postgres tag resolves to the table value`(reference: String) {
        assertEquals(ImagePins.postgres.getValue(reference), resolve(reference))
    }

    @Test
    fun `a reference that already carries a digest is returned unchanged`() {
        val minio = DockerImageName.parse("cgr.dev/chainguard/minio@sha256:" + "a".repeat(64))
        assertSame(minio, substitutor.apply(minio))
        val ryuk = DockerImageName.parse(ImagePins.RYUK)
        assertSame(ryuk, substitutor.apply(ryuk))
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "postgres:17.1",
            "postgres:latest",
            "postgres",
            "library/postgres:17",
            "mypostgres:17",
            "postgres:17-alpine",
            "Postgres:17",
            "docker.io/postgres:17",
            "testcontainers/ryuk:0.0.0",
        ],
    )
    fun `any other reference throws`(reference: String) {
        val failure = assertThrows<IllegalStateException> { resolve(reference) }
        assertEquals(true, failure.message!!.contains("not pinned by digest"))
    }

    @Test
    fun `the classpath testcontainers properties register the substitutor and the Ryuk pin`() {
        val properties =
            Properties().apply {
                PinnedImageNameSubstitutor::class.java.getResourceAsStream("/testcontainers.properties")!!.use(::load)
            }
        assertEquals(PinnedImageNameSubstitutor::class.java.name, properties.getProperty("image.substitutor"))
        assertEquals(ImagePins.RYUK, properties.getProperty("ryuk.container.image"))
        assertEquals(ImagePins.RYUK, configuredRyukImage())
    }

    @Test
    fun `Testcontainers resolves the names through the registered substitutor`() {
        val resolved = ImageNameSubstitutor.instance().apply(DockerImageName.parse("postgres:17"))
        assertEquals(ImagePins.postgres.getValue("postgres:17"), resolved.asCanonicalNameString())
    }

    // The method RyukContainer itself reads (classpath testcontainers.properties included); deprecated in Java only.
    @Suppress("DEPRECATION")
    private fun configuredRyukImage(): String = TestcontainersConfiguration.getInstance().ryukImage
}
