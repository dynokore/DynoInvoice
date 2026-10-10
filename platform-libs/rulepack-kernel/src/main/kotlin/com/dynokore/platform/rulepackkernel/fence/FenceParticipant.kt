package com.dynokore.platform.rulepackkernel.fence

import com.dynokore.platform.rulepackkernel.ContentValidationOutcome
import com.dynokore.platform.rulepackkernel.PackArtefact
import com.dynokore.platform.rulepackkernel.PackContentValidator
import com.dynokore.platform.rulepackkernel.PackId
import com.dynokore.platform.rulepackkernel.PackManifest
import com.dynokore.platform.rulepackkernel.PackRegistry
import com.dynokore.platform.rulepackkernel.PackVerificationOutcome
import com.dynokore.platform.rulepackkernel.PackVerifier
import com.dynokore.platform.rulepackkernel.PackVersion
import com.dynokore.platform.rulepackkernel.PackVersionLabel
import com.dynokore.platform.rulepackkernel.TrustAnchors
import com.dynokore.platform.tenancykernel.TenantId
import java.util.concurrent.ConcurrentHashMap

/**
 * A pack-holding instance's side of the two-phase activation fence (INV-REQ-1166, FR-K.7).
 *
 * Phase one, [onStaged]: verify the signature again, refuse executable content, compile,
 * hold, and acknowledge with [PackReadinessAcknowledgement], all without using the pack.
 * A failure at any step holds nothing and acknowledges `Failed`. Phase two is the
 * coordinator's, taken only after every instance acknowledged: [onArmed] makes the held
 * pack usable (registers it for resolution by business date); [onAborted] discards it.
 * Until armed, [armed] returns nothing, so the held pack is never used.
 *
 * Redelivery of the same staged pack (at-least-once) re-acknowledges and changes nothing.
 * Each step completes its effect or leaves none (INV-REQ-1009 behaviour #3): if the
 * acknowledgement cannot be handed over, the hold is withdrawn and the failure propagates,
 * so the coordinator sees no acknowledgement and aborts.
 */
class FenceParticipant<C : Any>(
    val instance: String,
    private val verifier: PackVerifier,
    private val contentValidator: PackContentValidator,
    private val compiler: PackCompiler<C>,
    private val trustAnchors: TrustAnchors,
    private val acknowledger: ReadinessAcknowledger,
    private val registry: PackRegistry,
) {
    private data class Key(val packId: PackId, val version: PackVersionLabel)

    private class Held<C>(val manifest: PackManifest, val tenant: TenantId?, val compiled: C)

    private val held = ConcurrentHashMap<Key, Held<C>>()
    private val armedPacks = ConcurrentHashMap<Key, Held<C>>()

    init {
        require(instance.isNotBlank()) { "instance must not be blank" }
    }

    fun onStaged(
        artefact: PackArtefact,
        tenant: TenantId? = null,
    ): PackReadinessAcknowledgement {
        val outcome = load(artefact, tenant)
        val manifest = (outcome as? Loading.Held)?.manifest
        val ack =
            PackReadinessAcknowledgement(
                packId = (outcome as? Loading.Held)?.manifest?.packId ?: PackId(UNKNOWN_PACK),
                version = manifest?.version ?: PackVersionLabel(UNKNOWN_PACK),
                instance = instance,
                outcome = if (outcome is Loading.Held) ReadinessOutcome.Loaded else (outcome as Loading.Refused).failure,
            )
        val acknowledged = (outcome as? Loading.Refused)?.let { ack.withIdentity(it.identity) } ?: ack
        try {
            acknowledger.acknowledge(acknowledged)
        } catch (
            @Suppress("TooGenericExceptionCaught") e: RuntimeException,
        ) {
            if (outcome is Loading.Held && outcome.newlyHeld) held.remove(Key(outcome.manifest.packId, outcome.manifest.version))
            throw e
        }
        return acknowledged
    }

    private fun PackReadinessAcknowledgement.withIdentity(identity: Pair<PackId, PackVersionLabel>?) =
        if (identity == null) this else copy(packId = identity.first, version = identity.second)

    private sealed interface Loading {
        data class Held(val manifest: PackManifest, val newlyHeld: Boolean) : Loading

        data class Refused(
            val failure: ReadinessOutcome.Failed,
            val identity: Pair<PackId, PackVersionLabel>?,
        ) : Loading
    }

    private fun load(
        artefact: PackArtefact,
        tenant: TenantId?,
    ): Loading {
        val verified = verifier.verify(artefact, trustAnchors)
        if (verified !is PackVerificationOutcome.Verified) {
            val rejected = verified as PackVerificationOutcome.Rejected
            return refused(ReadinessFailure.VERIFICATION_FAILED, rejected.reason.name, null)
        }
        val manifest = verified.manifest
        val identity = manifest.packId to manifest.version
        val content = contentValidator.validate(artefact.contents)
        if (content is ContentValidationOutcome.Rejected) {
            return refused(ReadinessFailure.CONTENT_REFUSED, content.reason, identity)
        }
        return hold(manifest, tenant, artefact, identity)
    }

    private fun hold(
        manifest: PackManifest,
        tenant: TenantId?,
        artefact: PackArtefact,
        identity: Pair<PackId, PackVersionLabel>,
    ): Loading {
        val key = Key(manifest.packId, manifest.version)
        val existing = held[key]
        if (existing != null) {
            return if (existing.manifest == manifest && existing.tenant == tenant) {
                Loading.Held(manifest, newlyHeld = false)
            } else {
                refused(ReadinessFailure.CONFLICTING_PACK, "a different ${key.packId} ${key.version} is already held", identity)
            }
        }
        val compiled =
            try {
                compiler.compile(manifest, artefact.contents)
            } catch (
                @Suppress("TooGenericExceptionCaught") e: RuntimeException,
            ) {
                CompileOutcome.Failed("compiler threw ${e::class.simpleName}")
            }
        return when (compiled) {
            is CompileOutcome.Failed -> refused(ReadinessFailure.COMPILE_FAILED, compiled.reason, identity)
            is CompileOutcome.Compiled -> {
                held[key] = Held(manifest, tenant, compiled.compiled)
                Loading.Held(manifest, newlyHeld = true)
            }
        }
    }

    private fun refused(
        reason: ReadinessFailure,
        detail: String,
        identity: Pair<PackId, PackVersionLabel>?,
    ) = Loading.Refused(ReadinessOutcome.Failed(reason, detail), identity)

    /** Phase two: the coordinator armed the pack. Makes the held pack usable; false if this instance holds nothing for it. */
    fun onArmed(
        packId: PackId,
        version: PackVersionLabel,
    ): Boolean {
        val key = Key(packId, version)
        val pack = held[key] ?: return false
        registry.register(PackVersion(pack.manifest, pack.tenant))
        armedPacks[key] = pack
        return true
    }

    /** The activation was abandoned: discard what was held. Nothing was ever used. */
    fun onAborted(
        packId: PackId,
        version: PackVersionLabel,
    ) {
        val key = Key(packId, version)
        if (key !in armedPacks) held.remove(key)
    }

    /** The compiled pack, only once armed; a held but unarmed pack is not available for use. */
    fun armed(
        packId: PackId,
        version: PackVersionLabel,
    ): C? = armedPacks[Key(packId, version)]?.compiled

    /** True while a pack is held, armed or not. */
    fun isHolding(
        packId: PackId,
        version: PackVersionLabel,
    ): Boolean = Key(packId, version) in held

    private companion object {
        const val UNKNOWN_PACK = "unidentified"
    }
}
