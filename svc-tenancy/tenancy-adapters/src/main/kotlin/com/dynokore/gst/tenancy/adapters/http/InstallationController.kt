package com.dynokore.gst.tenancy.adapters.http

import com.dynokore.gst.tenancy.application.election.CallerSource
import com.dynokore.gst.tenancy.application.installation.ElectionOutcome
import com.dynokore.gst.tenancy.application.installation.InstallOutcome
import com.dynokore.gst.tenancy.application.installation.InstallationAuthority
import com.dynokore.gst.tenancy.application.installation.InstallationHealth
import com.dynokore.gst.tenancy.application.installation.InstallationOutcome
import com.dynokore.gst.tenancy.application.installation.InstallationPermissions
import com.dynokore.gst.tenancy.application.installation.InstallationStatus
import com.dynokore.gst.tenancy.application.installation.InstallationStep
import com.dynokore.gst.tenancy.application.installation.InstalledLicence
import com.dynokore.gst.tenancy.application.installation.Licences
import com.dynokore.gst.tenancy.application.installation.Platform
import com.dynokore.gst.tenancy.application.installation.PlatformElection
import com.dynokore.gst.tenancy.application.installation.RecordInstallationAction
import com.dynokore.gst.tenancy.contract.api.InstallationApi
import com.dynokore.gst.tenancy.contract.model.ElectPlatformRequest
import com.dynokore.gst.tenancy.contract.model.InstallLicenceRequest
import com.dynokore.gst.tenancy.contract.model.InstallationActionAck
import com.dynokore.gst.tenancy.contract.model.InstallationFactsRequest
import com.dynokore.gst.tenancy.contract.model.InstallationFactsResponse
import com.dynokore.gst.tenancy.contract.model.LicenceResponse
import com.dynokore.gst.tenancy.contract.model.PlatformElectionResponse
import com.dynokore.gst.tenancy.contract.model.RecordInstallationActionRequest
import com.dynokore.platform.serviceruntime.http.ClosedErrorCode
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.context.request.RequestContextHolder
import org.springframework.web.context.request.ServletRequestAttributes
import org.springframework.web.util.ContentCachingRequestWrapper
import org.springframework.web.util.WebUtils
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import java.time.ZoneOffset
import java.util.UUID

/**
 * TPL-08. recordInstallationAction (scope installation, caller the installer workload, checked by
 * [InstallerCallFilter]): records the step, its installation facts and its audit entry in one transaction; a repeat of
 * (run id, step) answers the original acknowledgement (INV-Q-3245 A). The API-15 `Idempotency-Key` is required and
 * validated by the contract; the permanent key is (run id, step), so its value does not decide the outcome.
 */
