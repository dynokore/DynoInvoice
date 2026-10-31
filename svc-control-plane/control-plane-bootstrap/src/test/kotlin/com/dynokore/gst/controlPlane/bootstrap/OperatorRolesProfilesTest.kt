package com.dynokore.gst.controlPlane.bootstrap

import com.dynokore.gst.controlPlane.adapters.access.AuthoriseOperatorRoles
import com.dynokore.gst.controlPlane.application.pack.OperatorRoles
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

/** The OperatorRoles binding in the profiles `test`, `saas` and `private`: live with the base URL, fail closed without. */
class OperatorRolesProfilesTest {
    @TempDir
    lateinit var mount: Path

    private fun everyProfile(
        vararg extra: String,
        probe: (OperatorRoles) -> Unit,
    ) = listOf("test", "saas", "private").map { profile ->
        DynamicTest.dynamicTest(profile) {
            controlPlaneRunner(mount, "spring.profiles.active=$profile", *extra).run { context ->
                context.startupFailure shouldBe null
                probe(context.getBean(OperatorRoles::class.java))
            }
        }
    }

    @TestFactory
    fun `with controlplane access base-url the live authorise binding is bound`() =
        everyProfile("controlplane.access.base-url=http://access-svc.invalid") { it.shouldBeInstanceOf() }

    @TestFactory
    fun `without the property the fail-closed default holds no role`() =
        everyProfile { roles ->
            (roles is AuthoriseOperatorRoles) shouldBe false
            AuthoriseOperatorRoles.ROLE_PERMISSIONS.keys.forEach { roles.holds("rita", it) shouldBe false }
        }

    private fun OperatorRoles.shouldBeInstanceOf() {
        (this is AuthoriseOperatorRoles) shouldBe true
    }
}
