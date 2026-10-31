package com.dynokore.gst.controlPlane.adapters.stage

import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import org.testcontainers.containers.ContainerFetchException
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.utility.DockerImageName

/**
 * The sandbox adapter of the stage against the published certification-kit Access Point simulator image (coordinator
 * ruling INV-Q-9639 A; `ci/pull-simulator.sh` tags the image the pin file names). The simulator contract is provisional
 * (BLK-2), so only an accepted and a rejected package are asserted. A missing image fails the test, never skips it.
 */
class StageIT {
    private companion object {
        const val IMAGE = "certkit/access-point-simulator:dev"
        const val PORT = 8080

        val simulator: GenericContainer<*> by lazy {
            GenericContainer(DockerImageName.parse(IMAGE))
                .withExposedPorts(PORT)
                .withLabel("inv.agent", System.getenv("INV_AGENT_NAME") ?: "svc-control-plane-tests")
                .waitingFor(Wait.forHttp("/__control/status").forStatusCode(200))
                .also {
                    try {
                        it.start()
                    } catch (e: ContainerFetchException) {
                        throw IllegalStateException(
                            "the certification-kit AP simulator image $IMAGE is not in the local Docker daemon: " +
                                "run `sh ci/pull-simulator.sh` (it pulls the digest of simulator-image.digest)",
                            e,
                        )
                    }
                }
        }

        fun submitter() = AccessPointSandboxSubmitter("http://${simulator.host}:${simulator.getMappedPort(PORT)}")
    }

    @Test
    fun `the simulator accepts a well-formed package`() {
        submitter().submit(StagedSubmission("00000000-0000-4000-8000-000000000001", "{}")) shouldBe
            SandboxVerdict.Accepted
    }

    @Test
    fun `the simulator rejects a package the access point refuses`() {
        // a blank document reference makes the package structurally invalid (AP-PKG-INVALID-DOCUMENT, 422)
        submitter().submit(StagedSubmission(" ", "{}")).shouldBeInstanceOf<SandboxVerdict.Rejected>()
    }

    @Test
    fun `an unreachable sandbox is a rejection`() {
        AccessPointSandboxSubmitter("http://127.0.0.1:1")
            .submit(StagedSubmission("00000000-0000-4000-8000-000000000001", "{}"))
            .shouldBeInstanceOf<SandboxVerdict.Rejected>()
    }

    @Test
    fun `the target of the adapter is SIMULATOR`() {
        submitter().target shouldBe "SIMULATOR"
    }
}
