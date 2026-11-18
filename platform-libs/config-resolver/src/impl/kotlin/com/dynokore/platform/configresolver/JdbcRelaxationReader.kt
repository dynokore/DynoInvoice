package com.dynokore.platform.configresolver

import org.jooq.DSLContext
import java.time.LocalDate

/**
 * [RelaxationReader] over [JdbcPolicyReader] (the value in force) and [JdbcSettingWrites] (the stored relaxation
 * reference and its acknowledgements), in the caller's open tenant transaction. Reads only; holds no state.
 */
class JdbcRelaxationReader(
    dsl: DSLContext,
    catalogue: VariationPointCatalogue,
    form: DeploymentForm,
    schema: String,
) : RelaxationReader {
    private val policy = JdbcPolicyReader(dsl, catalogue, form, schema)
    private val writes = JdbcSettingWrites(dsl, schema)

    override fun acknowledgementsInForce(
        key: VariationPointKey,
        scope: PolicyScope,
        at: LocalDate,
    ): List<RelaxationAcknowledgement> {
        val tenant = requireNotNull(scope.tenant) { "a relaxation is read for a tenant scope" }
        val settingId =
            when (val source = policy.resolve(key, scope, at).source) {
                is PolicySource.Override -> source.settingId
                is PolicySource.Profile -> source.settingId
                PolicySource.CatalogueDefault -> return emptyList()
            }
        val relaxation = writes.relaxationOf(tenant, settingId) ?: return emptyList()
        return writes.acknowledgements(tenant, relaxation).map { it.acknowledgement }
    }
}
