package com.dynokore.platform.serviceruntime.testing

import com.dynokore.platform.tenancyjooq.TenancyProbe
import org.jooq.DSLContext
import org.jooq.Field
import org.jooq.impl.DSL
import org.jooq.impl.SQLDataType
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
import org.testcontainers.utility.MountableFile
import java.nio.file.Files
import java.util.concurrent.CompletableFuture
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * A Testcontainers PostgreSQL that runs [initSql] once, as the container's superuser, before the first connection of any
 * test (coordinator ruling INV-Q-5245, route R1): roles, their passwords, schemas and test tables are provisioning, so
 * a test body issues no DDL. The script goes to a temporary file that is copied into the image's init directory and
 * deleted after the start.
 */
fun startPostgres(
    image: String,
    initSql: String = "",
): PostgreSQLContainer<*> {
    val container = PostgreSQLContainer(DockerImageName.parse(image))
    val script = Files.createTempFile("pg-init-", ".sql")
    try {
        Files.writeString(script, initSql)
        container.withCopyFileToContainer(MountableFile.forHostPath(script, 0b110_100_100), "/docker-entrypoint-initdb.d/10-init.sql")
        container.start()
    } finally {
        Files.deleteIfExists(script)
    }
    return container
}

/** `create role` as init SQL for a login with [password] (a generated test password, not a deployment secret). */
fun createLoginSql(
    role: String,
    password: String,
): String = "create role $role login password '$password';\n"

/**
 * A role changes its own password through its own login (route R5): the new password is bound with `set_config` and
 * never written into SQL text; the DDL reads it back with `current_setting`.
 */
fun TenancyProbe.changeOwnPassword(next: String) =
    unbound {
        fetchValue("select set_config('probe.next_password', ?, true)", next)
        fetchValue(
            "do \$\$ begin execute format('alter role %I password %L', current_user, " +
                "current_setting('probe.next_password')); end \$\$",
        )
    }

/**
 * The login of the session (`session_authorization`, which `current_user` equals until a `set role`) and its backend
 * pid, as jOOQ function calls on quoted names, for the sessions a test reads about itself.
 */
val CURRENT_USER: Field<String> =
    DSL.function(DSL.name("current_setting"), SQLDataType.VARCHAR, DSL.inline("session_authorization"))
val BACKEND_PID: Field<Int> = DSL.function(DSL.name("pg_backend_pid"), SQLDataType.INTEGER)

/** A session setting by name, `current_setting(name)`. */
fun setting(name: String): Field<String> =
    DSL.function(DSL.name("current_setting"), SQLDataType.VARCHAR, DSL.inline(name))

/**
 * A transaction a test holds open on a helper thread (route R6, in place of a connection the test held): [run] executes
 * a block on that thread inside the same transaction, [close] commits it and frees the connection.
 */
class HeldTransaction
    @JvmOverloads
    constructor(
        private val access: TestDatabase.Access,
        /** Wraps the helper thread's whole transaction (a pool lane is a property of the acquiring thread). */
        private val around: (() -> Unit) -> Unit = { it() },
    ) : AutoCloseable {
        private val commands = LinkedBlockingQueue<(DSLContext) -> Unit>()
        private val stop: (DSLContext) -> Unit = {}
        private val started = CompletableFuture<Unit>()
        private val finished: CompletableFuture<Unit> =
            CompletableFuture.supplyAsync {
                around {
                    access.asPlatform {
                        started.complete(Unit)
                        while (true) {
                            val command = commands.take()
                            if (command === stop) break
                            command(this)
                        }
                    }
                }
            }

        init {
            // the connection is taken (or refused) before the constructor returns
            CompletableFuture.anyOf(started, finished).get(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            if (finished.isDone) finished.get()
        }

        /** Runs [block] on the held transaction and returns its result. */
        fun <T> run(block: DSLContext.() -> T): T {
            val result = CompletableFuture<T>()
            commands.put { dsl -> runCatching { dsl.block() }.fold(result::complete, result::completeExceptionally) }
            return result.get(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        }

        /** `current_user` as the held session sees it. */
        fun user(): String = run { select(CURRENT_USER).fetchSingle().value1() }

        /** The held session's backend pid. */
        fun pid(): Int = run { select(BACKEND_PID).fetchSingle().value1() }

        override fun close() {
            commands.put(stop)
            finished.get(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        }

        private companion object {
            const val TIMEOUT_SECONDS = 30L
        }
    }
