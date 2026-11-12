package com.dynokore.gst.ingestion.bootstrap

import com.dynokore.gst.ingestion.adapters.permission.ReplicaEntityPermission
import com.dynokore.gst.ingestion.adapters.permission.RequestCallerSource
import com.dynokore.gst.ingestion.application.permission.CallerSource
import com.dynokore.gst.ingestion.application.permission.EntityPermission
import com.dynokore.platform.serviceruntime.identity.EndUserPrincipalResolver
import com.dynokore.platform.serviceruntime.replica.AuthorityReplica
import com.dynokore.platform.serviceruntime.replica.OperationRoleMap
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import java.nio.file.Path

/** INV-T-21565: the composition root holds one replica, one role map, one permission and one caller source. */
class PermissionWiringTest {
    private fun runner(dir: Path): ApplicationContextRunner =
        IngestionContext
            .offline(dir)
            .withBean(EndUserPrincipalResolver::class.java, { EndUserPrincipalResolver { null } })

    @Test
    fun `INV-AC-21564 the context holds exactly one bean of each of the four types`(
        @TempDir dir: Path,
    ) {
        runner(dir).run { ctx ->
            (ctx.startupFailure == null) shouldBe true
            listOf(
                AuthorityReplica::class.java,
                OperationRoleMap::class.java,
                EntityPermission::class.java,
                CallerSource::class.java,
            ).forEach { ctx.getBeanNamesForType(it).size shouldBe 1 }
            (ctx.getBean(EntityPermission::class.java) is ReplicaEntityPermission) shouldBe true
            (ctx.getBean(CallerSource::class.java) is RequestCallerSource) shouldBe true
        }
    }

    @Test
    fun `INV-AC-21564 a user-defined EntityPermission and CallerSource replace those of PermissionConfiguration`(
        @TempDir dir: Path,
    ) {
        val own = EntityPermission { _, _, _ -> true }
        val ownCaller = CallerSource { "x" }
        runner(dir)
            .withBean(EntityPermission::class.java, { own })
            .withBean(CallerSource::class.java, { ownCaller })
            .run { ctx ->
                (ctx.startupFailure == null) shouldBe true
                ctx.getBeanNamesForType(EntityPermission::class.java).size shouldBe 1
                ctx.getBeanNamesForType(CallerSource::class.java).size shouldBe 1
                (ctx.getBean(EntityPermission::class.java) === own) shouldBe true
                (ctx.getBean(CallerSource::class.java) === ownCaller) shouldBe true
            }
    }

    @Test
    fun `INV-AC-21564 rolesFor is non-empty for entity-config manage and vp-61 set and empty for an unknown operation`(
        @TempDir dir: Path,
    ) {
        runner(dir).run { ctx ->
            val map = ctx.getBean(OperationRoleMap::class.java)
            map.rolesFor("entity-config.manage").entries.shouldNotBeEmpty()
            map.rolesFor("vp-61.set").entries.shouldNotBeEmpty()
            map.rolesFor("entity-config.managed").entries.shouldBeEmpty()
        }
    }

    @Test
    fun `INV-AC-21564 the decide budget defaults to one second and is read from the property`() {
        PermissionConfiguration.DEFAULT_BUDGET shouldBe "PT1S"
        java.time.Duration.parse(PermissionConfiguration.DEFAULT_BUDGET) shouldBe java.time.Duration.ofSeconds(1)
    }
}
