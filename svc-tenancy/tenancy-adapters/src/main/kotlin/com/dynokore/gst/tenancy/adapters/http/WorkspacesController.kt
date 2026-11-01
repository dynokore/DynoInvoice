package com.dynokore.gst.tenancy.adapters.http

import com.dynokore.gst.tenancy.application.election.CallerSource
import com.dynokore.gst.tenancy.application.tenant.TenantRefused
import com.dynokore.gst.tenancy.application.tenant.WorkspaceArchival
import com.dynokore.gst.tenancy.application.workspace.AccessPointCredential
import com.dynokore.gst.tenancy.application.workspace.ArchiveWorkspace
import com.dynokore.gst.tenancy.application.workspace.CreateWorkspace
import com.dynokore.gst.tenancy.application.workspace.CredentialLoad
import com.dynokore.gst.tenancy.application.workspace.LoadCredential
import com.dynokore.gst.tenancy.application.workspace.SampleApprovalOutcome
import com.dynokore.gst.tenancy.application.workspace.SampleCopies
import com.dynokore.gst.tenancy.application.workspace.SampleCopyOutcome
import com.dynokore.gst.tenancy.application.workspace.SampleCopyRefusal
import com.dynokore.gst.tenancy.application.workspace.TenantAuthority
import com.dynokore.gst.tenancy.application.workspace.WorkspaceCreation
import com.dynokore.gst.tenancy.contract.api.WorkspacesApi
import com.dynokore.gst.tenancy.contract.model.CreateWorkspaceRequest
import com.dynokore.gst.tenancy.contract.model.LoadCredentialRequest
import com.dynokore.gst.tenancy.contract.model.LoadCredentialResponse
import com.dynokore.gst.tenancy.contract.model.RecordSampleApprovalRequest
import com.dynokore.gst.tenancy.contract.model.RequestSampleCopyRequest
import com.dynokore.gst.tenancy.contract.model.SampleApprovalResponse
import com.dynokore.gst.tenancy.contract.model.SampleCopyResponse
import com.dynokore.gst.tenancy.contract.model.WorkspaceResponse
import com.dynokore.platform.serviceruntime.http.ClosedErrorCode
import com.dynokore.platform.serviceruntime.http.TenancyErrorCode
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/**
 * TPL-08. createWorkspace (scope tenant): the SANDBOX sibling of the bound production tenant, the original on a repeat
 * of the same `(tenantRef, name)`; archiveWorkspace undoes it and is a no-op on a repeat (INV-REQ-1125).
 */
