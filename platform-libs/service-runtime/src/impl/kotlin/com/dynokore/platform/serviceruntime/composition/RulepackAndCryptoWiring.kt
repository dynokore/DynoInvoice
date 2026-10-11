package com.dynokore.platform.serviceruntime.composition

import com.dynokore.platform.crypto.BouncyCastleCms
import com.dynokore.platform.crypto.DetachedSignatureVerifier
import com.dynokore.platform.crypto.DetachedSigner
import com.dynokore.platform.rulepackkernel.BouncyCastlePackVerifier
import com.dynokore.platform.rulepackkernel.InMemoryPackRegistry
import com.dynokore.platform.rulepackkernel.JacksonPackContentValidator
import com.dynokore.platform.rulepackkernel.PackContentValidator
import com.dynokore.platform.rulepackkernel.PackRegistry
import com.dynokore.platform.rulepackkernel.PackVerifier
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * crypto's ports (INV-T-2875, INV-Q-3710), when the service embeds its impl half: the CMS [DetachedSigner] and
 * [DetachedSignatureVerifier]. A `SigningKey` needs the service's key material and is the service's.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnClass(name = ["com.dynokore.platform.crypto.BouncyCastleCms"])
class CryptoWiring {
    @Bean
    @ConditionalOnMissingBean(DetachedSigner::class, DetachedSignatureVerifier::class)
    fun cms(): BouncyCastleCms = BouncyCastleCms()
}

/**
 * rulepack-kernel's ports (INV-T-2875, INV-Q-3710), when the service embeds its impl half: the pack verifier,
 * the content validator and the pack registry. The §12.1 `PackSource` has no platform-libs implementation; it is
 * rules-svc's (INV-ADR-2773, INV-REQ-1079, INV-Q-3732).
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnClass(name = ["com.dynokore.platform.rulepackkernel.BouncyCastlePackVerifier"])
class RulepackKernelWiring {
    @Bean
    @ConditionalOnMissingBean(PackVerifier::class)
    fun packVerifier(): BouncyCastlePackVerifier = BouncyCastlePackVerifier()

    @Bean
    @ConditionalOnMissingBean(PackContentValidator::class)
    fun packContentValidator(): JacksonPackContentValidator = JacksonPackContentValidator()

    @Bean
    @ConditionalOnMissingBean(PackRegistry::class)
    fun packRegistry(): InMemoryPackRegistry = InMemoryPackRegistry()
}
