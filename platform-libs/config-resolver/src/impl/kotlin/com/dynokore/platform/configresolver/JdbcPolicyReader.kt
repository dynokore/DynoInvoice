package com.dynokore.platform.configresolver

import com.dynokore.platform.tenancykernel.CurrentConnection
import java.time.LocalDate

/**
 * [PolicyReader] over the setting tables, on the connection of the caller's open transaction (the tenant
 * is bound by the transaction, ARC-21). All precedence logic is [PrecedenceResolver]'s.
 *
 * @param form the deployment form of this installation, for a row whose default depends on it (VP-74).
 */
class JdbcPolicyReader(
    private val connections: CurrentConnection,
    catalogue: VariationPointCatalogue,
    form: DeploymentForm,
    schema: String,
) : PolicyReader {
    private val store = JdbcSettingStore(schema)
    private val resolver =
        PrecedenceResolver(catalogue, form) { key, ref, tenant, source, at ->
            store.find(connections.require(), key, ref, tenant, source, at)
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
