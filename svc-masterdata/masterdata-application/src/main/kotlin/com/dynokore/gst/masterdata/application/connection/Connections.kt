package com.dynokore.gst.masterdata.application.connection

import com.dynokore.gst.masterdata.domain.connection.ConnectionRecord
import com.dynokore.gst.masterdata.domain.connection.ConnectionTestResult
import com.dynokore.gst.masterdata.domain.connection.TestClass
import com.dynokore.gst.masterdata.domain.identity.PersonRef
import com.dynokore.platform.tenancykernel.TransactionRunner

/** The connection records of the bound tenant (TPL-11); every call runs inside the caller's transaction. */
interface ConnectionStore {
    fun find(connectionRef: String): ConnectionRecord?

    fun findByServiceAndTag(
        externalService: String,
        environmentTag: String,
    ): ConnectionRecord?

    fun save(record: ConnectionRecord)

    /** The next connection reference; assigned by the store, never by the caller. */
    fun nextRef(): String
}

/** Runs the test against the endpoint with the referenced credential through adapter-runtime (classified result). */
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
                val existing = store.findByServiceAndTag(command.externalService, command.environmentTag)
                if (existing != null) {
                    CreateConnectionOutcome.Stored(existing, created = false)
                } else {
                    val record =
                        ConnectionRecord(
                            connectionRef = store.nextRef(),
                            externalService = command.externalService,
                            endpoint = command.endpoint,
                            credentialRef = command.credentialRef,
                            environmentTag = command.environmentTag,
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
    /** The environment tag of the environment that asks for the test (a workspace or PRODUCTION). */
    val callerEnvironment: String,
)

sealed interface TestConnectionOutcome {
    data class Tested(
        val result: ConnectionTestResult,
    ) : TestConnectionOutcome

    data object Unknown : TestConnectionOutcome

    data object Forbidden : TestConnectionOutcome

    /** INV-REQ-1126: a workspace asked to use a PRODUCTION credential; refused before any call. */
    data object ProductionCredentialRefused : TestConnectionOutcome
}

/**
 * INV-REQ-835: runs the test through the adapter-runtime port and returns the classified result. Nothing is stored.
 * The port's failure is classified as UNREACHABLE with no detail; no raw error text is kept.
 */
class TestConnection(
    private val tx: TransactionRunner,
    private val store: ConnectionStore,
    private val tester: ConnectionTester,
    private val permission: ConnectionTestPermission,
) {
    fun handle(command: TestConnectionCommand): TestConnectionOutcome {
        var stop: TestConnectionOutcome? = null
        val record =
            tx.inTransaction {
                if (!permission.allows(command.caller)) {
                    stop = TestConnectionOutcome.Forbidden
                    null
                } else {
                    store.find(command.connectionRef).also { if (it == null) stop = TestConnectionOutcome.Unknown }
                }
            }
        return when {
            record == null -> {
                stop ?: TestConnectionOutcome.Unknown
            }

            record.environmentTag == ConnectionRecord.PRODUCTION &&
                command.callerEnvironment != ConnectionRecord.PRODUCTION -> {
                TestConnectionOutcome.ProductionCredentialRefused
            }

            else -> {
                TestConnectionOutcome.Tested(classify(record))
            }
        }
    }

    private fun classify(record: ConnectionRecord): ConnectionTestResult =
        try {
            tester.test(record)
        } catch (_: Exception) {
            ConnectionTestResult(TestClass.UNREACHABLE)
        }
}
