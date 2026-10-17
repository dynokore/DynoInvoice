package com.dynokore.platform.documentlifecycle

/**
 * What an embedding service instantiates: its [StateModel] and [SupersessionRules] as data, then one document per
 * logical id. Holds no persistence, clock or event type; claim and timer behaviour belong to the embedding service.
 */
class DocumentLifecycle(
    val rules: SupersessionRules,
) {
    constructor(
        model: StateModel,
        rules: List<SupersessionRule> = emptyList(),
    ) : this(SupersessionRules(model, rules))

    val model: StateModel get() = rules.model

    /** A new document holding its first version in the model's initial state. */
    fun open(id: LogicalId): VersionedDocument = VersionedDocument.create(id, rules).addVersion()
}
