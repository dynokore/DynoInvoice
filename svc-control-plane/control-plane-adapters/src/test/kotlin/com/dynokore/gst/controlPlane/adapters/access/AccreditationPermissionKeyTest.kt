package com.dynokore.gst.controlPlane.adapters.access

import com.dynokore.gst.controlPlane.application.accreditation.AccreditationPermission
import com.dynokore.gst.controlPlane.application.accreditation.AccreditationRecords
import com.dynokore.gst.controlPlane.application.accreditation.RecordAccreditation
import com.dynokore.gst.controlPlane.application.accreditation.RecordAccreditationCommand
import com.dynokore.gst.controlPlane.application.pack.RequestContext
import com.dynokore.gst.controlPlane.application.release.PublishTrain
import com.dynokore.gst.controlPlane.application.release.PublishTrainCommand
import com.dynokore.gst.controlPlane.domain.accreditation.SubmissionType
import com.dynokore.gst.controlPlane.domain.pack.ActorId
import com.dynokore.gst.controlPlane.domain.release.TrainKind
import com.dynokore.gst.controlPlane.domain.release.TrainVersion
import com.dynokore.platform.serviceruntime.identity.WorkloadToken
import com.sun.net.httpserver.HttpServer
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * The adapter of INV-T-9784 (`AuthoriseOperatorPermissions`, not edited here) with the accreditation key against a stub
 * of access-svc `authorise` that answers per (principalRef, permission, scope). INV-T-7322, INV-Q-13897 A.
 */
class AccreditationPermissionKeyTest {
    private val allowed = LinkedHashSet<Triple<String, String, String>>()
    private val requests = ArrayList<Triple<String, String, String>>()
    private var status = 200
    private var rawBody: String? = null
    private var delayMillis = 0L
    private val server: HttpServer =
        HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0).apply {
            createContext(AuthoriseClient.PATH) { exchange ->
                val q =
                    exchange.requestURI.rawQuery
                        .split("&")
                        .associate {
                            it.substringBefore("=") to URLDecoder.decode(it.substringAfter("="), StandardCharsets.UTF_8)
                        }
                val key = Triple(q.getValue("principalRef"), q.getValue("permission"), q.getValue("scope"))
                requests += key
                Thread.sleep(delayMillis)
                val decided = if (key in allowed) """{"decision":"ALLOW"}""" else """{"decision":"DENY"}"""
                val answer = (rawBody ?: decided).toByteArray()
                exchange.sendResponseHeaders(status, answer.size.toLong())
                exchange.responseBody.use { it.write(answer) }
            }
            start()
        }

    @AfterEach
    fun stop() = server.stop(0)

    private fun permissions(timeout: Duration = Duration.ofSeconds(2)) =
        AuthoriseOperatorPermissions(
            AuthoriseClient(
                URI.create("http://127.0.0.1:${server.address.port}"),
                { WorkloadToken("workload") },
                { "Bearer operator" },
                timeout,
            ),
        )

    private fun allow(
        actor: String,
        key: String,
    ) {
        allowed += Triple(actor, key, "platform")
    }

    private val key = AccreditationPermission.RECORD

    @Test
    fun `the record key is accreditation dot record`() {
        AccreditationPermission.RECORD shouldBe "accreditation.record"
    }

    @Test
    fun `ALLOW gives true for the record key and the request carries the key and the platform scope`() {
        allow("alice", key)
        permissions().holds("alice", key) shouldBe true
        requests shouldBe listOf(Triple("alice", "accreditation.record", "platform"))
        requests.none { it.third.startsWith("entity:") } shouldBe true
    }

    @Test
    fun `DENY, a 500, a timeout and an unreadable body each give false`() {
        permissions().holds("alice", key) shouldBe false // DENY
        allow("alice", key)
        status = 500
        permissions().holds("alice", key) shouldBe false
        status = 200
        rawBody = "not json"
        permissions().holds("alice", key) shouldBe false
        rawBody = null
        delayMillis = 1500
        permissions(Duration.ofMillis(200)).holds("alice", key) shouldBe false
    }

    @Test
    fun `an unreachable access service gives false`() {
        server.stop(0)
        permissions().holds("alice", key) shouldBe false
    }

    @Test
    fun `look-alike keys allowed alone give a 403 for recordAccreditation and nothing is stored`() {
        val stored = ArrayList<Any>()
        val records =
            object : AccreditationRecords {
                override fun latest() = null

                override fun find(
                    imdaReference: String,
                    currentUntil: LocalDate,
                ) = null

                override fun save(record: com.dynokore.gst.controlPlane.domain.accreditation.AccreditationRecord) {
                    stored += record
                }
            }
        val operation =
            RecordAccreditation(
                records,
                permissions(),
                Clock.fixed(Instant.parse("2026-10-12T00:00:00Z"), ZoneOffset.UTC),
            )
        val command =
            RecordAccreditationCommand(
                ActorId("alice"),
                LocalDate.parse("2026-09-01"),
                LocalDate.parse("2027-09-01"),
                "IMDA-1",
                listOf(SubmissionType.TYPE_1A),
                emptyList(),
                RequestContext("c", "k"),
            )
        listOf(
            "accreditation.records",
            "Accreditation.record",
            "accreditation.record ",
            "release-train.publish",
        ).forEach {
            allow("alice", it)
        }
        operation.permits("alice", command) shouldBe false
        stored shouldBe emptyList<Any>()
        allow("alice", key)
        operation.permits("alice", command) shouldBe true
        requests.map { it.second }.toSet() shouldBe
            setOf("accreditation.record") // the one key the operation asks, never a role
    }

    @Test
    fun `publishTrain bound to the stub is permitted for the publish key and not for the certify key alone`() {
        val publish =
            PublishTrain(
                trains =
                    object : com.dynokore.gst.controlPlane.application.release.TrainRepository {
                        override fun find(version: TrainVersion) = null

                        override fun save(train: com.dynokore.gst.controlPlane.domain.release.ReleaseTrain) = Unit
                    },
                permissions = permissions(),
                evidence = { null },
                accreditation = { error("not asked") },
                clock = Clock.systemUTC(),
            )
        val command =
            PublishTrainCommand(
                ActorId("rita"),
                TrainVersion("2026.10.0"),
                TrainKind.TRAIN,
                false,
                false,
                RequestContext("c", "k"),
            )
        allow("rita", "release-train.certify")
        publish.permits("rita", command) shouldBe false
        allow("rita", "release-train.publish")
        publish.permits("rita", command) shouldBe true
        requests.map { it.second }.toSet() shouldBe
            setOf("release-train.publish", "release-train.certify").intersect(
                requests.map { it.second }.toSet(),
            )
        requests.all { it.second.startsWith("release-train.") && it.third == "platform" } shouldBe true
    }
}
