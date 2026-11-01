package com.dynokore.gst.notification.application.alerting

import com.dynokore.gst.notification.domain.alerting.BreakGlassAction
import com.dynokore.gst.notification.domain.alerting.BreakGlassAlert
import com.dynokore.gst.notification.domain.alerting.BreakGlassAlertDraft
import com.dynokore.gst.notification.domain.alerting.BreakGlassAlertRaised
import com.dynokore.gst.notification.domain.alerting.BreakGlassDecision
import com.dynokore.gst.notification.domain.alerting.BreakGlassMessage
import com.dynokore.gst.notification.domain.alerting.BreakGlassMessageParameters
import com.dynokore.gst.notification.domain.alerting.BreakGlassScope
import com.dynokore.gst.notification.domain.alerting.BreakGlassWithheld
import com.dynokore.gst.notification.domain.alerting.CLASS_SECURITY
import com.dynokore.platform.tenancykernel.TenantId
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.File
import java.util.UUID

class BreakGlassHandlerTest {
    private val tenant = TenantId(UUID.fromString("00000000-0000-0000-0000-00000000000a"))
    private val other = TenantId(UUID.fromString("00000000-0000-0000-0000-00000000000b"))
    private val empty = TenantId(UUID.fromString("00000000-0000-0000-0000-00000000000c"))
    private val tenantHolders = mutableMapOf<TenantId, Set<String>>()
    private var installationHolders = setOf<String>()
    private val reads = mutableListOf<String>()
    private val asked = mutableListOf<String>()
    private val raised = mutableListOf<Pair<BreakGlassAlertDraft, BreakGlassMessageParameters>>()
    private val seen = mutableSetOf<String>()

    private val handler =
        BreakGlassHandler(
            object : ProcessedEvents {
                override fun firstTime(eventId: String) = seen.add(eventId)
            },
            object : SecurityOfficerHolders {
                override fun holders(
                    tenant: TenantId,
                    scope: BreakGlassScope,
                ): Set<String> {
                    asked += SECURITY_OFFICER_ROLE
                    reads += "$scope"
                    return if (scope == BreakGlassScope.TENANT) tenantHolders[tenant].orEmpty() else installationHolders
                }
            },
            object : BreakGlassAlertSink {
                override fun raise(
                    draft: BreakGlassAlertDraft,
                    message: BreakGlassMessageParameters,
                ) {
                    raised += draft to message
                }
            },
        )

    private fun event(
        code: String = "ACC-002",
        scope: BreakGlassScope = BreakGlassScope.TENANT,
    ) = BreakGlassAlertRaised(code, scope, BreakGlassAction.UNLOCK, "unlock-1")

    /** The inbox row is written in the transaction of the handler: a throw rolls the mark back (INV-REQ-1222). */
    private fun transactional(
        eventId: String,
        tenant: TenantId,
        event: BreakGlassAlertRaised,
    ) {
        val before = seen.toSet()
        try {
            handler.handle(eventId, tenant, event)
        } catch (e: BreakGlassNotDelivered) {
            seen.clear()
            seen.addAll(before)
            throw e
        }
    }

    @Test
    fun `INV-AC-2384 one alert reaches the sorted holders at once with no tier`() {
        tenantHolders[tenant] = setOf("p2", "p1", "p3")
        handler.handle("ev-1", tenant, event())
        raised.size shouldBe 1
        raised[0].first.recipients shouldBe listOf("p1", "p2", "p3")
        raised[0].first.tiers shouldBe emptyList()
        raised[0].second shouldBe BreakGlassMessage.of(event())
    }

    @Test
    fun `INV-AC-2384 the same event delivered twice raises one alert`() {
        tenantHolders[tenant] = setOf("p1")
        handler.handle("ev-1", tenant, event())
        handler.handle("ev-1", tenant, event())
        raised.size shouldBe 1
        reads shouldBe listOf("TENANT")
    }

    @Test
    fun `INV-AC-1934 a TENANT event reads the TENANT holders only`() {
        tenantHolders[tenant] = setOf("t1")
        installationHolders = setOf("i1")
        handler.handle("ev-1", tenant, event(scope = BreakGlassScope.TENANT))
        reads shouldBe listOf("TENANT")
        raised[0].first.recipients shouldBe listOf("t1")
    }

