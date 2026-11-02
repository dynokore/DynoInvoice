package com.dynokore.gst.access.bootstrap

import com.dynokore.gst.access.domain.elevation.OperatorAccess
import com.dynokore.gst.access.domain.elevation.OperatorSessionAccess
import com.dynokore.gst.access.domain.role.Permission
import com.dynokore.gst.access.domain.role.PermissionMatrix
import com.dynokore.gst.access.domain.role.Role
import com.dynokore.gst.catalogue.DeploymentForm
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.io.File

/**
 * INV-T-4154 (INV-Q-16475 A, INV-Q-16479 A; INV-REQ-862 b1-b3, INV-AC-922): what an operator session may do in its
 * tenant, decided over the real catalogue matrix of each form. A key is allowed when it is one of the two metadata
 * keys and the catalogue cell of the session's role is PERMITTED, or when it is `doc.view.content` inside an
 * elevation; every other key is denied.
 */
class OperatorSessionAccessTest {
    private val saas: PermissionMatrix = PermissionMatrixLoader.load(DeploymentForm.SAAS)
    private val private: PermissionMatrix = PermissionMatrixLoader.load(DeploymentForm.PRIVATE)
    private val content = Permission("doc.view.content")

    private fun allows(
        matrix: PermissionMatrix,
        role: Role,
        key: String,
        elevated: Boolean = false,
    ) = OperatorSessionAccess.allows({ matrix.allowance(role, it) }, Permission(key), elevated)

    @Test
    fun `the metadata keys are exactly the two of INV-Q-16475 A, compared by equality`() {
        assertThat(OperatorAccess.SESSION_METADATA)
            .isEqualTo(setOf(Permission("submission.view-status"), Permission("period-readiness.view")))
        assertThat(OperatorAccess.OPENED_BY_ELEVATION).isEqualTo(setOf(content))
    }

    @Test
    fun `the constant with a third key fails the equality test`() {
        val widened = OperatorAccess.SESSION_METADATA + Permission("monitoring.view")
        assertThat(
            widened,
        ).isNotEqualTo(setOf(Permission("submission.view-status"), Permission("period-readiness.view")))
    }

    @Test
    fun `each operator role has the two metadata keys where the catalogue cell is permitted`() {
        listOf(Role.PLATFORM_SUPPORT, Role.PLATFORM_ENGINEER).forEach { role ->
            OperatorAccess.SESSION_METADATA.forEach { key ->
                assertThat(allows(saas, role, key.value)).describedAs("$role $key").isEqualTo(
                    saas.allowance(role, key) == com.dynokore.gst.access.domain.role.Allowance.PERMITTED,
                )
            }
        }
    }

    @Test
    fun `no session of any operator role holds a write or administrative key`() {
        val denied =
            listOf(
                "audit-entry.purge",
                "retention.delete.request",
                "submission.replay",
                "ap-credential.manage",
                "support-bundle.export",
                "installation.install",
                "vp-66.set",
                "operator-session.open",
                "elevation.request",
                "audit-log.view",
                "monitoring.view",
                "no.such.key",
            )
        listOf(saas to Role.PLATFORM_SUPPORT, saas to Role.PLATFORM_ENGINEER, private to Role.DEPLOYMENT_OPERATOR)
            .forEach { (matrix, role) ->
                denied.forEach {
                    assertThat(
                        allows(matrix, role, it, elevated = true),
                    ).describedAs("$role $it").isFalse()
                }
            }
    }

    @Test
    fun `content is denied without an elevation and allowed inside one, for no other key`() {
        assertThat(allows(saas, Role.PLATFORM_SUPPORT, content.value, elevated = false)).isFalse()
        assertThat(allows(saas, Role.PLATFORM_SUPPORT, content.value, elevated = true)).isTrue()
        assertThat(allows(saas, Role.PLATFORM_SUPPORT, "monitoring.view", elevated = true)).isFalse()
    }

    @Test
    fun `the use case compares no role name for the grant check or the session permissions`() {
        val root = File("..").canonicalFile
        val sources =
            listOf(
                "access-application/src/main/kotlin/com/dynokore/gst/access/application/" +
                    "operatorsession/OperatorSessions.kt",
                "access-domain/src/main/kotlin/com/dynokore/gst/access/domain/elevation/Elevation.kt",
            ).map { File(root, it).readText() }
        sources.forEach { text ->
            assertThat(
                text,
            ).doesNotContain(
                "Role.PLATFORM_",
                "Role.DEPLOYMENT_OPERATOR",
                "\"platform.support\"",
                "\"deployment.operator\"",
            )
        }
        assertThat(sources.first()).contains("ElevationKeys.OPEN_SESSION")
    }
}
