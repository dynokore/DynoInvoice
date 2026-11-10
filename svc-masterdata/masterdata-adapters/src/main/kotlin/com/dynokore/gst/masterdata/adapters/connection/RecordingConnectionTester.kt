package com.dynokore.gst.masterdata.adapters.connection

import com.dynokore.gst.masterdata.adapters.persistence.JooqCallRecords
import com.dynokore.gst.masterdata.application.connection.ConnectionTester
import com.dynokore.gst.masterdata.application.connection.CredentialGate
import com.dynokore.gst.masterdata.application.connection.EvaluateConnectionStatus
import com.dynokore.gst.masterdata.domain.connection.CallFailure
import com.dynokore.gst.masterdata.domain.connection.CallRecord
import com.dynokore.gst.masterdata.domain.connection.ConnectionRecord
import com.dynokore.gst.masterdata.domain.connection.ConnectionTestResult
import com.dynokore.gst.masterdata.domain.connection.TestClass
import com.dynokore.platform.tenancykernel.TransactionRunner
import java.time.Clock
import java.time.Duration

/**
 * The adapter recording path (INV-REQ-836, INV-REQ-837). Every call of a connection goes through here: the call takes
 * the credential reference that is current when it starts ([CredentialGate.begin], so a rotation never fails a call),
 * is timed, is recorded as a [CallRecord] (the class and the code of the answer, never raw error text), and then
 * [EvaluateConnectionStatus.handle] decides whether the state changed. This class is the only caller of that use case;
 * the health read never publishes.
 */
class RecordingConnectionTester(
    private val delegate: ConnectionTester,
    private val gate: CredentialGate,
    private val calls: JooqCallRecords,
    private val evaluate: EvaluateConnectionStatus,
    private val tx: TransactionRunner,
    private val clock: Clock,
) : ConnectionTester {
    override fun test(record: ConnectionRecord): ConnectionTestResult {
        val credentialRef = gate.begin(record.connectionRef) ?: record.credentialRef
        val started = System.nanoTime()
        val result = delegate.test(record.copy(credentialRef = credentialRef))
        val latency = Duration.ofNanos(System.nanoTime() - started)
        val failure =
            if (result.resultClass == TestClass.SUCCESS) null else CallFailure(result.resultClass, result.detail)
        tx.inTransaction {
            calls.record(record.connectionRef, CallRecord(clock.instant(), latency, failure))
            evaluate.handle(record.connectionRef)
        }
        return result
    }
}
