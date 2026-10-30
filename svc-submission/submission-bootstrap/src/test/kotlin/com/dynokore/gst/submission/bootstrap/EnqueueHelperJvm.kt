package com.dynokore.gst.submission.bootstrap

import com.dynokore.gst.submission.application.queue.SubmissionQueue
import com.dynokore.platform.tenancykernel.TenantContext
import com.dynokore.platform.tenancykernel.TenantId
import com.dynokore.platform.tenancykernel.TransactionRunner
import java.nio.file.Path
import java.util.UUID

/**
 * The helper JVM of INV-AC-2140 and INV-AC-13067: it runs both writes of `SubmissionQueue.enqueue` inside a
 * transaction it never commits, prints [WRITTEN] and waits to be killed with SIGKILL. The enclosing `inTransaction`
 * joins `enqueue`'s own (REQUIRED propagation), so the commit would happen only when this block returns.
 * Arguments: JDBC URL, secret mount root, tenant, Doc UUID, version.
 */
object EnqueueHelperJvm {
    const val WRITTEN = "BOTH-WRITES-DONE"
    private const val ONE_HOUR_MILLIS = 3_600_000L

    @JvmStatic
    fun main(args: Array<String>) {
        val jdbcUrl = args[0]
        val mountRoot = args[1]
        val tenant = args[2]
        val docUuid = args[3]
        val version = args[4].toInt()
        SubmissionContext.online(Path.of(mountRoot), jdbcUrl).run { context ->
            val tenants = context.getBean(TenantContext::class.java)
            val runner = context.getBean(TransactionRunner::class.java)
            val queue = context.getBean(SubmissionQueue::class.java)
            tenants.bind(TenantId(UUID.fromString(tenant)), "svc:submission-svc") {
                runner.inTransaction {
                    queue.enqueue(docUuid, version)
                    println(WRITTEN)
                    System.out.flush()
                    Thread.sleep(ONE_HOUR_MILLIS)
                }
            }
        }
    }
}
