package com.dynokore.gst.controlPlane.adapters.corpus

import com.dynokore.gst.controlPlane.application.release.ConformanceCorpus
import com.dynokore.gst.controlPlane.domain.pack.RegulatoryPack
import com.dynokore.gst.controlPlane.domain.release.GoldenDocument
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.springframework.context.annotation.AnnotationConfigApplicationContext
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/** The production binding (INV-AC-9790): built the way the bootstrap scans it, with the unbound default beside it. */
class ConformanceCorpusConfigurationTest {
    @Configuration(proxyBeanMethods = false)
    class UnboundDefault {
        @Bean
        fun conformanceCorpus(): ConformanceCorpus =
            object : ConformanceCorpus {
                override fun documents(): List<GoldenDocument> = emptyList()

                override fun classify(
                    pack: RegulatoryPack,
                    document: GoldenDocument,
                ): String? = null
            }
    }

    @Test
    fun `INV-AC-9790 the context binds the in-process corpus as the ConformanceCorpus bean`() {
        AnnotationConfigApplicationContext(ConformanceCorpusConfiguration::class.java).use { context ->
            val corpus = context.getBean(ConformanceCorpus::class.java)
            (corpus is InProcessConformanceCorpus) shouldBe true
            corpus.documents().isNotEmpty() shouldBe true
        }
    }

    @Test
    fun `INV-AC-9790 the in-process corpus wins whichever of the two configurations registers first`() {
        listOf(
            arrayOf(ConformanceCorpusConfiguration::class.java, UnboundDefault::class.java),
            arrayOf(UnboundDefault::class.java, ConformanceCorpusConfiguration::class.java),
        ).forEach { order ->
            AnnotationConfigApplicationContext().use { context ->
                context.setAllowBeanDefinitionOverriding(false)
                context.register(*order)
                context.refresh()
                (context.getBean(ConformanceCorpus::class.java) is InProcessConformanceCorpus) shouldBe true
            }
        }
    }

    @Test
    fun `INV-AC-9790 the unbound default alone holds no document`() {
        AnnotationConfigApplicationContext(UnboundDefault::class.java).use {
            it.getBean(ConformanceCorpus::class.java).documents() shouldBe emptyList()
        }
    }
}
