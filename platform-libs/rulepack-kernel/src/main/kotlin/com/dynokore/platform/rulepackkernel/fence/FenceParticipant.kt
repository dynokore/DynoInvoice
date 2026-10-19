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
@Suppress("LongParameterList") // constructor injection under CS-05; INV-Q-4629 (CS-50)
class FenceParticipant<C : Any>(
    val instance: String,
    private val verifier: PackVerifier,
    private val contentValidator: PackContentValidator,
    private val compiler: PackCompiler<C>,
    private val trustAnchors: TrustAnchors,
    private val acknowledger: ReadinessAcknowledger,
    private val registry: PackRegistry,
) {
    private data class Key(
        val packId: PackId,
        val version: PackVersionLabel,
    )

    private class Held<C>(
        val manifest: PackManifest,
        val tenant: TenantId?,
        val compiled: C,
    )

    private val held = ConcurrentHashMap<Key, Held<C>>()
    private val armedPacks = ConcurrentHashMap<Key, Held<C>>()

    init {
        require(instance.isNotBlank()) { "instance must not be blank" }
    }

    fun onStaged(
        artefact: PackArtefact,
        tenant: TenantId? = null,
    ): PackReadinessAcknowledgement {
        val loading = load(artefact, tenant)
        val ack =
            when (loading) {
                is Loading.Held -> {
                    PackReadinessAcknowledgement(
                        loading.manifest.packId,
                        loading.manifest.version,
                        instance,
                        ReadinessOutcome.Loaded,
                    )
                }

                is Loading.Refused -> {
                    PackReadinessAcknowledgement(
                        loading.identity?.first ?: PackId(UNKNOWN_PACK),
                        loading.identity?.second ?: PackVersionLabel(UNKNOWN_PACK),
                        instance,
                        loading.failure,
                    )
                }
            }
        try {
            acknowledger.acknowledge(ack)
        } catch (
            @Suppress("TooGenericExceptionCaught") e: RuntimeException,
        ) {
            if (loading is Loading.Held && loading.newlyHeld) {
                held.remove(Key(loading.manifest.packId, loading.manifest.version))
            }
            throw e
        }
        return ack
    }

    private sealed interface Loading {
        data class Held(
            val manifest: PackManifest,
            val newlyHeld: Boolean,
        ) : Loading

        data class Refused(
            val failure: ReadinessOutcome.Failed,
            val identity: Pair<PackId, PackVersionLabel>?,
        ) : Loading
    }

    private fun load(
        artefact: PackArtefact,
        tenant: TenantId?,
    ): Loading =
        when (val verified = verifier.verify(artefact, trustAnchors)) {
            is PackVerificationOutcome.Rejected -> {
                refused(ReadinessFailure.VERIFICATION_FAILED, verified.reason.name, null)
            }

            is PackVerificationOutcome.Verified -> {
                val manifest = verified.manifest
                val identity = manifest.packId to manifest.version
                when (val content = contentValidator.validate(artefact.contents)) {
                    is ContentValidationOutcome.Rejected -> {
                        refused(ReadinessFailure.CONTENT_REFUSED, content.reason, identity)
                    }

                    ContentValidationOutcome.Accepted -> {
                        hold(manifest, tenant, artefact, identity)
                    }
                }
            }
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
                val detail = "a different ${key.packId} ${key.version} is already held"
                refused(ReadinessFailure.CONFLICTING_PACK, detail, identity)
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
            is CompileOutcome.Failed -> {
                refused(ReadinessFailure.COMPILE_FAILED, compiled.reason, identity)
            }

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

    /**
     * Phase two: the coordinator armed the pack. Makes the held pack usable;
     * false if this instance holds nothing for it.
     */
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
        if (!armedPacks.containsKey(key)) held.remove(key)
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
    ): Boolean = held.containsKey(Key(packId, version))

    private companion object {
        const val UNKNOWN_PACK = "unidentified"
    }
}
