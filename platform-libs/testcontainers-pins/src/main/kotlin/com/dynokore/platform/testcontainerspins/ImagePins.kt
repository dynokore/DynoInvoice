package com.dynokore.platform.testcontainerspins

/**
 * The one table of Testcontainers images the platform-libs tests may start (INV-T-5604). Each value is the tag plus the
 * digest of the multi-arch index, read from the registry on [README.md]'s date. To bump: testcontainers-pins/README.md.
 */
object ImagePins {
    /** Keys are the exact reference text of the test sources; any other reference is refused. */
    val postgres: Map<String, String> =
        mapOf(
            "postgres:16" to "postgres:16@sha256:ca0bd484cb98bf4b24eb1010e73fb3fcbd6714d240fbc1a10eea5b7dbecb641d",
            "postgres:17" to "postgres:17@sha256:2d2b8998d31037bf721cfdf764d76ba74171b4fab3431b7f72c27c56ddbdf9e3",
            "postgres:18" to "postgres:18@sha256:74935e72241653ca55e0414067e6d8763aceb8a810eb51b452253ec3dcfc4336",
        )

    /** Testcontainers 1.21.4's default Ryuk image (RyukContainer), pinned; testcontainers.properties repeats it. */
    const val RYUK: String =
        "testcontainers/ryuk:0.12.0@sha256:dd3f023a6ed7015b3f95a49ccd65a2daf0c56e681422c12952b19a810dfa6298"
}
