package com.dynokore.gst.canonical.bootstrap

import com.dynokore.gst.canonical.adapters.persistence.DocumentRowFactsFromRecord
import com.dynokore.gst.canonical.adapters.persistence.JooqEntityIdentities
import com.dynokore.gst.canonical.application.document.PeriodSpans
import com.dynokore.gst.canonical.application.document.ScopeAnswer
import com.dynokore.gst.canonical.application.document.ScopeAnswerCapture
import com.dynokore.gst.canonical.application.document.ScopeEvaluation
import com.dynokore.platform.tenancykernel.TenantContext
import io.kotest.matchers.shouldBe
import org.jooq.DSLContext
import org.jooq.SQLDialect
import org.jooq.impl.DSL
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.annotation.Bean

class DocumentRowFactsConfigurationTest {
    // not annotated: a nested @Configuration would be found by the component scan of the full-context tests
    class Collaborators {
        @Bean
        fun scopeEvaluation(): ScopeEvaluation = ScopeEvaluation { _, _, _ -> ScopeAnswer.Unavailable }

        @Bean
        fun dsl(): DSLContext = DSL.using(SQLDialect.POSTGRES)

        @Bean
        fun tenants(): TenantContext =
            java.lang.reflect.Proxy.newProxyInstance(
                TenantContext::class.java.classLoader,
                arrayOf(TenantContext::class.java),
            ) { _, _, _ -> error("not used") } as TenantContext

        @Bean
        fun entityIdentities(dsl: DSLContext): JooqEntityIdentities = JooqEntityIdentities(dsl)

        @Bean
        fun spans(): PeriodSpans = PeriodSpans { _, _ -> emptyList() }
    }

    @Test
    fun `INV-AC-21604 S10 the capture is the primary ScopeEvaluation and the one object of the facts source`() {
        ApplicationContextRunner()
            .withUserConfiguration(Collaborators::class.java, DocumentRowFactsConfiguration::class.java)
            .run { ctx ->
                (ctx.getBean(ScopeEvaluation::class.java) === ctx.getBean(ScopeAnswerCapture::class.java)) shouldBe true
                (ctx.getBean("scopeEvaluation") === ctx.getBean(ScopeAnswerCapture::class.java)) shouldBe false
                ctx.getBeansOfType(ScopeAnswerCapture::class.java).size shouldBe 1
                ctx.getBeansOfType(DocumentRowFactsFromRecord::class.java).size shouldBe 1
            }
    }
}
