package com.dynokore.gst.rules.adapters.packs

import com.dynokore.gst.rules.application.PackBundleNotFoundException
import com.dynokore.platform.serviceruntime.binding.PackSource
import com.dynokore.platform.serviceruntime.binding.PackSourceRef
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path

/**
 * The [PackSource] over the mounted read-only directory of the property `rules.pack-source.directory`: the bundle is
 * the file named by the digest hex after `sha256:` and no other path is ever read. The reference is validated to the
 * exact shape `sha256:<lower hex>` before it becomes a file name, so no separator, dot or case variant reaches the
 * file system; a link is not followed.
 */
class DirectoryPackSource(
    private val directory: Path,
) : PackSource {
    override fun fetch(reference: PackSourceRef): ByteArray {
        val name = NAME.matchEntire(reference.value)?.groupValues?.get(1)
        val file = name?.let { directory.resolve(it) }
        if (file == null || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
            throw PackBundleNotFoundException(reference.value)
        }
        return Files.readAllBytes(file)
    }

    private companion object {
        val NAME = Regex("sha256:([0-9a-f]{1,128})")
    }
}
