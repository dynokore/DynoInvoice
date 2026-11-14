package com.dynokore.gst.rules.application

import com.dynokore.gst.rules.domain.pack.EngineCompatibility
import com.dynokore.platform.rulepackkernel.ContentValidationOutcome
import com.dynokore.platform.rulepackkernel.PackArtefact
import com.dynokore.platform.rulepackkernel.PackContentValidator
import com.dynokore.platform.rulepackkernel.PackVerificationOutcome
import com.dynokore.platform.rulepackkernel.PackVerifier
import com.dynokore.platform.rulepackkernel.TrustAnchors
import com.dynokore.platform.rulepackkernel.VerifiedPack
import com.dynokore.platform.serviceruntime.binding.PackSource
import com.dynokore.platform.serviceruntime.binding.PackSourceRef
import java.io.ByteArrayInputStream
import java.security.MessageDigest
import java.util.zip.ZipInputStream

/** The catalogue codes an import can fail with (INV-REQ-1078; INV-Q-3588). */
enum class ImportFailure(
    val code: String,
) {
    /** Step 1: the bundle is malformed, its digest differs from the request, or its signature does not verify. */
    SIGNATURE("DEP-002"),

    /** Step 2: the pack needs a newer engine than the running train. */
    ENGINE("DEP-003"),

    /** Step 3: the contents do not load. */
    CONTENTS("RUL-005"),
}

/** The status of an import as `getRegulatoryPack` reads it: IMPORTING, STAGED, or FAILED with a catalogue code. */
data class ImportStatus(
    val status: String,
    val failureCode: String?,
)

/** The bundle named by the request is not in the pack source (404 SRT-005; nothing is written). */
class PackBundleNotFoundException(
    reference: String,
) : RuntimeException("no bundle for $reference")

/** The anchors a release train ships; null when there are none (503 SRT-013, fail closed). */
fun interface TrustAnchorSource {
    fun anchors(): TrustAnchors?
}

/** The scoped permission `regulatory-pack.import-verify` decided by `AuthorityReplica` (INV-Q-10027); no role name. */
fun interface ImportPermissionPort {
    fun mayImport(user: String): Boolean
}

/** The running train's version; null when the build states none (503 SRT-013). */
fun interface RunningEngine {
    fun version(): String?
}

/**
 * The state of imports, in the table `rules.pack_import`, and the one transaction that stores a verified pack. The
 * artefact is stored only through the persisted holder copy, in the same transaction as the registry metadata row and
 * the outbox row of `RulePackStaged` (INV-Q-7438 A).
 */
interface PackImportStore {
    /** Records IMPORTING for the version; replaces an earlier FAILED record of the same version. */
    fun begin(
        packType: String,
        version: String,
        bundleHash: String,
        actor: String,
    )

    /**
     * Stores the artefact, the registry row (STAGED) and the `RulePackStaged` outbox row, and marks the import STAGED,
     * in one transaction. A store that does not find the pack afterwards by (pack type, tenant, pack version) rolls the
     * whole transaction back.
     */
    fun stage(
        pack: VerifiedPack,
        actor: String,
    )

    fun fail(
        packType: String,
        version: String,
        failure: ImportFailure,
    )

    /** The status of the version: the import record first, else the registry row of a pack that was not imported. */
    fun status(
        packType: String,
        version: String,
    ): ImportStatus?
}

sealed interface ImportRegulatoryPackResult {
    data object Staged : ImportRegulatoryPackResult

    data class Failed(
        val failure: ImportFailure,
    ) : ImportRegulatoryPackResult

    data object Denied : ImportRegulatoryPackResult

    /** The bundle hash names no bundle in the pack source. */
    data object BundleNotFound : ImportRegulatoryPackResult

    /** No trust anchor, or no engine version: the import cannot decide and changes nothing. */
    data object Unavailable : ImportRegulatoryPackResult

    /** The pack version exists with another manifest (an immutable version, INV-ADR-263). */
    data object Conflict : ImportRegulatoryPackResult
}

/**
 * Import steps 1 to 3 of a signed regulatory pack bundle (INV-REQ-1078): the bytes come only through [PackSource] by
 * the bundle hash and must hash to it; the signature is verified (DEP-002), then the engine range (DEP-003), then the
 * contents are checked (RUL-005); only then is the pack stored. A refused bundle leaves no artefact, registry or
 * outbox row, only its FAILED import record.
 */
