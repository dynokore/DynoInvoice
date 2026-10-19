package com.dynokore.gst.controlPlane.bootstrap

import com.dynokore.gst.controlPlane.adapters.bundle.PackBundles
import com.dynokore.gst.controlPlane.adapters.feed.PackFeedIndex
import com.dynokore.gst.controlPlane.adapters.feed.PackFeedReader
import com.dynokore.gst.controlPlane.adapters.feed.PackReleaserJooq
import com.dynokore.gst.controlPlane.adapters.persistence.StagingEvidenceReaderJooq
import com.dynokore.gst.controlPlane.application.pack.OperatorRoles
import com.dynokore.gst.controlPlane.application.pack.PackContentHasher
import com.dynokore.gst.controlPlane.application.pack.PackReleaser
import com.dynokore.gst.controlPlane.application.pack.PackRepository
import com.dynokore.gst.controlPlane.application.pack.PackSigningKeys
import com.dynokore.gst.controlPlane.application.pack.PublishPack
import com.dynokore.gst.controlPlane.application.pack.StagingEvidenceReader
import com.dynokore.platform.crypto.DetachedSigner
import org.jooq.DSLContext
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Clock

/** What publishPack reads and writes besides the pack repository: the gate's evidence, hash and the release. */
class PublishPorts(
    val staging: StagingEvidenceReader,
    val hasher: PackContentHasher,
    val releaser: PackReleaser,
)

/** The bundle, the feed and the publication use case (INV-REQ-1077, INV-REQ-1079). */
@Configuration(proxyBeanMethods = false)
class ReleaseConfiguration {
    /** The bundle builder over the CMS signer of service-runtime's CryptoWiring. */
    @Bean
    fun packBundles(signer: DetachedSigner) = PackBundles(signer)

    @Bean
    fun packFeedIndex(
        dsl: DSLContext,
        clock: Clock,
    ) = PackFeedIndex(dsl, clock)

    @Bean
    fun packFeedReader(
        dsl: DSLContext,
        index: PackFeedIndex,
    ) = PackFeedReader(dsl, index)

    @Bean
    fun packReleaser(
        dsl: DSLContext,
        clock: Clock,
        bundles: PackBundles,
        keys: PackSigningKeys,
        index: PackFeedIndex,
    ): PackReleaser = PackReleaserJooq(dsl, clock, bundles, keys, index)

    @Bean
    fun publishPorts(
        staging: StagingEvidenceReaderJooq,
        bundles: PackBundles,
        releaser: PackReleaser,
    ) = PublishPorts(staging, bundles.hasher(), releaser)

    @Bean
    fun publishPack(
        packs: PackRepository,
        roles: OperatorRoles,
        ports: PublishPorts,
        clock: Clock,
    ) = PublishPack(packs, roles, ports.staging, ports.hasher, ports.releaser, clock)
}
