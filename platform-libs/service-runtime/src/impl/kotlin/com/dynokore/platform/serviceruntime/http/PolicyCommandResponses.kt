package com.dynokore.platform.serviceruntime.http

import com.dynokore.platform.configresolver.PolicyChange
import com.dynokore.platform.configresolver.PolicySource
import com.dynokore.platform.configresolver.RelaxationStatus
import com.dynokore.platform.configresolver.ScopeLevel
import com.dynokore.platform.configresolver.SelectedOption
import com.dynokore.platform.configresolver.SettingVersion
import tools.jackson.databind.ObjectMapper
import tools.jackson.databind.node.ObjectNode

/** The stored and sent answers of the policy commands. */
internal object PolicyCommandResponses {
    private const val HTTP_OK = 200
    private val mapper = ObjectMapper()

    fun settingResponse(change: PolicyChange): StoredResponse {
        val version = change.version
        val node = mapper.createObjectNode()
        node.put("variationPoint", version.key.value)
        target(node, version)
        node.set("selected", selected(version.selected))
        node.put("source", version.source.name)
        version.profileId?.let { node.put("profileRef", it) }
        version.profileVersion?.let { node.put("profileVersion", it.toLong()) }
        version.relaxation?.let { node.put("relaxationId", it.toString()) }
        node.put("effectiveDate", version.effectiveFrom.toString())
        node.put("setBy", version.setBy)
        node.put("reason", version.reason)
        node.put("recordedAt", version.recordedAt.toString())
        node.put("settingId", version.id.toString())
        // the contract's prior carries a value an override or a profile set; the catalogue default is no setting
        val prior = change.prior
        val priorSource = prior?.source
        if (prior == null || priorSource is PolicySource.CatalogueDefault) {
            node.putNull("prior")
        } else {
            val priorNode = node.putObject("prior")
            priorNode.set("selected", selected(prior.selected))
            priorNode.put("effectiveDate", prior.at.toString())
            priorNode.put("source", if (priorSource is PolicySource.Profile) "PROFILE" else "OVERRIDE")
        }
        node.put("auditEventId", change.auditEventId)
        change.policyChangedEventId?.let { node.put("policyChangedEventId", it) }
            ?: node.putNull("policyChangedEventId")
        return StoredResponse(HTTP_OK, mapper.writeValueAsString(node))
    }

    fun relaxationResponse(status: RelaxationStatus): StoredResponse {
        val node = mapper.createObjectNode()
        node.put("relaxationId", status.relaxation.toString())
        node.put("variationPoint", status.key.value)
        node.put("scopeLevel", status.target.ref.level.name)
        if (status.target.ref.level != ScopeLevel.INSTALLATION) node.put("scopeRef", status.target.ref.id)
        status.target.entity?.let { node.put("entityRef", it) }
        node.set("selected", selected(status.selected))
        node.put("effectiveDate", status.effectiveFrom.toString())
        val acknowledgements = node.putArray("acknowledgements")
        status.acknowledgements.forEach {
            acknowledgements
                .addObject()
                .put("role", it.role)
                .put("principal", it.principal)
                .put("acknowledgedAt", it.acknowledgedAt.toString())
        }
        val outstanding = node.putArray("outstandingRoles")
        status.outstandingRoles.forEach { outstanding.add(it) }
        node.put("complete", status.complete)
        return StoredResponse(HTTP_OK, mapper.writeValueAsString(node))
    }

    /**
     * The stored target: the response `scopeRef` is the stored ref (the token tenant's text for TENANT, INV-Q-5233).
     */
    private fun target(
        node: ObjectNode,
        version: SettingVersion,
    ) {
        node.put("scopeLevel", version.target.ref.level.name)
        if (version.target.ref.level != ScopeLevel.INSTALLATION) node.put("scopeRef", version.target.ref.id)
        version.target.entity?.let { node.put("entityRef", it) }
    }

    private fun selected(options: List<SelectedOption>) =
        mapper.createArrayNode().also { array ->
            options.forEach {
                val item = array.addObject().put("option", it.key)
                it.valueJson?.let { json -> item.set("value", mapper.readTree(json)) }
            }
        }
}
