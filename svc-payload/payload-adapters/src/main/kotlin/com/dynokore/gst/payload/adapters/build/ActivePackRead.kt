package com.dynokore.gst.payload.adapters.build

import com.dynokore.gst.payload.domain.OffNetworkPrefixSource

/**
 * One read of the active MDE pack: its [version], the [prefix] source of its off-network endpoint and the stored
 * `artefact.contents` JSON text ([contents]) of the same read. A build calls the read once, so the version a
 * rejection names is the version the build used.
 */
data class ActivePackRead(
    val version: String,
    val prefix: OffNetworkPrefixSource,
    val contents: String,
)
