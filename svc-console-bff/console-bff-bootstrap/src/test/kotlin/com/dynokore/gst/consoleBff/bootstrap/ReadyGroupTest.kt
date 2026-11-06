package com.dynokore.gst.consoleBff.bootstrap

import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.springframework.boot.health.actuate.endpoint.CompositeHealthDescriptor
import org.springframework.boot.health.actuate.endpoint.HealthEndpoint
import java.nio.file.Path

/** INV-Q-7533 C: the ready group of the unmodified application.yaml names the components it reports (`db` absent). */
class ReadyGroupTest {
    @Test
    fun `INV-AC-14435 the ready group is the loaded key and reports exactly the components named in it`(
        @TempDir mount: Path,
    ) {
        ConsoleContext.offline(mount).run { context ->
            context.startupFailure shouldBe null
            val key = context.environment.getProperty(READY_KEY).shouldNotBeNull()
            key shouldBe "readinessState,vault,db,eventLoops"
            val ready = context.getBean(HealthEndpoint::class.java).healthForPath("ready")
            ready.shouldNotBeNull()
            // this slice has no DataSource, so the one expected absence is `db` (INV-Q-17448 C); INV-T-14458 adds it
            val expected = key.split(",").toSet() - ABSENT_WITHOUT_DATASOURCE
            checkNotNull((ready as CompositeHealthDescriptor).components).keys shouldBe expected
        }
    }

    private companion object {
        const val ABSENT_WITHOUT_DATASOURCE = "db"
        const val READY_KEY = "management.endpoint.health.group.ready.include"
    }
}
