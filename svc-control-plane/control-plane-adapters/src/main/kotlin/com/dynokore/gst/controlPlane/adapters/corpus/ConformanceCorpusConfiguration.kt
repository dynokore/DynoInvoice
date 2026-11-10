package com.dynokore.gst.controlPlane.adapters.corpus

import com.dynokore.gst.controlPlane.application.release.ConformanceCorpus
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Primary

/**
 * Binds the in-process corpus run as the production [ConformanceCorpus] (INV-Q-8719 A, INV-REQ-2660). The resource tree
 * is part of this module, so a context that has this configuration has the tree; the unbound default of the bootstrap
 * module stays only for a context without it. [Primary] makes this bean win whichever registers first.
 */
@Configuration(proxyBeanMethods = false)
class ConformanceCorpusConfiguration {
    @Bean
    @Primary
    fun inProcessConformanceCorpus(): ConformanceCorpus = InProcessConformanceCorpus(ClasspathCorpusSource())
}
