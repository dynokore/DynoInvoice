package com.dynokore.gst.tenancy.bootstrap

import com.dynokore.gst.tenancy.adapters.events.InstallationStatusChangedOutbox
import com.dynokore.gst.tenancy.adapters.http.InstallationController
import com.dynokore.gst.tenancy.application.certification.ReleaseTrainChanged
import com.dynokore.gst.tenancy.contract.model.InstallLicenceRequest
import com.dynokore.gst.tenancy.contract.model.InstallationFactsRequest
import com.dynokore.platform.outboxandqueue.Envelope
import com.dynokore.platform.serviceruntime.http.ClosedErrorCode
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.config.BeanDefinition
import org.springframework.boot.test.context.assertj.AssertableWebApplicationContext
import tools.jackson.databind.json.JsonMapper
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/**
 * recordInstallationFacts (DEP-004), installLicence and getLicenceStatus (DEP-005) with the platform event
 * InstallationStatusChanged, through the real application context over PostgreSQL (INV-REQ-1095, INV-REQ-1076,
 * INV-Q-9821 A, INV-Q-14289 A).
 */
class InstallationHealthTest {
    private val vendor = LicenceKey()

    private inner class Env(
        val context: AssertableWebApplicationContext,
        val hook: AtomicInteger,
    ) {
        val api: InstallationController = context.getBean(InstallationController::class.java)
        val captured: CapturedEvents = context.getBean(CapturedEvents::class.java)
        private var seen = 0

        init {
            BoundaryFixture.asSuperuser { c ->
                c.createStatement().use {
                    it.execute("delete from tenancy.installation_fact")
                    it.execute("delete from tenancy.installed_licence")
                }
            }
            seen = status().size
        }

        fun status(): List<Envelope> = captured.envelopes.filter { it.type == InstallationStatusChangedOutbox.TYPE }

        /** The InstallationStatusChanged events published since the last call. */
        fun fresh(): List<Envelope> = status().drop(seen).also { seen = status().size }

        fun facts(
            train: String,
            vararg window: String,
        ) = api.recordInstallationFacts(UUID.randomUUID(), InstallationFactsRequest(train, window.toList()))

        fun install(
            id: String,
            file: ByteArray,
        ) = api.installLicence(UUID.randomUUID(), InstallLicenceRequest(id, file))

        fun refused(
            id: String,
            file: ByteArray,
        ): Throwable? = refusalOf { install(id, file) }
    }

    private fun run(
        anchors: Boolean = true,
        hook: AtomicInteger = AtomicInteger(),
        block: (Env) -> Unit,
    ) {
        BoundaryFixture
            .runner()
            .withPropertyValues("gst.installation.ref=INST-1")
            .let { if (anchors) it.withPropertyValues("gst.licence.trust-anchors=${vendor.anchor}") else it }
            .withBean(ReleaseTrainChanged::class.java, { ReleaseTrainChanged { hook.incrementAndGet() } }, ::primary)
            .run { context ->
                context.startupFailure?.let { throw it }
                block(Env(context, hook))
            }
    }

    // primary: the application also binds the outbox hook of the NEW_TRAIN mark, which this test replaces
    private fun primary(definition: BeanDefinition) {
        definition.isPrimary = true
    }

    private fun today(plusDays: Long = 0) = LocalDate.now(ZoneOffset.UTC).plusDays(plusDays)

    private fun term(end: LocalDate) = "2025-01-01/$end"

    private fun payloadNode(event: Envelope) = JsonMapper.builder().build().readTree(event.dataJson)

    @Test
    fun `INV-AC-9590 an expired licence raises DEP-005 once, a valid one clears it, a bad file changes nothing`() {
        run { env ->
            val l1 = vendor.file(LicenceKey.licence("LIC-1", "INST-1", term(today(-1))))
            val l2 = vendor.file(LicenceKey.licence("LIC-2", "INST-1", term(today(30))))
            // a signature that does not verify:
            val l3 = LicenceKey().file(LicenceKey.licence("LIC-3", "INST-1", term(today(30))))
            val first = env.install("LIC-1", l1).body!!
            assertThat(first.status.value).isEqualTo("EXPIRED")
            assertThat(first.expiresAt).isEqualTo(today(-1))
            val raised = env.fresh().single()
            assertThat(payloadNode(raised)["depCode"].asString()).isEqualTo("DEP-005")
            assertThat(payloadNode(raised)["status"].asString()).isEqualTo("RAISED")
            env.install("LIC-1", l1)
            assertThat(env.fresh()).isEmpty()
            assertThat(
                env
                    .install("LIC-2", l2)
                    .body!!
                    .status.value,
            ).isEqualTo("ACTIVE")
            val cleared = env.fresh().single()
            assertThat(payloadNode(cleared)["status"].asString()).isEqualTo("CLEARED")
            val before = env.api.getLicenceStatus().body!!
            assertThat(env.refused("LIC-3", l3)).isNotNull()
            assertThat(env.api.getLicenceStatus().body).isEqualTo(before)
            assertThat(env.fresh()).isEmpty()
            assertThat(before.licenceId).isEqualTo("LIC-2")
        }
    }

