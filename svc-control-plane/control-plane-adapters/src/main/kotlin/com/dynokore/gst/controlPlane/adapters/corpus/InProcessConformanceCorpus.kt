package com.dynokore.gst.controlPlane.adapters.corpus

import com.dynokore.gst.controlPlane.application.release.ConformanceCorpus
import com.dynokore.gst.controlPlane.domain.pack.RegulatoryPack
import com.dynokore.gst.controlPlane.domain.release.GoldenDocument
import com.dynokore.platform.rulesevaluation.evaluate

/**
 * The rule conformance corpus run in-process (INV-REQ-2660, INV-AC-9790, owner answer INV-Q-8719 A): the golden
 * documents of the corpus resource tree, and [classify] by the pure `evaluate` of rules-evaluation in the same JVM,
 * with no HTTP call. The candidate pack's `contents` replaces the baseline of its own type; the other two types come
 * from the baselines of the tree, the tenant mapping from `mapping-fixture.json` and the entity profile from
 * `profile-fixture.json`.
 *
 * Fail closed (NIST SP 800-53 SI-7, SI-10): [CorpusLoader] makes [documents] throw for a tree that is not exactly as
 * its manifest says; nothing is defaulted. An evaluation refusal passes through [classify] uncaught, so the pack is
 * not signed.
 */
class InProcessConformanceCorpus(
    private val source: CorpusSource,
) : ConformanceCorpus {
    private val loaded: LoadedCorpus by lazy { CorpusLoader(source).load() }

    override fun documents(): List<GoldenDocument> = loaded.documents.map { it.golden }

    override fun classify(
        pack: RegulatoryPack,
        document: GoldenDocument,
    ): String? {
        val corpus = loaded
        val golden =
            requireNotNull(corpus.documents.firstOrNull { it.golden.id == document.id }) {
                "document ${document.id.value} is not a golden document of the corpus"
            }
        val type = pack.type.value
        val packs =
            if (type in CorpusLoader.BASELINE_FILES.keys) {
                val contents = checkNotNull(pack.contents) { "a $type pack with no contents is refused" }
                corpus.baselines + (type to contents.json)
            } else {
                corpus.baselines
            }
        val result = evaluate(golden.request, packs, corpus.mapping, corpus.profile)
        return when (golden.field) {
            CorpusLoader.CATEGORY_CODE -> result.categoryCode
            CorpusLoader.SCOPE_VERDICT -> result.scopeVerdict
            else -> result.documentTypeCode
        }
    }

    companion object {
        /** The five fixture files of the tree; every other manifest entry is a golden document. */
        val FIXTURES: Set<String> get() = CorpusLoader.FIXTURES
    }
}
