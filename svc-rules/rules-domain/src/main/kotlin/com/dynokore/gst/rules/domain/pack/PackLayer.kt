package com.dynokore.gst.rules.domain.pack

/**
 * The two rule layers, versioned and signed separately (INV-REQ-856, INV-AC-1902): the regulatory layer is shared by
 * every tenant and fed from the control plane; the tenant mapping layer is each tenant's own approved mapping pack.
 */
enum class PackLayer {
    REGULATORY,
    TENANT_MAPPING,
}

/**
 * Who stands behind a pack's content (packs.yaml `authority`, INV-REQ-774 behaviour 1). IRAS-derived content must cite
 * its source paragraph; a tenant mapping is business-derived and needs no citation.
 */
enum class PackAuthority(
    val layer: PackLayer,
    val irasDerived: Boolean,
) {
    IRAS(PackLayer.REGULATORY, irasDerived = true),
    IRAS_INTERPRETATION(PackLayer.REGULATORY, irasDerived = true),
    OPEN_PEPPOL(PackLayer.REGULATORY, irasDerived = false),
    TENANT(PackLayer.TENANT_MAPPING, irasDerived = false),
}

/**
 * Binds a pack type to its layer (INV-REQ-856, INV-AC-14160): only the Mapping pack is a tenant mapping pack; every
 * other text, including a look-alike spelling, gets the regulatory layer that no tenant edits (fail closed).
 */
object PackLayers {
    const val MAPPING_PACK_TYPE = "MAPPING"

    fun of(packType: String): PackLayer =
        if (packType == MAPPING_PACK_TYPE) PackLayer.TENANT_MAPPING else PackLayer.REGULATORY
}