@RestController
// open: the generated API interface is @Validated, so Spring proxies the controller (no kotlin-spring plugin)
open class WorkspacesController(
    private val createWorkspace: CreateWorkspace,
    private val archiveWorkspace: ArchiveWorkspace,
    private val loadCredential: LoadCredential,
    private val sampleCopies: SampleCopies,
    private val caller: CallerSource,
    private val authority: TenantAuthority,
) : WorkspacesApi {
    override fun createWorkspace(
        idempotencyKey: UUID,
        createWorkspaceRequest: CreateWorkspaceRequest,
    ): ResponseEntity<WorkspaceResponse> {
        val created =
            try {
                createWorkspace.handle(createWorkspaceRequest.tenantRef, createWorkspaceRequest.name)
            } catch (e: IllegalArgumentException) {
                invalid("tenantRef is not a usable tenant reference: ${e.message}")
            } catch (e: TenantRefused) {
                if (e.invalid) invalid("the workspace is refused") else throw ApiRefusal(ClosedErrorCode.ACCESS_DENIED)
            }
        return when (created) {
            is WorkspaceCreation.Done -> {
                ResponseEntity
                    .status(HttpStatus.CREATED)
                    .body(
                        WorkspaceResponse(
                            created.workspace.ref,
                            createWorkspaceRequest.tenantRef,
                            createWorkspaceRequest.name,
                            WorkspaceResponse.Environment.SANDBOX,
                        ),
                    )
            }

            WorkspaceCreation.Conflict -> {
                conflict(TenancyErrorCode.TENANT_NOT_CREATED, "the workspace conflicts with a tenant already recorded")
            }
        }
    }

    override fun archiveWorkspace(
        workspaceRef: String,
        idempotencyKey: UUID,
    ): ResponseEntity<Unit> =
        when (archiveWorkspace.handle(workspaceRef)) {
            WorkspaceArchival.ARCHIVED, WorkspaceArchival.ALREADY_ARCHIVED -> ResponseEntity.noContent().build()
            WorkspaceArchival.MISSING -> notFound()
        }

    /**
     * INV-REQ-1126: decided by the scoped permission key `ap-credential.manage` (403 SRT-004 and nothing bound
     * without it, INV-Q-13841 A), then refused 422 TNC-005 unless the credential's own environment tag equals the
     * target's.
     */
    override fun loadCredential(
        idempotencyKey: UUID,
        loadCredentialRequest: LoadCredentialRequest,
    ): ResponseEntity<LoadCredentialResponse> {
        if (!holdsCredentialKey()) throw ApiRefusal(ClosedErrorCode.ACCESS_DENIED)
        return when (
            val loaded =
                loadCredential.handle(
                    AccessPointCredential(loadCredentialRequest.name, loadCredentialRequest.environmentTag?.value),
                )
        ) {
            is CredentialLoad.Accepted -> {
                ResponseEntity.ok(
                    LoadCredentialResponse(
                        loadCredentialRequest.name,
                        LoadCredentialResponse.Environment.valueOf(loaded.environment.name),
                    ),
                )
            }

            is CredentialLoad.Refused -> {
                throw TenancyRefusal(TenancyErrorCode.CREDENTIAL_ENVIRONMENT_MISMATCH)
            }
        }
    }

    private fun holdsCredentialKey(): Boolean =
        try {
            authority.holds(caller.actor(), CREDENTIAL_MANAGE)
        } catch (_: RuntimeException) {
            false
        }

    /** INV-REQ-1130: decided by `sample-copy.approve`; 403 SRT-004 and nothing recorded for a caller without it. */
    override fun recordSampleApproval(
        idempotencyKey: UUID,
        recordSampleApprovalRequest: RecordSampleApprovalRequest,
    ): ResponseEntity<SampleApprovalResponse> {
        val sampleRef = recordSampleApprovalRequest.sampleRef
        return when (val outcome = sampleCopies.approve(caller.actor(), sampleRef)) {
            SampleApprovalOutcome.Forbidden -> {
                throw ApiRefusal(ClosedErrorCode.ACCESS_DENIED)
            }

            SampleApprovalOutcome.SamplesOff -> {
                throw TenancyRefusal(TenancyErrorCode.SAMPLE_COPY_OFF)
            }

            is SampleApprovalOutcome.Recorded -> {
                ResponseEntity.status(HttpStatus.CREATED).body(SampleApprovalResponse(sampleRef, outcome.approvedBy))
            }

            is SampleApprovalOutcome.AlreadyRecorded -> {
                ResponseEntity.ok(SampleApprovalResponse(sampleRef, outcome.approvedBy))
            }
        }
    }

    /** INV-REQ-1130, INV-REQ-1305: decided by `sample-copy.request`; each copy spends one approval. */
    override fun requestSampleCopy(
        workspaceRef: String,
        idempotencyKey: UUID,
        requestSampleCopyRequest: RequestSampleCopyRequest,
    ): ResponseEntity<SampleCopyResponse> {
        val sampleRef = requestSampleCopyRequest.sampleRef
        return when (val outcome = sampleCopies.copy(caller.actor(), workspaceRef, sampleRef)) {
            SampleCopyOutcome.Forbidden -> {
                throw ApiRefusal(ClosedErrorCode.ACCESS_DENIED)
            }

            is SampleCopyOutcome.Refused -> {
                refuse(outcome.reason)
            }

            is SampleCopyOutcome.Copied -> {
                ResponseEntity
                    .status(HttpStatus.ACCEPTED)
                    .body(SampleCopyResponse(workspaceRef, sampleRef, SampleCopyResponse.Status.REQUESTED))
            }
        }
    }

    private fun refuse(reason: SampleCopyRefusal): Nothing =
        when (reason) {
            SampleCopyRefusal.SAMPLES_OFF -> throw TenancyRefusal(TenancyErrorCode.SAMPLE_COPY_OFF)
            SampleCopyRefusal.NOT_APPROVED -> throw TenancyRefusal(TenancyErrorCode.SAMPLE_COPY_NOT_APPROVED)
            SampleCopyRefusal.NO_SUCH_WORKSPACE -> notFound()
        }

    private companion object {
        const val CREDENTIAL_MANAGE = "ap-credential.manage"
    }
}
