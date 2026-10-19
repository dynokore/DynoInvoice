package com.dynokore.platform.contracttests.provider

import au.com.dius.pact.provider.MessageAndMetadata
import au.com.dius.pact.provider.PactVerifyProvider
import au.com.dius.pact.provider.junit5.MessageTestTarget
import au.com.dius.pact.provider.junit5.PactVerificationContext
import au.com.dius.pact.provider.junit5.PactVerificationInvocationContextProvider
import au.com.dius.pact.provider.junitsupport.Provider
import au.com.dius.pact.provider.junitsupport.State
import au.com.dius.pact.provider.junitsupport.loader.PactBroker
import au.com.dius.pact.provider.junitsupport.loader.PactBrokerAuth
import au.com.dius.pact.provider.junitsupport.loader.PactFolder
import com.dynokore.platform.contracttests.Contracts
import com.dynokore.platform.contracttests.consumer.PolicyChangedPactTest
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.TestTemplate
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.junit.jupiter.api.extension.ExtendWith

/**
 * config-resolver verifies the PolicyChanged pacts registered against it (INV-REQ-1225 b2; coordinator ruling
 * INV-Q-3925): each message is the one its real policy writer emits ([PolicyWriterProbe]). Two loaders, one check:
 * [PolicyChangedProviderTest] reads the pact this pipeline's consumer test just wrote (every run, the gate), and
 * [PolicyChangedProviderBrokerTest] reads every pact the broker holds for config-resolver and publishes the result.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
abstract class PolicyChangedProvider {
    private val probe by lazy { PolicyWriterProbe() }

    @AfterAll
    fun stop() = probe.close()

    @BeforeEach
    fun target(context: PactVerificationContext?) {
        context?.target = MessageTestTarget()
    }

    @TestTemplate
    @ExtendWith(PactVerificationInvocationContextProvider::class)
    fun verify(context: PactVerificationContext?) {
        context?.verifyInteraction()
    }

    /** The provider state the pact names; a subclass annotates it with `@State` and calls [entityOverrideState]. */
    abstract fun state()

    /** The message the pact verifies; a subclass annotates it with `@PactVerifyProvider`. */
    abstract fun entityOverride(): MessageAndMetadata

    /** The provider state: nothing to set up, the probe writes the override on a fresh tenant. */
    protected fun entityOverrideState() = Unit

    protected fun entityOverrideMessage(): MessageAndMetadata {
        val message = probe.entityOverride()
        return MessageAndMetadata(message.toByteArray(), mapOf("contentType" to "application/json"))
    }
}

@Provider(Contracts.CONFIG_RESOLVER)
@PactFolder("build/pacts")
class PolicyChangedProviderTest : PolicyChangedProvider() {
    @State(PolicyChangedPactTest.STATE)
    override fun state() = entityOverrideState()

    @PactVerifyProvider(PolicyChangedPactTest.DESCRIPTION)
    override fun entityOverride() = entityOverrideMessage()
}

@Provider(Contracts.CONFIG_RESOLVER)
@PactBroker(
    url = "\${PACT_BROKER_BASE_URL}",
    authentication = PactBrokerAuth(username = "\${PACT_BROKER_USERNAME}", password = "\${PACT_BROKER_PASSWORD}"),
)
@EnabledIfEnvironmentVariable(named = "PACT_BROKER_BASE_URL", matches = ".+")
class PolicyChangedProviderBrokerTest : PolicyChangedProvider() {
    @State(PolicyChangedPactTest.STATE)
    override fun state() = entityOverrideState()

    @PactVerifyProvider(PolicyChangedPactTest.DESCRIPTION)
    override fun entityOverride() = entityOverrideMessage()
}