@RestController
@Suppress("TooManyFunctions", "LongParameterList") // one function per contract operation; one port per use case
// open: the generated API interface is @Validated, so Spring proxies the controller (no kotlin-spring plugin)
open class InstallationController(
    private val record: RecordInstallationAction,
    private val status: InstallationStatus,
    private val health: InstallationHealth,
    private val licences: Licences,
    private val mapper: ObjectMapper,
    private val caller: CallerSource,
    private val authority: InstallationAuthority,
) : InstallationApi {
    override fun recordInstallationAction(
        idempotencyKey: UUID,
        recordInstallationActionRequest: RecordInstallationActionRequest,
    ): ResponseEntity<InstallationActionAck> {
        val ack = record.handle(step(recordInstallationActionRequest)).let(InstallationOutcome::ack)
        return ResponseEntity.ok(
            InstallationActionAck(
                ack.runId,
                ack.step,
                ack.auditEntryId,
                ack.recordedAt.atOffset(ZoneOffset.UTC),
                ignoredFields(),
            ),
        )
    }

    /** electPlatform (INV-REQ-1313): the SaaS form and SUPPLIED_APPLIANCE are refused with POL-011, nothing stored. */
    override fun electPlatform(
        idempotencyKey: UUID,
        electPlatformRequest: ElectPlatformRequest,
    ): ResponseEntity<PlatformElectionResponse> {
        val allowed =
            try {
                authority.holds(caller.actor(), InstallationPermissions.SET_PLATFORM)
            } catch (_: RuntimeException) {
                false
            }
        if (!allowed) throw ApiRefusal(ClosedErrorCode.ACCESS_DENIED)
        val requested = Platform.valueOf(electPlatformRequest.platform.value)
        return when (val outcome = status.elect(requested)) {
            is ElectionOutcome.Elected -> ResponseEntity.status(HttpStatus.CREATED).body(response(outcome.election))
            is ElectionOutcome.Refused -> optionRefused(outcome.reason)
        }
    }

    /** getPlatformElection: 404 before any election; there is no default. */
    override fun getPlatformElection(): ResponseEntity<PlatformElectionResponse> =
        ResponseEntity.ok(response(status.election() ?: notFound()))

    /** recordInstallationFacts (INV-REQ-1095): stores the facts; DEP-004 iff the train is outside the window. */
    override fun recordInstallationFacts(
        idempotencyKey: UUID,
        installationFactsRequest: InstallationFactsRequest,
    ): ResponseEntity<InstallationFactsResponse> {
        val request = installationFactsRequest
        val stored = health.record(request.trainVersion, request.supportedTrainVersions)
        return ResponseEntity.ok(InstallationFactsResponse(stored.trainVersion, stored.supportedTrainVersions))
    }

    /** installLicence (INV-REQ-1076): a refused file is 400 and nothing is recorded. */
    override fun installLicence(
        idempotencyKey: UUID,
        installLicenceRequest: InstallLicenceRequest,
    ): ResponseEntity<LicenceResponse> =
        when (val outcome = licences.install(installLicenceRequest.licenceId, installLicenceRequest.signedFile)) {
            is InstallOutcome.Installed -> ResponseEntity.status(HttpStatus.CREATED).body(licenceOf(outcome.licence))
            is InstallOutcome.Refused -> invalid(outcome.reason)
        }

    /** getLicenceStatus: the latest installed licence, 404 when none. */
    override fun getLicenceStatus(): ResponseEntity<LicenceResponse> =
        ResponseEntity.ok(licenceOf(licences.latest() ?: notFound()))

    private fun licenceOf(licence: InstalledLicence) =
        LicenceResponse(licence.licenceId, LicenceResponse.Status.forValue(licence.status.name), licence.expiresAt)

    private fun response(election: PlatformElection) =
        PlatformElectionResponse(
            PlatformElectionResponse.Platform.forValue(election.platform.name),
            election.electedAt.atOffset(ZoneOffset.UTC),
        )

    private fun step(request: RecordInstallationActionRequest) =
        InstallationStep(
            runId = request.runId,
            step = request.step,
            command = request.command.value,
            stepName = request.stepName,
            outcome = request.outcome.value,
            occurredAt = request.occurredAt.toInstant(),
            runIdentity = request.runIdentity,
            deploymentOperator = request.deploymentOperator,
            approvingEngineer = request.approvingEngineer,
            trainFrom = request.trainFrom,
            trainTo = request.trainTo,
            facts = facts(request),
        )

    /** The facts the step sets, by fact key: scalar facts as text, structured facts as one compact JSON object. */
    private fun facts(request: RecordInstallationActionRequest): Map<String, String> {
        val facts = request.facts ?: return emptyMap()
        val out = linkedMapOf<String, String>()
        facts.releaseTrain?.let { out[RELEASE_TRAIN] = it }
        facts.supportedWindow?.let { out[SUPPORTED_WINDOW] = it }
        facts.backupTarget?.let {
            val node = mapper.createObjectNode()
            node.put("verified", it.verified)
            node.put(
                "checkedAt",
                it.checkedAt
                    .withOffsetSameInstant(ZoneOffset.UTC)
                    .toInstant()
                    .toString(),
            )
            it.detail?.let { detail -> node.put("detail", detail) }
            out[BACKUP_TARGET] = node.toString()
        }
        facts.heartbeatTarget?.let {
            val node = mapper.createObjectNode()
            node.put("present", it.present)
            it.endpointHost?.let { host -> node.put("endpointHost", host) }
            out[HEARTBEAT_TARGET] = node.toString()
        }
        return out
    }

    /** API-10: the names of the request fields the contract does not know, never stored. */
    private fun ignoredFields(): List<String> {
        val servlet = (RequestContextHolder.getRequestAttributes() as? ServletRequestAttributes)?.request
        val cached = servlet?.let { WebUtils.getNativeRequest(it, ContentCachingRequestWrapper::class.java) }
        val root: JsonNode? = cached?.let { runCatching { mapper.readTree(it.contentAsByteArray) }.getOrNull() }
        return root
            ?.let { (unknown(it, KNOWN_FIELDS, "") + unknown(it.path("facts"), KNOWN_FACTS, "facts.")).sorted() }
            .orEmpty()
    }

    private fun unknown(
        node: JsonNode,
        known: Set<String>,
        prefix: String,
    ): List<String> =
        if (node.isObject) {
            node.propertyNames().filter { it !in known }.map {
                (prefix + it).take(
                    MAX_NAME,
                )
            }
        } else {
            emptyList()
        }

    companion object {
        const val RELEASE_TRAIN = "releaseTrain"
        const val SUPPORTED_WINDOW = "supportedWindow"
        const val BACKUP_TARGET = "backupTarget"
        const val HEARTBEAT_TARGET = "heartbeatTarget"
        private const val MAX_NAME = 128
        private val KNOWN_FIELDS =
            setOf(
                "runId",
                "step",
                "command",
                "stepName",
                "outcome",
                "occurredAt",
                "runIdentity",
                "deploymentOperator",
                "approvingEngineer",
                "trainFrom",
                "trainTo",
                "facts",
            )
        private val KNOWN_FACTS = setOf(RELEASE_TRAIN, SUPPORTED_WINDOW, BACKUP_TARGET, HEARTBEAT_TARGET)
    }
}
