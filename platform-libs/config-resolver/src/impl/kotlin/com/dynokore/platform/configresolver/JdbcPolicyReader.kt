package com.dynokore.platform.configresolver

import org.jooq.DSLContext
import java.time.LocalDate

/**
 * [PolicyReader] over the setting tables, through jOOQ's DSL on the DSLContext of tenancy-jooq's factory, in the caller's
 * open transaction (the tenant is bound by the transaction, ARC-21). The DSLContext runs on the TransactionRunner whose
 * TenantAwareDataSource holds the lane gate (INV-Q-4502). All precedence logic is [PrecedenceResolver]'s.
 *
 * @param form the deployment form of this installation, for a row whose default depends on it (VP-74).
 */
class JdbcPolicyReader(
    dsl: DSLContext,
    catalogue: VariationPointCatalogue,
    form: DeploymentForm,
    schema: String,
) : PolicyReader {
    private val store = JdbcSettingStore(dsl, schema)
    private val resolver =
        PrecedenceResolver(catalogue, form) { key, ref, tenant, source, at ->
            store.find(key, ref, tenant, source, at)
        }

    override fun resolve(
        key: VariationPointKey,
        scope: PolicyScope,
        at: LocalDate,
    ): ResolvedSetting = resolver.resolve(key, scope, at)

    override fun capToBounds(
        key: VariationPointKey,
        scope: PolicyScope,
        level: ScopeLevel,
        selected: List<SelectedOption>,
        at: LocalDate,
    ): ResolvedSetting = resolver.capToBounds(key, scope, level, selected, at)
}
