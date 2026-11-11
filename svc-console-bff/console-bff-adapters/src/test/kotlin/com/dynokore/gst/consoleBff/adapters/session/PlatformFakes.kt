package com.dynokore.gst.consoleBff.adapters.session

import com.dynokore.platform.tenancykernel.PlatformTenantBinding
import com.dynokore.platform.tenancykernel.TransactionRunner

/** Records the actors of the platform bindings and the transactions opened; runs the block in place (no database tenancy). */
class PlatformFakes {
    val actors = mutableListOf<String>()
    var transactions = 0
        private set
    private var inBinding = false

    val binding =
        object : PlatformTenantBinding {
            override fun <T> bindPlatform(
                actor: String,
                block: () -> T,
            ): T {
                actors += actor
                val prior = inBinding
                inBinding = true
                try {
                    return block()
                } finally {
                    inBinding = prior
                }
            }
        }

    val runner =
        object : TransactionRunner {
            override fun <T> inTransaction(block: () -> T): T {
                check(inBinding) { "a transaction opened outside the platform binding" }
                transactions++
                return block()
            }
        }

    fun store(dsl: org.jooq.DSLContext) = JooqSessionStore(dsl, binding, runner)
}
