package com.dynokore.gst.masterdata.domain.registry

import com.dynokore.gst.masterdata.domain.counterparty.CounterpartyContent
import com.dynokore.platform.partyregistry.IdentifierScheme
import java.time.LocalDate

/** VP-29 (INV-REQ-1290): what the lookup of the GST register does with a difference it finds. */
enum class RegisterPolicy {
    /** The default: the difference is stored as a suggestion the operator confirms. */
    SUGGEST_CONFIRM,

    /** The difference is applied to the counterparty with the provenance `REGISTER`. */
    AUTO_APPLY,

    /** Nothing is applied or suggested; the lookup is still evidence. */
    OFF,
}

/** What the IRAS Check GST Register answered for one UEN. */
data class RegisterFact(
    val found: Boolean,
    val status: String,
    val gstNumber: String?,
    val registeredName: String?,
    val asOf: LocalDate?,
)

/** What a register fact means for the counterparty record under a [RegisterPolicy]. */
sealed interface Reconciliation {
    /** Nothing differs, or the policy is `OFF`, or the register holds no such UEN. */
    data object NoChange : Reconciliation

    /** The counterparty's GSTN conflicts with the register's: MDM-007, never overwritten, no suggestion. */
    data class Conflict(
        val held: String,
        val register: String,
    ) : Reconciliation

    /** The register name differs: to be stored as a suggestion until an operator confirms it. */
    data class Suggest(
        val registerName: String,
        val currentName: String?,
    ) : Reconciliation

    /** The register name differs and the policy applies it with provenance. */
    data class Apply(
        val registerName: String,
        val currentName: String?,
    ) : Reconciliation
}

/**
 * INV-REQ-1290, INV-AC-2276, INV-AC-9496: decides what the register answer does to [content]. A GSTN the counterparty
 * holds that differs from the register's is a conflict under every policy, and a conflict is never overwritten and
 * never suggested. Otherwise a registered name that differs from the held legal name is suggested, applied or ignored
 * by [policy]. The comparison is on the trimmed text, exactly: any difference is shown to the operator.
 */
fun reconcile(
    content: CounterpartyContent,
    fact: RegisterFact,
    policy: RegisterPolicy,
): Reconciliation {
    val held =
        content.identifiers
            .firstOrNull { it.scheme == IdentifierScheme.GSTN }
            ?.value
            ?.trim()
    val register = fact.gstNumber?.trim()
    val registered = fact.registeredName?.trim()?.takeIf { it.isNotEmpty() }
    val current = content.legalName?.trim()
    return when {
        !fact.found -> Reconciliation.NoChange
        held != null && register != null && held != register -> Reconciliation.Conflict(held, register)
        registered == null || registered == current -> Reconciliation.NoChange
        policy == RegisterPolicy.SUGGEST_CONFIRM -> Reconciliation.Suggest(registered, current)
        policy == RegisterPolicy.AUTO_APPLY -> Reconciliation.Apply(registered, current)
        else -> Reconciliation.NoChange
    }
}

/** The counterparty field a register value is for (the `field` of a register suggestion). */
enum class RegisteredField { REGISTERED_NAME, GSTN }

/**
 * INV-AC-1610, INV-Q-10173 A: the GST registration number the register holds for a REGISTERED UEN when the counterparty
 * holds no GSTN; null otherwise. A GSTN the counterparty holds is never touched here: a different one is the MDM-007
 * conflict that [reconcile] reports, and the same one needs nothing. A DEREGISTERED or unknown status suggests nothing.
 */
fun registerGstnToAdd(
    content: CounterpartyContent,
    fact: RegisterFact,
): String? {
    val held = content.identifiers.any { it.scheme == IdentifierScheme.GSTN }
    return fact.gstNumber
        ?.trim()
        ?.takeIf { fact.found && fact.status == REGISTERED && !held && it.isNotEmpty() }
}

private const val REGISTERED = "REGISTERED"
