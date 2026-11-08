package com.dynokore.gst.masterdata.application.connection

import com.dynokore.gst.masterdata.domain.connection.ConnectionRecord
import com.dynokore.gst.masterdata.domain.connection.ConnectionTestResult
import com.dynokore.gst.masterdata.domain.connection.EnvironmentTag
import com.dynokore.gst.masterdata.domain.identity.PersonRef
import com.dynokore.platform.tenancykernel.TransactionRunner

/** The connection records of the bound tenant (TPL-11); every call runs inside the caller's transaction. */
interface ConnectionStore {
    fun find(connectionRef: String): ConnectionRecord?

    fun findByServiceAndTag(
        externalService: String,
        environmentTag: EnvironmentTag,
    ): ConnectionRecord?

    fun save(record: ConnectionRecord)

    /** The next connection reference; assigned by the store, never by the caller. */
    fun nextRef(): String
}

/**
 * Runs the test against the endpoint with the referenced credential through adapter-runtime.
 *
 * Contract: the port returns a classified [ConnectionTestResult] for EVERY outcome (success, unreachable,
 * authentication refused, unexpected response); a network, DNS or authentication failure is a result, never an
 * exception. An exception from this port is a defect and propagates unchanged.
 */
fun interface ConnectionTester {
    fun test(record: ConnectionRecord): ConnectionTestResult
}

/** The scoped permission `connection.configure`, decided per request (INV-Q-10027). No role name crosses this port. */
fun interface ConnectionConfigurePermission {
    fun allows(caller: PersonRef): Boolean
}

/** The scoped permission `connection.view`. */
fun interface ConnectionViewPermission {
    fun allows(caller: PersonRef): Boolean
}

/** The scoped permission `connection.test`. */
fun interface ConnectionTestPermission {
    fun allows(caller: PersonRef): Boolean
}

data class CreateConnectionCommand(
    val caller: PersonRef,
    val externalService: String,
    val endpoint: String,
    val credentialRef: String,
    val environmentTag: String,
    val ownerService: String,
)

sealed interface CreateConnectionOutcome {
    /** [created] is false when (externalService, environmentTag) already had a record, which is returned unchanged. */
    data class Stored(
        val record: ConnectionRecord,
        val created: Boolean,
    ) : CreateConnectionOutcome

    data object Forbidden : CreateConnectionOutcome

    /** The environment tag text is not exactly PRODUCTION or SANDBOX; nothing is stored. */
    data object UnknownEnvironmentTag : CreateConnectionOutcome
}

/** INV-REQ-834: creates the record; a repeat of (externalService, environmentTag) returns the existing record. */
class CreateConnection(
    private val tx: TransactionRunner,
    private val store: ConnectionStore,
    private val permission: ConnectionConfigurePermission,
) {
    fun handle(command: CreateConnectionCommand): CreateConnectionOutcome =
        tx.inTransaction {
            if (!permission.allows(command.caller)) {
                CreateConnectionOutcome.Forbidden
            } else {
                val tag = EnvironmentTag.parse(command.environmentTag)
                val existing = tag?.let { store.findByServiceAndTag(command.externalService, it) }
                if (tag == null) {
                    CreateConnectionOutcome.UnknownEnvironmentTag
                } else if (existing != null) {
                    CreateConnectionOutcome.Stored(existing, created = false)
                } else {
                    val record =
                        ConnectionRecord(
                            connectionRef = store.nextRef(),
                            externalService = command.externalService,
                            endpoint = command.endpoint,
                            credentialRef = command.credentialRef,
                            environmentTag = tag,
                            ownerService = command.ownerService,
                        )
                    store.save(record)
                    CreateConnectionOutcome.Stored(record, created = true)
                }
            }
        }
}

sealed interface GetConnectionOutcome {
    data class Found(
        val record: ConnectionRecord,
    ) : GetConnectionOutcome

    data object Unknown : GetConnectionOutcome

    data object Forbidden : GetConnectionOutcome
}

/** Reads one record; the permission is asked first, so a caller without it learns nothing of the record. */
class GetConnection(
    private val tx: TransactionRunner,
    private val store: ConnectionStore,
    private val permission: ConnectionViewPermission,
) {
    fun handle(
        caller: PersonRef,
        connectionRef: String,
    ): GetConnectionOutcome =
        tx.inTransaction {
            if (!permission.allows(caller)) {
                GetConnectionOutcome.Forbidden
            } else {
                store.find(connectionRef)?.let { GetConnectionOutcome.Found(it) } ?: GetConnectionOutcome.Unknown
            }
        }
}

data class TestConnectionCommand(
    val caller: PersonRef,
    val connectionRef: String,
    /** The text of the environment that asks for the test; parsed to [EnvironmentTag] after the permission check. */
    val callerEnvironment: String,
)

sealed interface TestConnectionOutcome {
    data class Tested(
        val result: ConnectionTestResult,
    ) : TestConnectionOutcome

    data object Unknown : TestConnectionOutcome

    data object Forbidden : TestConnectionOutcome

    /** The caller environment text is not exactly PRODUCTION or SANDBOX; the port is not called. */
    data object UnknownEnvironment : TestConnectionOutcome

    /** INV-REQ-1126: the record's tag differs from the caller's environment; refused before any call. */
    data object EnvironmentMismatchRefused : TestConnectionOutcome
}

/**
 * INV-REQ-835: runs the test through the adapter-runtime port and returns its classified result unchanged. Nothing is
 * stored. An exception thrown by the port is a defect: it propagates out of [handle] and is never mapped to a result.
 */
class TestConnection(
    private val tx: TransactionRunner,
    private val store: ConnectionStore,
    private val tester: ConnectionTester,
    private val permission: ConnectionTestPermission,
) {
    fun handle(command: TestConnectionCommand): TestConnectionOutcome {
        val checked =
            tx.inTransaction {
                if (!permission.allows(command.caller)) {
                    TestConnectionOutcome.Forbidden to null
                } else {
                    val callerEnvironment = EnvironmentTag.parse(command.callerEnvironment)
                    val record = store.find(command.connectionRef)
                    when {
                        callerEnvironment == null -> {
                            TestConnectionOutcome.UnknownEnvironment to null
                        }

                        record == null -> {
                            TestConnectionOutcome.Unknown to null
                        }

                        record.environmentTag != callerEnvironment -> {
                            TestConnectionOutcome.EnvironmentMismatchRefused to
                                null
                        }

                        else -> {
                            null to record
                        }
                    }
                }
            }
        val record = checked.second ?: return checked.first ?: TestConnectionOutcome.Unknown
        return TestConnectionOutcome.Tested(tester.test(record))
    }
}
