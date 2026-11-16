package com.dynokore.gst.canonical.adapters.clients

import com.dynokore.gst.canonical.adapters.clients.masterdata.model.IdentityTripleFacts
import com.dynokore.gst.canonical.application.aggregate.AggregateSupplierIdentity
import com.dynokore.gst.canonical.application.aggregate.SupplierAnswer
import com.dynokore.gst.canonical.application.aggregate.SupplierIdentity
import com.dynokore.gst.canonical.application.document.EntityConstitutions
import java.time.LocalDate
import java.util.UUID

/**
 * [AggregateSupplierIdentity] over the identity triple of `resolveCounterparty` of masterdata-svc (INV-REQ-437). Not a
 * Spring component: the caller wires it. Nothing is invented or defaulted: an unknown constitution, a refusal, no
 * answer and a blank or over-long member are all [SupplierAnswer.Retry]; no exception is raised.
 */
class MasterdataSupplierIdentity(
    private val constitutions: EntityConstitutions,
    private val resolve: (String, String, LocalDate) -> MasterDataAnswer<IdentityTripleFacts>,
) : AggregateSupplierIdentity {
    constructor(constitutions: EntityConstitutions, client: MasterDataClient) :
        this(constitutions, client::resolveIdentityTriple)

    override fun identityOf(
        tenantId: UUID,
        entityRef: String,
        transactionDate: LocalDate,
    ): SupplierAnswer {
        val constitution =
            constitutions.constitutionOf(tenantId, entityRef)
                ?: return SupplierAnswer.Retry("constitution unknown")
        return when (val answer = resolve(entityRef, constitution, transactionDate)) {
            is MasterDataAnswer.Answered -> {
                found(answer.value)
            }

            is MasterDataAnswer.Refused -> {
                SupplierAnswer.Retry("masterdata refused: status ${answer.status} code ${answer.code}")
            }

            is MasterDataAnswer.Unavailable -> {
                SupplierAnswer.Retry(answer.reason)
            }
        }
    }

    private fun found(triple: IdentityTripleFacts): SupplierAnswer {
        val bad =
            listOf(
                Triple("senderId", triple.senderId, SENDER_MAX),
                Triple("supplierId", triple.supplierId, SUPPLIER_MAX),
                Triple("supplierGstn", triple.supplierGstn, GSTN_MAX),
            ).firstOrNull { (_, value, max) -> value.isBlank() || value.length > max }
        return if (bad != null) {
            SupplierAnswer.Retry("${bad.first} is blank or longer than ${bad.third}")
        } else {
            SupplierAnswer.Found(SupplierIdentity(triple.senderId, triple.supplierId, triple.supplierGstn))
        }
    }

    private companion object {
        const val SENDER_MAX = 64
        const val SUPPLIER_MAX = 64
        const val GSTN_MAX = 20
    }
}
