package com.dynokore.gst.access.bootstrap

import com.dynokore.gst.access.adapters.events.BreakGlassAlertOutbox
import com.dynokore.gst.access.adapters.http.ApiConflict
import com.dynokore.gst.access.adapters.http.ApiRefusal
import com.dynokore.gst.access.adapters.http.BreakGlassController
import com.dynokore.gst.access.application.idp.DirectoryGroupMembers
import com.dynokore.gst.access.contract.model.BreakGlassActionRequest
import com.dynokore.gst.access.contract.model.UnlockBreakGlassRequest
import com.dynokore.gst.access.domain.role.Role
import com.dynokore.platform.serviceruntime.http.ClosedErrorCode
import com.dynokore.platform.serviceruntime.identity.ServiceCaller
import com.dynokore.platform.tenancykernel.TenantContext
import com.dynokore.platform.tenancykernel.TenantId
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.assertj.core.api.Assertions.catchThrowable
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.assertj.AssertableWebApplicationContext
import java.sql.SQLException
import java.time.Duration
import java.util.UUID

/**
 * INV-AC-9167: `recordBreakGlassAction` stores the verified calling service with the action, writes it into the audit
 * detail and refuses a caller that is not mapped to a service (INV-REQ-897, INV-Q-9113 A). The controller is driven
 * through the real context over a real PostgreSQL; the caller is the settable bean of [BoundaryFixture] (INV-T-11611).
 */
class BreakGlassCallerServiceTest {
    private val tenant = TenantId(UUID.randomUUID())
    private val auditType = "com.dynokore.gst.platform.AuditEntryRecorded.v1"
    private val mapper =
        tools.jackson.databind.json.JsonMapper
            .builder()
            .findAndAddModules()
            .build()

    private inner class Env(
        val context: AssertableWebApplicationContext,
    ) {
        val tenants = context.getBean(TenantContext::class.java)
        val api = context.getBean(BreakGlassController::class.java)
        val caller = context.getBean(ServiceCaller::class.java) as MovableServiceCaller
        val captured = context.getBean(CapturedEvents::class.java)
        val clock = context.getBean(java.time.Clock::class.java) as MovableClock

        init {
            Seeds.grant(context, tenant, SeedSpec("IDN-ADM", Role.TENANT_ADMIN))
            Seeds.grant(context, tenant, SeedSpec("IDN-APP", Role.TAX_APPROVER))
            captured.envelopes.clear()
        }

        fun unlock(): String =
            tenants
                .bind(tenant, "IDN-ADM") {
                    api.unlockBreakGlass(
                        UUID.randomUUID(),
                        UnlockBreakGlassRequest(tenant.toString(), listOf("IDN-ADM", "IDN-APP")),
                    )
                }.body!!
                .unlockRef

        fun record(
            unlockRef: String,
            action: String,
        ) = tenants.bind(tenant, "IDN-ADM") {
            api.recordBreakGlassAction(
                unlockRef,
                UUID.randomUUID(),
                BreakGlassActionRequest(BreakGlassActionRequest.Action.valueOf(action)),
            )
        }

        fun alerts() = captured.envelopes.filter { it.type == BreakGlassAlertOutbox.EVENT_TYPE }

        fun audits() =
            captured.envelopes.filter {
                it.type == auditType &&
                    it.dataJson.contains("\"recordBreakGlassAction\"")
            }

        fun rows(): Int =
            BoundaryFixture.asSuperuser { c ->
                c.prepareStatement("select count(*) from access.break_glass_action where tenant_id = ?").use { s ->
                    s.setObject(1, tenant.value)
                    s.executeQuery().use { rs ->
                        rs.next()
                        rs.getInt(1)
                    }
                }
            }

        /** `caller_service`, `created_by` and `action` of the stored row [actionRef], read as the superuser. */
        fun stored(actionRef: String): Triple<String?, String, String> =
            BoundaryFixture.asSuperuser { c ->
                c
                    .prepareStatement(
                        "select caller_service, created_by, action from access.break_glass_action " +
                            "where tenant_id = ? and action_ref = ?",
                    ).use { s ->
                        s.setObject(1, tenant.value)
                        s.setString(2, actionRef)
                        s.executeQuery().use { rs ->
                            check(rs.next()) { "no row for $actionRef" }
                            Triple(rs.getString(1), rs.getString(2), rs.getString(3))
                        }
                    }
            }

        /** One counter triple: rows, audit events named `recordBreakGlassAction`, alert events. */
        fun counts() = Triple(rows(), audits().size, alerts().size)
    }

    private fun run(block: (Env) -> Unit) {
        BoundaryFixture
            .runner()
            .withBean(DirectoryGroupMembers::class.java, { MovableDirectory() })
            .run { context ->
                context.startupFailure?.let { throw it }
                block(Env(context))
            }
    }

    private fun auditReason(event: com.dynokore.platform.outboxandqueue.Envelope) =
        mapper.readTree(event.dataJson).path("reason").asString()

