package com.dynokore.gst.masterdata.adapters.http

import com.dynokore.gst.catalogue.ErrorCode
import com.dynokore.gst.masterdata.adapters.connection.InstallationEnvironment
import com.dynokore.gst.masterdata.adapters.persistence.JooqCredentialExpiries
import com.dynokore.gst.masterdata.application.connection.ConnectionStore
import com.dynokore.gst.masterdata.application.connection.CreateConnection
import com.dynokore.gst.masterdata.application.connection.CreateConnectionCommand
import com.dynokore.gst.masterdata.application.connection.CreateConnectionOutcome
import com.dynokore.gst.masterdata.application.connection.CredentialStateStore
import com.dynokore.gst.masterdata.application.connection.GetConnection
import com.dynokore.gst.masterdata.application.connection.GetConnectionHealth
import com.dynokore.gst.masterdata.application.connection.GetConnectionHealthOutcome
import com.dynokore.gst.masterdata.application.connection.GetConnectionOutcome
import com.dynokore.gst.masterdata.application.connection.RotateCredential
import com.dynokore.gst.masterdata.application.connection.RotateCredentialOutcome
import com.dynokore.gst.masterdata.application.connection.TestConnection
import com.dynokore.gst.masterdata.application.connection.TestConnectionCommand
import com.dynokore.gst.masterdata.application.connection.TestConnectionOutcome
import com.dynokore.gst.masterdata.contract.api.ConnectionsApi
import com.dynokore.gst.masterdata.contract.model.ConnectionHealth
import com.dynokore.gst.masterdata.contract.model.ConnectionHealthLastFailure
import com.dynokore.gst.masterdata.contract.model.ConnectionRecord
import com.dynokore.gst.masterdata.contract.model.ConnectionTestResult
import com.dynokore.gst.masterdata.contract.model.CreateConnectionRequest
import com.dynokore.gst.masterdata.contract.model.RotateConnectionCredentialRequest
import com.dynokore.gst.masterdata.domain.identity.PersonRef
import com.dynokore.platform.serviceruntime.http.ClosedErrorCode
import com.dynokore.platform.tenancykernel.TenantContext
import com.dynokore.platform.tenancykernel.TransactionRunner
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.RestController
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import com.dynokore.gst.masterdata.domain.connection.ConnectionHealth as DomainHealth
import com.dynokore.gst.masterdata.domain.connection.ConnectionRecord as DomainRecord

/**
 * `createConnection`, `getConnection` and `testConnection` (INV-T-18774, INV-REQ-834, INV-REQ-835). The permission of
 * each operation (`connection.configure`, `connection.view`, `connection.test`) is decided by its use case through the
 * replica, first, before anything is read or stored; a denied caller gets 403 SRT-004. The answer of a record holds
 * exactly the six contract properties and never a secret. The caller's environment of `testConnection` is read only
 * from [InstallationEnvironment] and passed unchanged: no request body, header, query parameter or claim is read
 * for it.
 */