    @Test
    fun `INV-AC-9590 a file signed by a key that is not a trusted anchor is refused and records nothing`() {
        run { env ->
            val file = LicenceKey().file(LicenceKey.licence("LIC-9", "INST-1", term(today(30))))
            assertThat(env.refused("LIC-9", file)).isNotNull()
            assertThat(refusalOf { env.api.getLicenceStatus() }).isNotNull() // 404: nothing recorded
            assertThat(env.fresh()).isEmpty()
        }
    }

    @Test
    fun `INV-AC-9590 a changed byte, an extra member, another licenceId or another installation are refused`() {
        run { env ->
            val members = LicenceKey.licence("LIC-8", "INST-1", term(today(30)))
            val good = vendor.file(members)
            val changed = String(good).replace("2026-01-01", "2026-01-02").toByteArray()
            assertThat(env.refused("LIC-8", changed)).isNotNull()
            assertThat(env.refused("LIC-8", vendor.file(members, ",\"extra\":\"x\""))).isNotNull()
            assertThat(env.refused("LIC-OTHER", good)).isNotNull()
            assertThat(env.refused("LIC-7", vendor.file(LicenceKey.licence("LIC-7", "INST-2", term(today(30))))))
                .isNotNull()
            assertThat(env.refused("LIC-8", "not json".toByteArray())).isNotNull()
            assertThat(refusalOf { env.api.getLicenceStatus() }).isNotNull()
            env.install("LIC-8", good)
            assertThat(
                env.api
                    .getLicenceStatus()
                    .body!!
                    .licenceId,
            ).isEqualTo("LIC-8")
        }
    }

    @Test
    fun `INV-AC-9590 a term ending on the clock date is ACTIVE and one ending a day before is EXPIRED`() {
        run { env ->
            assertThat(
                env
                    .install(
                        "LIC-A",
                        vendor.file(LicenceKey.licence("LIC-A", "INST-1", term(today()))),
                    ).body!!
                    .status.value,
            ).isEqualTo("ACTIVE")
            assertThat(
                env
                    .install(
                        "LIC-B",
                        vendor.file(LicenceKey.licence("LIC-B", "INST-1", term(today(-1)))),
                    ).body!!
                    .status.value,
            ).isEqualTo("EXPIRED")
        }
    }

    @Test
    fun `INV-AC-9590 with the default trust binding every file is refused`() {
        run(anchors = false) { env ->
            assertThat(env.refused("LIC-D", vendor.file(LicenceKey.licence("LIC-D", "INST-1", term(today(30))))))
                .isNotNull()
        }
    }

    @Test
    fun `INV-AC-9589 the licence status read answers the installed licence with its stored expiry`() {
        run { env ->
            env.install("LIC-E", vendor.file(LicenceKey.licence("LIC-E", "INST-1", term(today(-3)))))
            val read = env.api.getLicenceStatus()
            assertThat(read.statusCode.value()).isEqualTo(200)
            assertThat(read.body!!.status.value).isEqualTo("EXPIRED")
            assertThat(read.body!!.expiresAt).isEqualTo(today(-3))
        }
    }

    @Test
    fun `INV-AC-2079 two trains behind raises DEP-004 and one train behind does not`() {
        run { env ->
            env.facts("2026.10", "2026.10", "2026.07")
            assertThat(env.fresh()).isEmpty()
            env.facts("2026.07", "2026.10", "2026.07") // one train behind: inside the window
            assertThat(env.fresh()).isEmpty()
            env.facts("2026.04", "2026.10", "2026.07")
            val raised = env.fresh().single()
            assertThat(payloadNode(raised)["depCode"].asString()).isEqualTo("DEP-004")
            assertThat(payloadNode(raised)["status"].asString()).isEqualTo("RAISED")
            env.facts("2026.04", "2026.10", "2026.07") // an equal repeat publishes nothing
            assertThat(env.fresh()).isEmpty()
        }
    }

    @Test
    fun `INV-AC-9591 DEP-004 is cleared with an event when the reported train enters the window`() {
        run { env ->
            env.facts("2026.04", "2026.10", "2026.07")
            env.fresh()
            env.facts("2026.07", "2026.10", "2026.07")
            val cleared = env.fresh().single()
            assertThat(payloadNode(cleared)["depCode"].asString()).isEqualTo("DEP-004")
            assertThat(payloadNode(cleared)["status"].asString()).isEqualTo("CLEARED")
        }
    }

    @Test
    fun `INV-AC-2079 the look-alike train 1_10_0 against a window holding 1_1_0 raises by equality, not prefix`() {
        run { env ->
            env.facts("1.10.0", "1.1.0")
            assertThat(env.fresh().single().dataJson).contains("DEP-004", "RAISED")
        }
    }

    @Test
    fun `INV-AC-2079 no report raises nothing`() {
        run { env -> assertThat(env.fresh()).isEmpty() }
    }

    @Test
    fun `INV-AC-2079 the train hook runs on a changed trainVersion only`() {
        val hook = AtomicInteger()
        run(hook = hook) { env ->
            env.facts("2026.10", "2026.10")
            assertThat(hook.get()).isZero() // the first value ever stored
            env.facts("2026.10", "2026.10") // an equal repeat
            env.facts("2026.10", "2026.10", "2026.07") // the window alone changes
            assertThat(hook.get()).isZero()
            env.facts("2026.11", "2026.11", "2026.10")
            assertThat(hook.get()).isEqualTo(1)
            env.facts("2026.11", "2026.11", "2026.10")
            assertThat(hook.get()).isEqualTo(1)
        }
    }
}
