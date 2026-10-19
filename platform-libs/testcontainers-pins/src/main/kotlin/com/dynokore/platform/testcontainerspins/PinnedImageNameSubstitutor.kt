package com.dynokore.platform.testcontainerspins

import org.testcontainers.utility.DockerImageName
import org.testcontainers.utility.ImageNameSubstitutor

/**
 * Resolves every image Testcontainers starts to a digest reference (INV-T-5604). A reference that already carries
 * `@sha256:` (MinIO, Ryuk) is returned unchanged; a table key resolves to its pinned value; anything else throws, so
 * a test that names an unpinned image fails closed instead of pulling a floating tag.
 */
class PinnedImageNameSubstitutor : ImageNameSubstitutor() {
    override fun apply(original: DockerImageName): DockerImageName {
        val reference = original.asCanonicalNameString()
        if (DIGEST_MARKER in reference) return original
        val pinned =
            ImagePins.postgres[reference]
                ?: error(
                    "image '$reference' is not pinned by digest: add it to testcontainers-pins' ImagePins " +
                        "(see testcontainers-pins/README.md) or name it with @sha256:",
                )
        return DockerImageName.parse(pinned).asCompatibleSubstituteFor(original)
    }

    override fun getDescription(): String = "digest pins of testcontainers-pins (INV-T-5604)"

    private companion object {
        const val DIGEST_MARKER = "@sha256:"
    }
}
