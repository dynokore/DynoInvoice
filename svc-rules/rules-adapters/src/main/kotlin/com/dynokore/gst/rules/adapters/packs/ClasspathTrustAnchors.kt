package com.dynokore.gst.rules.adapters.packs

import com.dynokore.gst.rules.application.TrustAnchorSource
import com.dynokore.platform.rulepackkernel.TrustAnchor
import com.dynokore.platform.rulepackkernel.TrustAnchors
import java.util.Base64
import java.util.Properties

/**
 * The trust anchors a release train ships (STK-13), read from a classpath resource of `label=<base64 DER certificate>`
 * lines. An absent resource, an empty one and one with an unreadable line all give no anchors (fail closed): the import
 * then answers 503 SRT-013 and writes nothing.
 */
class ClasspathTrustAnchors(
    private val resource: String = DEFAULT_RESOURCE,
    private val loader: ClassLoader = ClasspathTrustAnchors::class.java.classLoader,
) : TrustAnchorSource {
    override fun anchors(): TrustAnchors? {
        val stream = loader.getResourceAsStream(resource) ?: return null
        val properties = Properties().also { p -> stream.use { p.load(it) } }
        val list =
            runCatching {
                properties.stringPropertyNames().sorted().map { label ->
                    TrustAnchor(label, Base64.getDecoder().decode(properties.getProperty(label).trim()))
                }
            }.getOrNull()
        return list?.takeIf { it.isNotEmpty() }?.let { TrustAnchors(it) }
    }

    companion object {
        const val DEFAULT_RESOURCE = "pack-trust-anchors.properties"
    }
}
