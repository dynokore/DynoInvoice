package com.dynokore.gst.controlPlane.application.release

import com.dynokore.gst.controlPlane.domain.pack.PackRef
import com.dynokore.gst.controlPlane.domain.pack.RegulatoryPack
import com.dynokore.gst.controlPlane.domain.release.CorpusSection
import com.dynokore.gst.controlPlane.domain.release.DocumentId
import com.dynokore.gst.controlPlane.domain.release.GoldenDocument
import com.dynokore.gst.controlPlane.domain.release.ReleaseRecord

/** A golden corpus with one document per section; a pack classifies each as expected unless [broken] says otherwise. */
class FakeCorpus(
    var documents: List<GoldenDocument> = standard(),
    val broken: MutableMap<PackRef, Set<String>> = HashMap(),
) : ConformanceCorpus {
    override fun documents() = documents

    override fun classify(
        pack: RegulatoryPack,
        document: GoldenDocument,
    ) = if (document.id.value in broken[pack.ref].orEmpty()) "WRONG" else document.expected

    companion object {
        fun standard() =
            CorpusSection.entries.mapIndexed { i, section ->
                GoldenDocument(DocumentId("GOLD-${i + 1}"), section, "SR")
            }
    }
}

class MemoryRecords : PackReleaseRecords {
    val store = LinkedHashMap<PackRef, ReleaseRecord>()

    override fun find(ref: PackRef) = store[ref]

    override fun save(
        ref: PackRef,
        record: ReleaseRecord,
    ) {
        store[ref] = record
    }
}