@RestController
// open: the generated API interface is @Validated, so Spring proxies the controller
open class ConnectionsController(
    private val create: CreateConnection,
    private val read: GetConnection,
    private val test: TestConnection,
    private val health: GetConnectionHealth,
    private val rotate: RotateCredential,
    private val extras: HealthExtras,
    private val tenants: TenantContext,
    private val installation: InstallationEnvironment,
    private val clock: Clock,
) : ConnectionsApi {
    override fun createConnection(
        idempotencyKey: UUID,
        createConnectionRequest: CreateConnectionRequest,
    ): ResponseEntity<ConnectionRecord> =
        created(
            create.handle(
                CreateConnectionCommand(
                    caller = caller(),
                    externalService = createConnectionRequest.externalService.value,
                    endpoint = createConnectionRequest.endpoint,
                    credentialRef = createConnectionRequest.credentialRef,
                    environmentTag = createConnectionRequest.environmentTag.value,
                    ownerService = createConnectionRequest.ownerService,
                    credentialExpiresAt = createConnectionRequest.credentialExpiresAt?.toInstant(),
                ),
            ),
        )

    override fun getConnection(connectionRef: String): ResponseEntity<ConnectionRecord> =
        found(read.handle(caller(), connectionRef))

    override fun testConnection(
        connectionRef: String,
        idempotencyKey: UUID,
    ): ResponseEntity<ConnectionTestResult> =
        tested(
            connectionRef,
            test.handle(TestConnectionCommand(caller(), connectionRef, installation.text())),
        )

    override fun getConnectionHealth(connectionRef: String): ResponseEntity<ConnectionHealth> =
        when (val outcome = health.handle(caller(), connectionRef)) {
            is GetConnectionHealthOutcome.Found -> ResponseEntity.ok(wire(outcome.health, extras.of(connectionRef)))
            GetConnectionHealthOutcome.Unknown -> throw ApiRefusal(ClosedErrorCode.NOT_FOUND)
            GetConnectionHealthOutcome.Forbidden -> throw ApiRefusal(ClosedErrorCode.ACCESS_DENIED)
        }

    override fun rotateCredential(
        connectionRef: String,
        idempotencyKey: UUID,
        rotateConnectionCredentialRequest: RotateConnectionCredentialRequest,
    ): ResponseEntity<ConnectionRecord> =
        when (
            val outcome =
                rotate.handle(
                    caller(),
                    connectionRef,
                    rotateConnectionCredentialRequest.credentialRef,
                    rotateConnectionCredentialRequest.credentialExpiresAt?.toInstant(),
                )
        ) {
            is RotateCredentialOutcome.Rotated -> {
                ResponseEntity.ok(wire(extras.record(connectionRef), outcome.rotation.currentRef))
            }

            RotateCredentialOutcome.Forbidden -> {
                throw ApiRefusal(ClosedErrorCode.ACCESS_DENIED)
            }

            RotateCredentialOutcome.Unknown -> {
                throw ApiRefusal(ClosedErrorCode.NOT_FOUND)
            }

            RotateCredentialOutcome.OverlapNotConfigured -> {
                throw ApiRefusal(ClosedErrorCode.SERVICE_NOT_READY)
            }

            RotateCredentialOutcome.ReplacementUnavailable,
            RotateCredentialOutcome.SameAsCurrent,
            -> {
                throw ApiRefusal(ClosedErrorCode.CONTRACT_VIOLATION)
            }
        }

    private fun caller(): PersonRef =
        tenants
            .currentActor()
            ?.takeIf { it.isNotBlank() && it.length <= PersonRef.MAX }
            ?.let(::PersonRef)
            ?: throw ApiRefusal(ClosedErrorCode.ACCESS_DENIED)

    /** A total `when` over [CreateConnectionOutcome]: a new member fails to compile. */
    private fun created(outcome: CreateConnectionOutcome): ResponseEntity<ConnectionRecord> =
        when (outcome) {
            is CreateConnectionOutcome.Stored -> {
                ResponseEntity.status(HttpStatus.CREATED).body(wire(outcome.record))
            }

            CreateConnectionOutcome.Forbidden -> {
                throw ApiRefusal(ClosedErrorCode.ACCESS_DENIED)
            }

            CreateConnectionOutcome.UnknownEnvironmentTag -> {
                throw CatalogueRefusal(ErrorCode.MDM_016, HttpStatus.BAD_REQUEST.value())
            }
        }

    private fun found(outcome: GetConnectionOutcome): ResponseEntity<ConnectionRecord> =
        when (outcome) {
            is GetConnectionOutcome.Found -> ResponseEntity.ok(wire(outcome.record))
            GetConnectionOutcome.Unknown -> throw ApiRefusal(ClosedErrorCode.NOT_FOUND)
            GetConnectionOutcome.Forbidden -> throw ApiRefusal(ClosedErrorCode.ACCESS_DENIED)
        }

    private fun tested(
        connectionRef: String,
        outcome: TestConnectionOutcome,
    ): ResponseEntity<ConnectionTestResult> =
        when (outcome) {
            is TestConnectionOutcome.Tested -> {
                ResponseEntity.ok(
                    ConnectionTestResult(
                        connectionRef = connectionRef,
                        classification = ConnectionTestResult.Classification.valueOf(outcome.result.resultClass.name),
                        checkedAt = clock.instant().atOffset(ZoneOffset.UTC),
                        detail = outcome.result.detail,
                    ),
                )
            }

            TestConnectionOutcome.Unknown -> {
                throw ApiRefusal(ClosedErrorCode.NOT_FOUND)
            }

            TestConnectionOutcome.Forbidden -> {
                throw ApiRefusal(ClosedErrorCode.ACCESS_DENIED)
            }

            TestConnectionOutcome.UnknownEnvironment -> {
                throw CatalogueRefusal(ErrorCode.MDM_017, HttpStatus.INTERNAL_SERVER_ERROR.value())
            }

            TestConnectionOutcome.EnvironmentMismatchRefused -> {
                throw CatalogueRefusal(ErrorCode.MDM_018, HttpStatus.FORBIDDEN.value())
            }
        }

    /** Exactly the six contract properties; the record type has no property that can hold a secret. */
    private fun wire(record: DomainRecord): ConnectionRecord =
        ConnectionRecord(
            connectionRef = record.connectionRef,
            externalService = ConnectionRecord.ExternalService.forValue(record.externalService),
            endpoint = record.endpoint,
            credentialRef = record.credentialRef,
            environmentTag = ConnectionRecord.EnvironmentTag.forValue(record.environmentTag.name),
            ownerService = record.ownerService,
        )

    private fun wire(
        record: DomainRecord,
        credentialRef: String,
    ): ConnectionRecord = wire(record.copy(credentialRef = credentialRef))

    private fun wire(
        health: DomainHealth,
        extra: HealthExtra,
    ): ConnectionHealth =
        ConnectionHealth(
            connectionRef = health.connectionRef,
            state = ConnectionHealth.State.valueOf(health.state.name),
            breakerState = ConnectionHealth.BreakerState.valueOf(health.breakerState.name),
            successRate = health.successRate?.let(BigDecimal::valueOf),
            latencyMillis = health.latencyMillis?.let(Math::toIntExact),
            measuredOverSeconds = health.measuredOver.seconds.toInt(),
            lastFailure =
                health.lastFailure?.let {
                    ConnectionHealthLastFailure(
                        at = it.at.atOffset(ZoneOffset.UTC),
                        classification =
                            ConnectionHealthLastFailure.Classification.valueOf(
                                it.failure.classification.name,
                            ),
                        detail = it.failure.detail,
                    )
                },
            credentialExpiresAt = extra.credentialExpiresAt?.atOffset(ZoneOffset.UTC),
            overlapEndsAt = extra.overlapEndsAt?.atOffset(ZoneOffset.UTC),
        )
}

/** The two optional figures of `ConnectionHealth` that are not health: the credential expiry and the overlap end. */
data class HealthExtra(
    val credentialExpiresAt: Instant?,
    val overlapEndsAt: Instant?,
)

/** Reads, in one transaction, what the answers add to the use cases' outcomes; it writes nothing. */
class HealthExtras(
    private val tx: TransactionRunner,
    private val expiries: JooqCredentialExpiries,
    private val states: CredentialStateStore,
    private val connections: ConnectionStore,
) {
    fun of(connectionRef: String): HealthExtra =
        tx.inTransaction {
            HealthExtra(
                expiries.of(connectionRef)?.credentialExpiresAt,
                states.find(connectionRef)?.last?.overlapEnd,
            )
        }

    fun record(connectionRef: String): DomainRecord =
        checkNotNull(tx.inTransaction { connections.find(connectionRef) }) { "connection $connectionRef vanished" }
}
