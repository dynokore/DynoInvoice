package com.dynokore.gst.masterdata.bootstrap

import com.dynokore.gst.masterdata.application.request.SendMethodPolicy
import com.dynokore.platform.configresolver.PolicyReader
import com.dynokore.platform.configresolver.PolicyScope
import com.dynokore.platform.configresolver.VariationPointKey
import com.dynokore.platform.tenancykernel.TransactionRunner
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * INV-T-18221 (INV-REQ-1291): VP-30 is registered in the resolver and the `PolicySendMethod` policy class reads it
 * through `PolicyReader.resolve` at tenant scope. The writer refuses VP-30 (POL-006, still planned), so overrides and
 * unknown options are covered in `PolicySendMethodReaderTest` (adapters).
 */
class PolicySendMethodTest {
    private fun run(block: (RegistryEnv) -> Unit) = runRegistry { env -> block(env) }

    private fun RegistryEnv.option(): String =
        ce.inTenant {
            context.getBean(TransactionRunner::class.java).inTransaction {
                context.getBean(SendMethodPolicy::class.java).option()
            }
        }

    @Test
    fun `INV-AC-18220 VP-30 is registered and resolves to the signed link default for a tenant`() =
        run { env ->
            val resolved =
                env.ce.inTenant {
                    env.context.getBean(TransactionRunner::class.java).inTransaction {
                        env.context
                            .getBean(PolicyReader::class.java)
                            .resolve(VariationPointKey("VP-30"), PolicyScope.tenant(env.ce.tenant), env.businessDate())
                    }
                }
            assertThat(resolved.selected.single().key).isEqualTo("SIGNED_LINK")
            assertThat(env.option()).isEqualTo("SIGNED_LINK")
        }
}
