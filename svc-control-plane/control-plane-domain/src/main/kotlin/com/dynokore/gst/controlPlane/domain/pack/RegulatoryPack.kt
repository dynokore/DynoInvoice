package com.dynokore.gst.controlPlane.domain.pack

import java.time.Instant

/**
 * One regulatory pack version through the draft, signed, published path (INV-REQ-2590, INV-NOTE-7 §7).
 *
 * The authors of a version are every identity that called authorRegulatoryPack on it ([authors]); the signer is a
 * different identity. The separation is non-waivable (INV-REQ-2590): no parameter, flag or role lifts it, and it is
 * decided here, in the aggregate, not by the caller. A published version is never changed or withdrawn; a correction is
 * a new version that supersedes it.
 */
@Suppress("LongParameterList") // the aggregate state is one record; copy() names the changed fields
class RegulatoryPack private constructor(
    val ref: PackRef,
    val type: PackType,
    val version: PackVersion,
    val citations: List<String>,
    val authors: Set<ActorId>,
    val status: PackStatus,
    val signer: ActorId?,
    val signingKeyRef: String?,
    val minEngineVersion: MinEngineVersion?,
    val exposureReducing: Boolean,
    val signedAt: Instant?,
    val publishedAt: Instant?,
) {
    init {
        require(authors.isNotEmpty()) { "a pack version has at least one author" }
        require(authors.size <= MAX_AUTHORS) { "a pack version has at most $MAX_AUTHORS authors" }
        require((status == PackStatus.DRAFT) == (signer == null)) { "a version has a signer exactly when it is signed" }
        require(
            signer == null || signer !in authors,
        ) { "the signer of a version is never one of its authors (INV-REQ-2590)" }
        require((status == PackStatus.PUBLISHED) == (publishedAt != null)) {
            "a version is published exactly when it has a time"
        }
    }

    /** The call of [actor] to authorRegulatoryPack on a version that already exists (the operation's repeat key). */
    fun authoredBy(actor: ActorId): RegulatoryPack =
        if (status != PackStatus.DRAFT || actor in authors) {
            this
        } else {
            copy(authors = authors + actor)
        }

    /**
     * signPack by [signer]. Refused when [signer] is an author, whatever roles the signer holds and whatever the
     * request says (INV-REQ-2590; INV-AC-2591): the version stays as it is. A repeat on a signed or published version
     * is a no-op (INV-REQ-1010).
     */
    fun sign(
        signer: ActorId,
        signingKeyRef: String,
        minEngineVersion: MinEngineVersion?,
        exposureReducing: Boolean,
        at: Instant,
    ): SignOutcome =
        when {
            status != PackStatus.DRAFT -> {
                SignOutcome.AlreadySigned(this)
            }

            signer in authors -> {
                SignOutcome.SeparationOfDutyRefused(this)
            }

            else -> {
                SignOutcome.Signed(
                    copy(
                        status = PackStatus.SIGNED,
                        signer = signer,
                        signingKeyRef = signingKeyRef,
                        minEngineVersion = minEngineVersion ?: this.minEngineVersion,
                        exposureReducing = exposureReducing,
                        signedAt = at,
                    ),
                )
            }
        }

    /** The version after publication; only a signed version is published. */
    fun published(at: Instant): RegulatoryPack {
        check(status == PackStatus.SIGNED) { "only a signed version is published" }
        return copy(status = PackStatus.PUBLISHED, publishedAt = at)
    }

    private fun copy(
        authors: Set<ActorId> = this.authors,
        status: PackStatus = this.status,
        signer: ActorId? = this.signer,
        signingKeyRef: String? = this.signingKeyRef,
        minEngineVersion: MinEngineVersion? = this.minEngineVersion,
        exposureReducing: Boolean = this.exposureReducing,
        signedAt: Instant? = this.signedAt,
        publishedAt: Instant? = this.publishedAt,
    ) = RegulatoryPack(
        ref,
        type,
        version,
        citations,
        authors,
        status,
        signer,
        signingKeyRef,
        minEngineVersion,
        exposureReducing,
        signedAt,
        publishedAt,
    )

    companion object {
        const val MAX_AUTHORS = 50
        const val MAX_CITATIONS = 50
        const val MAX_CITATION_LENGTH = 300

        /** A new draft; [author] is its first author. */
        fun draft(
            type: PackType,
            version: PackVersion,
            citations: List<String>,
            author: ActorId,
        ): RegulatoryPack {
            require(citations.size <= MAX_CITATIONS) { "a draft has at most $MAX_CITATIONS citations" }
            require(citations.all { it.isNotBlank() && it.length <= MAX_CITATION_LENGTH }) {
                "a citation is 1..$MAX_CITATION_LENGTH characters"
            }
            return RegulatoryPack(
                PackRef.of(type, version),
                type,
                version,
                citations,
                setOf(author),
                PackStatus.DRAFT,
                null,
                null,
                null,
                false,
                null,
                null,
            )
        }

        /** A version read back from storage; the invariants of the constructor apply. */
        @Suppress("LongParameterList")
        fun restore(
            type: PackType,
            version: PackVersion,
            citations: List<String>,
            authors: Set<ActorId>,
            status: PackStatus,
            signer: ActorId?,
            signingKeyRef: String?,
            minEngineVersion: MinEngineVersion?,
            exposureReducing: Boolean,
            signedAt: Instant?,
            publishedAt: Instant?,
        ): RegulatoryPack =
            RegulatoryPack(
                PackRef.of(type, version),
                type,
                version,
                citations,
                authors,
                status,
                signer,
                signingKeyRef,
                minEngineVersion,
                exposureReducing,
                signedAt,
                publishedAt,
            )
    }
}

/** What signPack did to a version. Nothing changed unless [Signed]. */
sealed interface SignOutcome {
    data class Signed(
        val pack: RegulatoryPack,
    ) : SignOutcome

    /** A repeat on a version that is already signed or published: the version as it is. */
    data class AlreadySigned(
        val pack: RegulatoryPack,
    ) : SignOutcome

    /** The signer edited the version (INV-AC-2591): the version stays unsigned. */
    data class SeparationOfDutyRefused(
        val pack: RegulatoryPack,
    ) : SignOutcome
}
