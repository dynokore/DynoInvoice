package com.dynokore.gst.consoleBff.adapters.clients

import com.dynokore.gst.consoleBff.adapters.clients.canonical.api.DocumentsApi
import com.dynokore.gst.consoleBff.adapters.clients.canonical.api.ReassignmentsApi
import com.dynokore.gst.consoleBff.adapters.clients.canonical.model.DocumentSummary
import com.dynokore.gst.consoleBff.adapters.clients.canonical.model.DocumentVersion
import com.dynokore.gst.consoleBff.adapters.clients.canonical.model.PreparedReassignment
import com.dynokore.gst.consoleBff.adapters.clients.canonical.model.PreparedReassignmentPage
import com.dynokore.gst.consoleBff.adapters.clients.canonical.model.ReleaseHeldDocumentRequest
import com.dynokore.gst.consoleBff.adapters.clients.canonical.model.ReleasedDocument
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.core.env.Environment
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import java.util.UUID

/**
 * ARC-08, STK-46, INV-REQ-1202 behavior 2: the five end-user operations of canonical-svc that console-bff calls
 * (`x-sync-edge: console-bff->canonical-svc`), through the client generated from the owner's contract. The
 * [RestClient] comes from the bean `internalRestClientBuilder`, whose interceptors (service-runtime) set the
 * `Authorization` header to the end user's token of the request being served. This class sets no header itself.
 */
@Component
class CanonicalEdgeClient(
    @Qualifier("internalRestClientBuilder") builder: RestClient.Builder,
    environment: Environment,
) {
    private val restClient = builder.baseUrl(baseUrl(environment)).build()
    private val documents = DocumentsApi(restClient)
    private val reassignments = ReassignmentsApi(restClient)

    fun getDocument(docUuid: UUID): DocumentSummary = documents.getDocument(docUuid)

    fun getDocumentVersion(
        docUuid: UUID,
        version: Int,
    ): DocumentVersion = documents.getDocumentVersion(docUuid, version)

    fun listPreparedReassignments(
        entityRef: String,
        limit: Int? = null,
        cursor: String? = null,
    ): PreparedReassignmentPage =
        if (limit == null) {
            reassignments.listPreparedReassignments(entityRef, cursor = cursor)
        } else {
            reassignments.listPreparedReassignments(entityRef, limit, cursor)
        }

    fun confirmPeriodReassignment(
        entityRef: String,
        reassignmentId: UUID,
    ): PreparedReassignment = reassignments.confirmPeriodReassignment(entityRef, reassignmentId)

    fun releaseHeldDocument(
        docUuid: UUID,
        request: ReleaseHeldDocumentRequest,
    ): ReleasedDocument = documents.releaseHeldDocument(docUuid, request)

    companion object {
        const val URL_PROPERTY = "console.canonical-svc-url"

        /** Required and not blank, with no default (TPL-16, twelve-factor config): the context fails without it. */
        fun baseUrl(environment: Environment): String =
            environment.getRequiredProperty(URL_PROPERTY).also {
                require(it.isNotBlank()) { "$URL_PROPERTY must not be blank" }
            }
    }
}
