package com.dynokore.gst.consoleBff.bootstrap

import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.springframework.boot.health.actuate.endpoint.CompositeHealthDescriptor
import org.springframework.boot.health.actuate.endpoint.HealthEndpoint
import java.nio.file.Path

/** INV-Q-7533 C: the ready group of the unmodified application.yaml reports exactly the components named in its key. */
class ReadyGroupTest {
    @Test
    fun `INV-AC-14457 the ready group is the loaded key and reports exactly the components named in it db included`(
        @TempDir mount: Path,
    ) {
        ConsoleContext.online(mount).run { context ->
            context.startupFailure shouldBe null
            val key = context.environment.getProperty(READY_KEY).shouldNotBeNull()
            key shouldBe "readinessState,vault,db,eventLoops"
            val ready = context.getBean(HealthEndpoint::class.java).healthForPath("ready")
            ready.shouldNotBeNull()
            checkNotNull((ready as CompositeHealthDescriptor).components).keys shouldBe key.split(",").toSet()
        }
    }

    private companion object {
        const val READY_KEY = "management.endpoint.health.group.ready.include"
    }
}
