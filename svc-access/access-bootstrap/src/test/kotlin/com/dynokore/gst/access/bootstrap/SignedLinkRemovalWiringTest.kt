package com.dynokore.gst.access.bootstrap

import com.dynokore.gst.access.application.link.LinkInvalidations
import com.dynokore.gst.access.application.link.RemovingClientSignatoryOnInvalidation
import com.dynokore.gst.access.application.link.VerifySignatoryLinkUseCase
import com.dynokore.platform.configresolver.ScopeLevel
import com.dynokore.platform.configresolver.SetterPermission
import com.dynokore.platform.serviceruntime.identity.EndUserPrincipal
import com.dynokore.platform.serviceruntime.identity.EndUserPrincipalResolver
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.assertj.AssertableWebApplicationContext
import org.springframework.boot.test.context.runner.WebApplicationContextRunner
import org.springframework.context.ConfigurableApplicationContext

/**
 * INV-AC-23434: the invalidation the verify use case receives is the decorator that publishes the REMOVED role record
 * of a client-signatory link. The context is built the way `SignedLinkStartupTest` builds it.
 */
class SignedLinkRemovalWiringTest {
    private fun runner(): WebApplicationContextRunner =
        BoundaryFixture
            .runner(
                catalogueMatrix = true,
                form = "PRIVATE",
                mounted = BoundaryFixture.mountWithDeliveryKey(TestSecrets.pem(TestSecrets.deliveryKeyPair.public)),
            ).withBean(
                EndUserPrincipalResolver::class.java,
                { TestTokenPrincipals(mapOf("token-operator" to EndUserPrincipal("user:operator", null, emptySet()))) },
            ).withBean(
                SetterPermission::class.java,
                {
                    SetterPermission { actor, _, level, _, _, _ ->
                        level == ScopeLevel.INSTALLATION && actor.principal == "user:operator"
                    }
                },
            )

    private fun withContext(check: (AssertableWebApplicationContext) -> Unit) {
        runner().run {
            assertThat(it).hasNotFailed()
            check(it)
        }
    }

    private fun invalidationsOfVerify(context: ConfigurableApplicationContext): Any {
        val field = VerifySignatoryLinkUseCase::class.java.getDeclaredField("invalidations")
        field.isAccessible = true
        return field.get(context.getBean(VerifySignatoryLinkUseCase::class.java))
    }

    @Test
    fun `INV-AC-23434 (a) two LinkInvalidations beans and only the decorator is primary`() {
        withContext { context ->
            val names = context.getBeanNamesForType(LinkInvalidations::class.java).toSet()
            assertThat(names).isEqualTo(setOf("linkInvalidations", "clientSignatoryAwareInvalidations"))
            val factory = context.beanFactory
            assertThat(factory.getBeanDefinition("linkInvalidations").isPrimary).isFalse()
            assertThat(factory.getBeanDefinition("clientSignatoryAwareInvalidations").isPrimary).isTrue()
        }
    }

    @Test
    fun `INV-AC-23434 (b) the primary LinkInvalidations is the removing decorator`() {
        withContext { context ->
            assertThat(context.getBean(LinkInvalidations::class.java))
                .isInstanceOf(RemovingClientSignatoryOnInvalidation::class.java)
        }
    }

    @Test
    fun `INV-AC-23434 (c) the verify use case holds the decorator bean and not the outbox bean`() {
        withContext { context ->
            val held = invalidationsOfVerify(context)
            assertThat(held).isSameAs(context.getBean("clientSignatoryAwareInvalidations"))
            assertThat(held).isNotSameAs(context.getBean("linkInvalidations"))
        }
    }

    @Test
    fun `INV-AC-23434 (d) the decorator delegates to the linkInvalidations bean`() {
        withContext { context ->
            val decorator = context.getBean("clientSignatoryAwareInvalidations")
            val field = RemovingClientSignatoryOnInvalidation::class.java.getDeclaredField("delegate")
            field.isAccessible = true
            assertThat(field.get(decorator)).isSameAs(context.getBean("linkInvalidations"))
        }
    }
}
