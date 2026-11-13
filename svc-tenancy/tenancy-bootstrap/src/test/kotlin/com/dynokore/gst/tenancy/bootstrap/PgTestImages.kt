package com.dynokore.gst.tenancy.bootstrap

import org.testcontainers.utility.DockerImageName

/** PostgreSQL test images by digest, the pins of platform-libs' testcontainers-pins (INV-T-5604, owner INV-1 A). */
internal object PgTestImages {
    const val PG17 = "postgres:17@sha256:2d2b8998d31037bf721cfdf764d76ba74171b4fab3431b7f72c27c56ddbdf9e3"
    const val PG18 = "postgres:18@sha256:74935e72241653ca55e0414067e6d8763aceb8a810eb51b452253ec3dcfc4336"

    /** A digest reference is not the default image name PostgreSQLContainer accepts, so it is declared compatible. */
    fun postgres(reference: String): DockerImageName =
        DockerImageName.parse(reference).asCompatibleSubstituteFor("postgres")
}