class ImportRegulatoryPack(
    private val source: PackSource,
    private val anchors: TrustAnchorSource,
    private val verifier: PackVerifier,
    private val validator: PackContentValidator,
    private val engine: RunningEngine,
    private val permission: ImportPermissionPort,
    private val store: PackImportStore,
) {
    @Suppress("ReturnCount") // one early return per refusal, in the ruled order
    fun execute(
        packType: String,
        version: String,
        bundleHash: String,
        user: String,
    ): ImportRegulatoryPackResult {
        if (!permission.mayImport(user)) return ImportRegulatoryPackResult.Denied
        val trusted = anchors.anchors() ?: return ImportRegulatoryPackResult.Unavailable
        val running = engine.version() ?: return ImportRegulatoryPackResult.Unavailable
        val digest = digestOf(bundleHash) ?: return ImportRegulatoryPackResult.BundleNotFound
        val bytes =
            try {
                source.fetch(PackSourceRef(bundleHash))
            } catch (_: PackBundleNotFoundException) {
                return ImportRegulatoryPackResult.BundleNotFound
            }
        store.begin(packType, version, bundleHash, user)
        val verified = verified(bytes, digest, trusted, packType, version)
        val failure =
            when {
                verified == null -> ImportFailure.SIGNATURE
                !EngineCompatibility.isCompatible(verified.version.manifest.minEngineVersion, running) ->
                    ImportFailure.ENGINE
                validator.validate(verified.artefact.contents) is ContentValidationOutcome.Rejected ->
                    ImportFailure.CONTENTS
                else -> null
            }
        if (failure != null) {
            store.fail(packType, version, failure)
            return ImportRegulatoryPackResult.Failed(failure)
        }
        return try {
            store.stage(checkNotNull(verified), user)
            ImportRegulatoryPackResult.Staged
        } catch (_: ConflictingPackVersionException) {
            store.fail(packType, version, ImportFailure.SIGNATURE)
            ImportRegulatoryPackResult.Conflict
        }
    }

    /** The verified pack of exactly the requested identity, or null when step 1 refuses the bundle. */
    private fun verified(
        bytes: ByteArray,
        digest: String,
        trusted: TrustAnchors,
        packType: String,
        version: String,
    ): VerifiedPack? {
        if (sha256Hex(bytes) != digest) return null
        val artefact = PackBundle.read(bytes) ?: return null
        val outcome = verifier.verify(artefact, trusted)
        if (outcome !is PackVerificationOutcome.Verified) return null
        val manifest = outcome.manifest
        // the verified identity must be the one asked for, compared exactly (no trim, no case folding)
        if (manifest.packType.value != packType || manifest.version.value != version) return null
        return VerifiedPack.of(outcome, artefact)
    }

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    companion object {
        private const val PREFIX = "sha256:"
        private val HEX = Regex("[0-9a-f]+")

        /** The digest hex of a `sha256:<lower hex>` hash, or null for any other shape (also the file name key). */
        fun digestOf(bundleHash: String): String? =
            bundleHash.takeIf { it.startsWith(PREFIX) }?.removePrefix(PREFIX)?.takeIf { HEX.matches(it) }
    }
}

/** The registry already holds the pack version with another manifest. */
class ConflictingPackVersionException(
    message: String,
) : RuntimeException(message)

/** The four-member artefact of a bundle as the pack feed ships it: a zip with exactly these file names. */
object PackBundle {
    private const val MAX_MEMBER_BYTES = 16 * 1024 * 1024
    private val NAMES = setOf("manifest.json", "checksums.json", "signature.p7s", "contents.json")

    /** The artefact, or null when the bytes are not a zip, a name is not one of the four or repeats, or one is huge. */
    fun read(bytes: ByteArray): PackArtefact? {
        val members = HashMap<String, ByteArray>()
        try {
            ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    if (entry.isDirectory || entry.name !in NAMES || entry.name in members) return null
                    val data = zip.readNBytes(MAX_MEMBER_BYTES + 1)
                    if (data.size > MAX_MEMBER_BYTES) return null
                    members[entry.name] = data
                }
            }
        } catch (_: java.io.IOException) {
            return null
        }
        // a missing member is an empty one: the verifier refuses it (an unsigned bundle has no signature.p7s)
        val empty = ByteArray(0)
        return PackArtefact(
            manifest = members["manifest.json"] ?: empty,
            checksums = members["checksums.json"] ?: empty,
            signature = members["signature.p7s"] ?: empty,
            contents = members["contents.json"] ?: empty,
        )
    }
}
