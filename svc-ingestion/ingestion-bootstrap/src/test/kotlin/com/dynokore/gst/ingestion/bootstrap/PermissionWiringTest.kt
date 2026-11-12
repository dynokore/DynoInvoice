package com.dynokore.gst.ingestion.bootstrap

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
import java.nio.file.Path

/** INV-AC-20953: the permission port and the platform beans it rests on resolve once each in the service context. */
class PermissionWiringTest {
    @TempDir
    lateinit var mount: Path

    private fun runner() =
        IngestionContext
            .offline(mount)
            .withBean(EndUserPrincipalResolver::class.java, { EndUserPrincipalResolver { null } })
            .withPropertyValues("gst.form=saas")

    @Test
    fun `INV-AC-20953 the context holds exactly one bean of each of the four types`() {
        runner().run { ctx ->
            (ctx.startupFailure?.let { throw it })
            for (type in listOf(
                AuthorityReplica::class.java,
                OperationRoleMap::class.java,
                EntityPermission::class.java,
                CallerSource::class.java,
            )) {
                ctx.getBeanNamesForType(type).size shouldBe 1
            }
        }
    }

    @Test
    fun `INV-AC-20953 rolesFor is non-empty for entity-config manage and vp-61 set and empty for the look-alike`() {
        runner().run { ctx ->
            (ctx.startupFailure?.let { throw it })
            val map = ctx.getBean(OperationRoleMap::class.java)
            map.rolesFor("entity-config.manage").entries.shouldNotBeEmpty()
            map.rolesFor("vp-61.set").entries.shouldNotBeEmpty()
            map.rolesFor("entity-config.managed").entries.shouldBeEmpty()
        }
    }
}