    @Test
    fun `INV-AC-9167 a recorded DRAIN stores ingestion-svc and the audit detail names it`() =
        run { env ->
            val ref = env.unlock()
            env.captured.envelopes.clear()
            env.caller.value = "ingestion-svc"
            val response = env.record(ref, "DRAIN")
            assertThat(response.statusCode.value()).isEqualTo(201)
            val (callerService, createdBy, action) = env.stored(response.body!!.actionRef)
            assertThat(callerService).isEqualTo("ingestion-svc")
            // the end-user actor is still the creator of the row
            assertThat(createdBy).isEqualTo("IDN-ADM")
            assertThat(action).isEqualTo("DRAIN")
            val audit = env.audits().single()
            assertThat(auditReason(audit)).isEqualTo("DRAIN by ingestion-svc")
            assertThat(mapper.readTree(audit.dataJson).path("actor").asString()).isEqualTo("IDN-ADM")
            // one ACC-002 for the action, as before
            val alert = env.alerts().single()
            assertThat(alert.type).isEqualTo(BreakGlassAlertOutbox.EVENT_TYPE)
            assertThat(alert.dataJson).contains("\"accCode\":\"ACC-002\"", "\"action\":\"DRAIN\"", ref)
        }

    @Test
    fun `INV-AC-9167 the look-alike ingestion-svc2 is stored as it is and is not ingestion-svc`() =
        run { env ->
            val ref = env.unlock()
            env.caller.value = "ingestion-svc2"
            val response = env.record(ref, "DRAIN")
            val stored = env.stored(response.body!!.actionRef).first
            assertThat(stored).isEqualTo("ingestion-svc2").isNotEqualTo("ingestion-svc")
            assertThat(auditReason(env.audits().last())).isEqualTo("DRAIN by ingestion-svc2")
        }

    @Test
    fun `INV-AC-9167 a caller that is not mapped to a service is refused and stores nothing`() =
        run { env ->
            val ref = env.unlock()
            env.caller.value = "ingestion-svc"
            env.record(ref, "VIEW")
            val before = env.counts()
            env.caller.value = null
            assertThatThrownBy { env.record(ref, "DRAIN") }
                .isInstanceOf(ApiRefusal::class.java)
                .hasMessageContaining("SRT-004")
                .extracting { (it as ApiRefusal).code }
                .isEqualTo(ClosedErrorCode.ACCESS_DENIED)
            assertThat(env.counts()).isEqualTo(before)
            assertThat(before).isEqualTo(Triple(1, 1, 2))
        }

    @Test
    fun `INV-AC-9167 an expired session is a conflict and an unknown session is 404, with the caller set`() =
        run { env ->
            val ref = env.unlock()
            env.caller.value = "ingestion-svc"
            env.clock.advance(Duration.ofHours(5))
            val before = env.counts()
            assertThatThrownBy { env.record(ref, "DRAIN") }.isInstanceOf(ApiConflict::class.java)
            assertThat(env.counts()).isEqualTo(before)
            assertThatThrownBy { env.record("BG-NONE", "DRAIN") }
                .isInstanceOf(ApiRefusal::class.java)
                .hasMessageContaining("SRT-005")
            assertThat(env.counts()).isEqualTo(before)
        }

    @Test
    fun `INV-AC-9167 a body with callerService is ignored and the caller bean decides the stored value`() =
        run { env ->
            val body = """{"action":"DRAIN","callerService":"evil-svc"}"""
            val request = mapper.readValue(body, BreakGlassActionRequest::class.java)
            assertThat(request).isEqualTo(BreakGlassActionRequest(BreakGlassActionRequest.Action.DRAIN))
            assertThat(request.javaClass.declaredFields.map { it.name }).doesNotContain("callerService")
            val ref = env.unlock()
            env.caller.value = "ingestion-svc"
            val response =
                env.tenants.bind(tenant, "IDN-ADM") {
                    env.api.recordBreakGlassAction(ref, UUID.randomUUID(), request)
                }
            assertThat(env.stored(response.body!!.actionRef).first).isEqualTo("ingestion-svc")
        }

    @Test
    fun `INV-AC-9167 the CHECK of caller_service accepts null and 64 characters and refuses empty and 65`() =
        run { env ->
            val ref = env.unlock()

            fun insert(value: String?): Int =
                BoundaryFixture.asSuperuser { c ->
                    c
                        .prepareStatement(
                            "insert into access.break_glass_action (id, tenant_id, action_ref, unlock_ref, action, " +
                                "created_at, created_by, updated_at, updated_by, version, caller_service) " +
                                "values (?, ?, ?, ?, 'VIEW', now(), 'direct', now(), 'direct', 0, ?)",
                        ).use { s ->
                            s.setObject(1, UUID.randomUUID())
                            s.setObject(2, tenant.value)
                            s.setString(3, "BGA-" + UUID.randomUUID().toString().take(8))
                            s.setString(4, ref)
                            s.setString(5, value)
                            s.executeUpdate()
                        }
                }
            assertThat(insert(null)).isEqualTo(1)
            assertThat(insert("a".repeat(64))).isEqualTo(1)
            val refusedEmpty = catchThrowable { insert("") }
            val refused65 = catchThrowable { insert("a".repeat(65)) }
            listOf(refusedEmpty, refused65).forEach {
                assertThat(it).isInstanceOf(SQLException::class.java)
                // 23514 check_violation
                assertThat((it as SQLException).sqlState).isEqualTo("23514")
            }
            assertThat(env.rows()).isEqualTo(2)
        }
}
