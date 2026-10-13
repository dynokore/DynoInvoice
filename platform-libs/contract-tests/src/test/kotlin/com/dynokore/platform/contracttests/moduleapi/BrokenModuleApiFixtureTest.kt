package com.dynokore.platform.contracttests.moduleapi

import com.dynokore.platform.adapterruntime.DeadLetterEntry
import com.dynokore.platform.adapterruntime.DeadLetterSink
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.platform.engine.discovery.DiscoverySelectors.selectClass
import org.junit.platform.testkit.engine.EngineTestKit

/**
 * A deliberately broken module-API fixture: a [DeadLetterSink] that accepts every entry and keeps none, so a call
 * that exhausted its retries is lost instead of dead-lettered (INV-REQ-1353). Tagged [BROKEN_FIXTURE], which the
 * suite's own test run excludes: it runs only through [BrokenModuleApiFixtureTest].
 */
@Tag(BROKEN_FIXTURE)
class BrokenDeadLetterSinkFixture : AdapterRuntimeModuleApiConsumerContract() {
    override fun sink(): DeadLetterSink = DeadLetterSink { _: DeadLetterEntry -> }

    override fun deadLetters(): List<DeadLetterEntry> = emptyList()
}

/**
 * Done-when (INV-T-2877 rev 5): "a broken module-API fixture fails its consumer test". Runs adapter-runtime's
 * module-API consumer contract against [BrokenDeadLetterSinkFixture] on the JUnit Platform and requires that it
 * fails, and that it fails on the dead-letter test only: the same contract passes against the real implementations
 * ([AdapterRuntimeModuleApiConsumerContractTest]).
 */
class BrokenModuleApiFixtureTest {
    @Test
    fun `the module-API consumer contract fails against a dead-letter sink that drops entries`() {
        val events =
            EngineTestKit
                .engine("junit-jupiter")
                .selectors(selectClass(BrokenDeadLetterSinkFixture::class.java))
                .execute()
                .testEvents()
        val failed = events.failed().list().map { it.testDescriptor.displayName }
        failed shouldBe listOf(DEAD_LETTER_TEST)
        events.succeeded().count() shouldBe 2L
        events
            .failed()
            .list()
            .single()
            .payload
            .toString() shouldContain "DeadLetterEntry"
    }

    private companion object {
        const val DEAD_LETTER_TEST =
            "a call that fails every attempt is dead-lettered once with its edge and attempts, then rethrown()"
    }
}

const val BROKEN_FIXTURE = "broken-fixture"
