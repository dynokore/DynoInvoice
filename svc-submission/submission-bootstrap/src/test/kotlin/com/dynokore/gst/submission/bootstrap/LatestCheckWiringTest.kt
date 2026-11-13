package com.dynokore.gst.submission.bootstrap

import com.dynokore.gst.submission.adapters.gate.AuthorityReplicaOverrideAuthority
import com.dynokore.gst.submission.application.gate.OverrideAuthority
import com.dynokore.gst.submission.application.readiness.GetLatestReadinessCheck
import com.dynokore.gst.submission.application.readiness.LatestCheckResult
import com.dynokore.gst.submission.application.readiness.ReadinessCheckReader
import com.dynokore.gst.submission.application.readiness.StoredReadinessCheck
import com.dynokore.gst.submission.domain.gate.CheckVerdict
import com.dynokore.platform.serviceruntime.replica.AuthorityReplica
import com.dynokore.platform.serviceruntime.replica.OperationRoleMap
import com.dynokore.platform.tenancykernel.TenantContext
import com.dynokore.platform.tenancykernel.TenantId
import com.dynokore.platform.tenancykernel.TransactionRunner
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.kotest.matchers.types.shouldBeSameInstanceAs
import org.jooq.SQLDialect
import org.jooq.impl.DSL
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

/** The wiring of `getLatestReadinessCheck` built by [LatestCheckConfiguration] (INV-AC-21137, TPL-18). */
class LatestCheckWiringTest {
    private val clock = Clock.fixed(Instant.parse("2026-11-14T00:00:00Z"), ZoneOffset.UTC)
    private val tenant = TenantId(UUID.fromString("00000000-0000-0000-0000-00000000000a"))
    private val events = mutableListOf<String>()
    private val check =
        StoredReadinessCheck(7, "chk-7", "E1", "P1", CheckVerdict.READY, clock.instant(), "u1", null)

    private val tenants =
        object : TenantContext {
            override fun current(): TenantId = tenant

            override fun currentActor(): String? = null

            override fun <T> bind(
                tenant: TenantId,
                actor: String,
                block: () -> T,
            ): T = block()
        }

    private val runner =
        object : TransactionRunner {
            override fun <T> inTransaction(block: () -> T): T {
                events += "begin"
                return block().also { events += "end" }
            }
        }

    private val store =
        object : ReadinessCheckReader {
            override fun latest(
                entityRef: String,
                periodRef: String,
            ): StoredReadinessCheck? {
                events += "store"
                return check
            }

            override fun page(
                entityRef: String,
                periodRef: String,
                limit: Int,
                before: Long?,
            ): List<StoredReadinessCheck> = emptyList()
        }

    private fun authority(allow: Boolean) =
        OverrideAuthority { _, _, _ ->
            events += "decide"
            allow
        }

    @Test
    fun `INV-AC-21137 the context holds one bean each and the authority is the replica-backed bean`() {
        ApplicationContextRunner()
            .withBean(org.jooq.DSLContext::class.java, { DSL.using(SQLDialect.POSTGRES) })
            .withBean(TenantContext::class.java, { tenants })
            .withBean(TransactionRunner::class.java, { runner })
            .withBean(AuthorityReplica::class.java, { AuthorityReplica(clock, OperationRoleMap { emptyMap() }) })
            .withUserConfiguration(LatestCheckConfiguration::class.java)
            .run { context ->
                context.getBeansOfType(ReadinessCheckReader::class.java).size shouldBe 1
                context.getBeansOfType(OverrideAuthority::class.java).size shouldBe 1
                context.getBeansOfType(GetLatestReadinessCheck::class.java).size shouldBe 1
                val authority = context.getBean(OverrideAuthority::class.java)
                authority.shouldBeInstanceOf<AuthorityReplicaOverrideAuthority>()
                val useCase = context.getBean(GetLatestReadinessCheck::class.java)
                val field =
                    GetLatestReadinessCheck::class.java.getDeclaredField("authority").apply {
                        isAccessible =
                            true
                    }
                field.get(useCase) shouldBeSameInstanceAs authority
            }
    }

    @Test
    fun `INV-AC-21137 an allowed caller opens one transaction and the store call is inside it`() {
        val useCase = GetLatestReadinessCheck(TransactionalReadinessCheckReader(store, runner), authority(true))
        useCase.execute("u1", "E1", "P1") shouldBe LatestCheckResult.Found(check)
        events shouldBe listOf("decide", "begin", "store", "end")
    }

    @Test
    fun `INV-AC-21137 a denied caller opens no transaction and reads nothing`() {
        val useCase = GetLatestReadinessCheck(TransactionalReadinessCheckReader(store, runner), authority(false))
        useCase.execute("u1", "E1", "P1") shouldBe LatestCheckResult.Forbidden
        events shouldBe listOf("decide")
    }
}
