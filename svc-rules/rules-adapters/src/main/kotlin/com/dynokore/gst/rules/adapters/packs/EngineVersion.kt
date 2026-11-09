package com.dynokore.gst.rules.adapters.packs

import org.springframework.boot.info.BuildProperties

/**
 * The engine version this service build carries: the `version` of its `BuildProperties`, which `springBoot { buildInfo() }`
 * fills from the Gradle property `version` that the release build passes. A mapping pack is activated with this value as
 * its minimum engine version (INV-REQ-380). It answers null when the build information is missing, blank or the Gradle
 * placeholder `unspecified`, so a caller refuses the activation instead of writing a made-up version.
 */
class EngineVersion(
    private val build: BuildProperties?,
) {
    /** The version, or null when this build does not state a usable one. */
    fun current(): String? =
        build
            ?.version
            ?.trim()
            ?.takeIf { it.isNotEmpty() && it != UNSPECIFIED }

    private companion object {
        const val UNSPECIFIED = "unspecified"
    }
}
