package com.dynokore.gst.access.bootstrap

import com.lemonappdev.konsist.api.Konsist
import com.lemonappdev.konsist.api.container.KoScope
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

/**
 * One publisher of `MessageRequested` in access-adapters (ruling INV-Q-15790 A, INV-T-15799): a class that implements
 * one of the two ports of the channel `accessMessageRequested` is `OutboxLinkMessages` and nothing else. A port is
 * recognised by its imported full name, so a class of the same simple name from another package is not mistaken for it.
 */
class MessageRequestedPublisherTest {
    private val ports =
        mapOf(
            "LinkMessages" to "com.dynokore.gst.access.application.link.LinkMessages",
            "ExpiryNoticeMessages" to "com.dynokore.gst.access.application.grant.ExpiryNoticeMessages",
        )

    private fun publishers(scope: KoScope): List<String> =
        scope
            .classes()
            .filter { cls ->
                cls.parents().any { parent ->
                    ports[parent.name]?.let { full -> cls.containingFile.imports.any { it.name == full } } == true
                }
            }.map { it.name }
            .sorted()

    @Test
    fun `OutboxLinkMessages is the only class of access-adapters that implements a MessageRequested port`() {
        val adapters = Konsist.scopeFromDirectory("access-adapters/src/main/kotlin")
        assertThat(publishers(adapters)).containsExactly("OutboxLinkMessages")
    }

    @Test
    fun `probe - a second implementation is found, a look-alike of another package is not`(
        @TempDir dir: Path,
    ) {
        dir.resolve("Second.kt").toFile().writeText(
            "package x\nimport com.dynokore.gst.access.application.grant.ExpiryNoticeMessages\n" +
                "class SecondPublisher : ExpiryNoticeMessages { override fun request(r: Any) {} }\n",
        )
        dir.resolve("Alike.kt").toFile().writeText(
            "package y\nimport other.pkg.LinkMessages\nclass AlikePublisher : LinkMessages\n",
        )
        assertThat(publishers(Konsist.scopeFromExternalDirectory(dir.toString()))).containsExactly("SecondPublisher")
    }
}