    @Test
    fun `INV-AC-1934 an INSTALLATION event reads the INSTALLATION holders and never the tenant holders`() {
        tenantHolders[tenant] = setOf("t1")
        installationHolders = setOf("i2", "i1")
        handler.handle("ev-1", tenant, event(scope = BreakGlassScope.INSTALLATION))
        reads shouldBe listOf("INSTALLATION")
        raised[0].first.recipients shouldBe listOf("i1", "i2")
    }

    @Test
    fun `INV-AC-11517 holders of another tenant are not recipients and an empty tenant is not dropped`() {
        tenantHolders[tenant] = setOf("p3", "p1", "p2")
        tenantHolders[other] = setOf("x1")
        transactional("ev-1", tenant, event())
        transactional("ev-1", tenant, event())
        raised.size shouldBe 1
        raised[0].first.alertClass shouldBe CLASS_SECURITY
        raised[0].first.recipients shouldBe listOf("p1", "p2", "p3")
        val thrown = assertThrows<BreakGlassNotDelivered> { transactional("ev-2", empty, event()) }
        thrown.reason shouldBe BreakGlassWithheld.NO_RECIPIENT
        raised.size shouldBe 1
        seen.contains("ev-2") shouldBe false
        assertThrows<BreakGlassNotDelivered> { transactional("ev-2", empty, event()) }
        asked.toSet() shouldBe setOf("security.officer")
    }

    @Test
    fun `INV-AC-11518 the decision drops blank and duplicate entries and the handler raises once`() {
        val list = listOf("p1", "", "p2", "p1", "p3")
        val decision = BreakGlassAlert.decide(event(), list) as BreakGlassDecision.Raise
        decision.draft.alertClass shouldBe CLASS_SECURITY
        decision.draft.recipients shouldBe listOf("p1", "p2", "p3")
        decision.draft.tiers shouldBe emptyList()
        BreakGlassAlert.decide(event(), emptyList()) shouldBe
            BreakGlassDecision.Withhold(BreakGlassWithheld.NO_RECIPIENT)
        tenantHolders[tenant] = list.toSet()
        handler.handle("ev-1", tenant, event())
        raised.size shouldBe 1
    }

    @Test
    fun `INV-AC-11518 no holder withholds NO_RECIPIENT and firstTime is true again`() {
        assertThrows<BreakGlassNotDelivered> { transactional("ev-1", tenant, event()) }.reason shouldBe
            BreakGlassWithheld.NO_RECIPIENT
        raised.size shouldBe 0
        seen.add("ev-1") shouldBe true
    }

    @Test
    fun `INV-AC-1934 an unsupported code is not delivered`() {
        tenantHolders[tenant] = setOf("p1")
        assertThrows<BreakGlassNotDelivered> { transactional("ev-1", tenant, event(code = "ACC-003")) }.reason shouldBe
            BreakGlassWithheld.UNSUPPORTED_CODE
        raised.size shouldBe 0
        seen.contains("ev-1") shouldBe false
    }

    @Test
    fun `INV-AC-1934 the role constant is security dot officer`() {
        SECURITY_OFFICER_ROLE shouldBe "security.officer"
    }

    @Test
    fun `INV-AC-1934 the interfaces the file declares are exactly the two new ports`() {
        val source = "BreakGlassHandler.kt"
        val dir =
            File(
                BreakGlassHandler::class.java.protectionDomain.codeSource.location
                    .toURI(),
            ).resolve(BreakGlassHandler::class.java.packageName.replace('.', '/'))
        val declared =
            dir
                .listFiles { f -> f.extension == "class" && '$' !in f.name }
                .orEmpty()
                .filter { String(it.readBytes(), Charsets.ISO_8859_1).contains(source) }
                .map { Class.forName(BreakGlassHandler::class.java.packageName + "." + it.nameWithoutExtension) }
                .filter { it.isInterface }
                .map { it.simpleName }
                .sorted()
        declared shouldBe listOf("BreakGlassAlertSink", "SecurityOfficerHolders")
    }
}
