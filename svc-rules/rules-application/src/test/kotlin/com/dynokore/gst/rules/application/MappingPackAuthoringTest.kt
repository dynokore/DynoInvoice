package com.dynokore.gst.rules.application

import com.dynokore.gst.catalogue.ErrorCode
import com.dynokore.gst.rules.application.MappingPackResult.Done
import com.dynokore.gst.rules.application.MappingPackResult.Forbidden
import com.dynokore.gst.rules.application.MappingPackResult.Refused
import com.dynokore.gst.rules.domain.coding.CatalogueEntry
import com.dynokore.gst.rules.domain.coding.CodeCatalogue
import com.dynokore.gst.rules.domain.pack.MappingAcceptanceRecord
import com.dynokore.gst.rules.domain.pack.MappingDraftEntry
import com.dynokore.gst.rules.domain.pack.PackStatus
import com.dynokore.gst.rules.domain.pack.SourceClass
import com.dynokore.gst.rules.domain.resolution.TransactionDate
import com.dynokore.platform.tenancykernel.TenantId
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import java.io.File
import java.nio.file.Files
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID

private val TENANT = TenantId(UUID.fromString("00000000-0000-7000-8000-00000000000a"))
private val TODAY = LocalDate.of(2026, 11, 2)
private val CLOCK = Clock.fixed(Instant.parse("2026-11-02T10:00:00Z"), ZoneOffset.UTC)

private fun catalogueOf(vararg codes: String) =
    CodeCatalogue(true, codes.map { CatalogueEntry(it, "G", "OUTPUT", "9%", null, SourceClass.PRIMARY, "R1") })

private data class StoreState(
    val packs: Map<String, MappingPackDraft>,
    val entries: Map<String, Map<String, MappingDraftEntry>>,
    val acceptances: Map<String, List<MappingAcceptanceRecord>>,
    val approvals: Map<String, List<MappingApproval>>,
    val activations: Map<String, MappingActivation>,
)

private class FakeStore : MappingPackStore {
    val packs = linkedMapOf<String, MappingPackDraft>()
    val entries = linkedMapOf<String, MutableMap<String, MappingDraftEntry>>()
    val accs = linkedMapOf<String, MutableList<MappingAcceptanceRecord>>()
    val apps = linkedMapOf<String, MutableList<MappingApproval>>()
    val acts = linkedMapOf<String, MappingActivation>()

    fun state() =
        StoreState(
            packs.toMap(),
            entries.mapValues { it.value.toMap() },
            accs.mapValues { it.value.toList() },
            apps.mapValues { it.value.toList() },
            acts.toMap(),
        )

    override fun draft(pack: MappingPackDraft) {
        packs[pack.packId] = pack
    }

    override fun find(packId: String) = packs[packId]

    override fun replaceEntry(
        packId: String,
        entry: MappingDraftEntry,
    ) {
        entries.getOrPut(packId) { linkedMapOf() }[entry.entryId] = entry
        accs[packId]?.removeAll { it.entryId == entry.entryId }
    }

    override fun listEntries(
        packId: String,
        after: String?,
        limit: Int,
    ) = entries[packId]
        .orEmpty()
        .values
        .sortedBy { it.entryId }
        .filter { after == null || it.entryId > after }
        .take(limit)

    override fun recordAcceptance(
        packId: String,
        acceptance: MappingAcceptanceRecord,
    ) {
        accs.getOrPut(packId) { mutableListOf() } += acceptance
    }

    override fun acceptances(packId: String) = accs[packId].orEmpty().toList()

    override fun approvals(packId: String) = apps[packId].orEmpty().toList()

    override fun recordApproval(
        packId: String,
        approval: MappingApproval,
        resultingStatus: PackStatus,
    ) {
        apps.getOrPut(packId) { mutableListOf() } += approval
        setStatus(packId, resultingStatus)
    }

    override fun setStatus(
        packId: String,
        status: PackStatus,
    ) {
        packs[packId] = packs.getValue(packId).copy(status = status)
    }

    override fun recordActivation(
        packId: String,
        activation: MappingActivation,
    ) {
        acts[packId] = activation
        setStatus(packId, PackStatus.ARMED)
    }
}

private class Env {
    val store = FakeStore()
    val calls = mutableListOf<String>()
    val authorityRefs = mutableListOf<Triple<String, String, String>>()
    var allowedEntity: String? = null
    var denyAll = false
    var heldOnly: String? = null
    var delta: String? = "delta-1"
    var decision: PackApprovalDecision = PackApprovalDecision.Allow(false, null, "A", emptySet())
    val decideArgs = mutableListOf<List<String>>()
    val requests = mutableListOf<PackApprovalRequest>()
    var privateForm = false
    var fact: InstallationApproverFact = InstallationApproverFact.NotRecorded
    val factCalls = mutableListOf<String>()
    var ack: MappingAcknowledgement = MappingAcknowledgement.NotRecorded
    var ackAnswer: ((String, String) -> MappingAcknowledgement)? = null
    val ackCalls = mutableListOf<Pair<String, String>>()
    val catalogueByDate = mutableMapOf<LocalDate, CodeCatalogue>()
    var catalogue: CodeCatalogue? = catalogueOf("SR", "ZR")
    var rate: List<String>? = emptyList()
    var holder: HolderCopyResult = HolderCopyResult.Written
    val holderRequests = mutableListOf<HolderCopyRequest>()

    val authoring by lazy {
        MappingPackAuthoring(
            MappingPackCollaborators(
                store,
                { actor, ref, perm ->
                    calls += "authority"
                    authorityRefs += Triple(actor, ref, perm)
                    !denyAll && (heldOnly == null || heldOnly == perm) &&
                        (allowedEntity == null || allowedEntity == ref)
                },
                { _, _ ->
                    calls += "delta"
                    delta
                },
                { request ->
                    calls += "decide"
                    decideArgs += request.priorApproverRef
                    requests += request
                    decision
                },
                { _, date: TransactionDate ->
                    calls += "catalogue"
                    catalogueByDate[date.value] ?: catalogue
                },
                { _, _ ->
                    calls += "rate"
                    rate
                },
                { request ->
                    calls += "holder"
                    holderRequests += request
                    holder
                },
                {
                    factCalls += "fact"
                    fact
                },
                { version, client ->
                    ackCalls += version to client
                    ackAnswer?.invoke(version, client) ?: ack
                },
                privateForm,
            ),
            CLOCK,
        )
    }

    fun draft(
        id: String = "P1",
        author: String = "author-1",
    ) = authoring.draft(DraftCommand(author, "E-001", id, TENANT, "v1"))

    fun map(
        code: String,
        iras: String,
        sc: SourceClass = SourceClass.PRIMARY,
        id: String = "P1",
    ) = authoring.mapTaxCode(MapTaxCodeCommand("author-1", "E-001", id, code, iras, sc))

    fun submit(id: String = "P1") = authoring.submit(PackActionCommand("author-1", "E-001", id))

    fun approve(
        actor: String = "approver-1",
        entityRef: String = "E-001",
        id: String = "P1",
    ) = authoring.approve(PackActionCommand(actor, entityRef, id))

    fun accept(
        entry: String,
        rationale: String = "reviewed",
        expires: LocalDate = TODAY.plusDays(30),
        id: String = "P1",
    ) = authoring.acceptEntry(AcceptEntryCommand("approver-1", "E-001", id, entry, rationale, expires))

    /** A submitted pack with entries A (PRIMARY, SR) and B (SECONDARY, ZR). */
    fun submitted(id: String = "P1"): Env {
        draft(id)
        map("A", "SR", id = id)
        map("B", "ZR", SourceClass.SECONDARY, id)
        accept("B", id = id)
        submit(id)
        return this
    }

    fun activate(
        from: LocalDate = TODAY,
        id: String = "P1",
    ) = authoring.activate(ActivateCommand("approver-1", "E-001", id, from, null, "1.0.0"))
}

class MappingPackAuthoringTest {
    @Test
    fun `INV-REQ-380 draft records the author and a label of 1 to 200 characters`() {
        val e = Env()
        e.draft() shouldBe Done(PackStatus.DRAFT)
        e.store.packs
            .getValue("P1")
            .authorUserId shouldBe "author-1"
        for (label in listOf("", "  ", "a".repeat(201))) {
            val before = e.store.state()
            val result = e.authoring.draft(DraftCommand("u", "E-001", "P2", TENANT, label))
            result shouldBe Refused(RefusalReason.INPUT_INVALID)
            e.store.state() shouldBe before
        }
        e.authoring.draft(DraftCommand("u", "E-001", "P3", TENANT, "a".repeat(200))) shouldBe Done(PackStatus.DRAFT)
        e.authoring.draft(DraftCommand("u", "a".repeat(65), "P4", TENANT, "x")).shouldBeInstanceOf<Forbidden>()
    }

    @Test
    fun `INV-REQ-380 mapTaxCode is DRAFT only, refuses a namespaced code and a repeat drops the acceptance`() {
        val e = Env()
        e.draft()
        e.map("B", "ZR", SourceClass.SECONDARY)
        e.accept("B") shouldBe Done(PackStatus.DRAFT)
        e.store.accs
            .getValue("P1")
            .size shouldBe 1
        e.map("B", "SR", SourceClass.SECONDARY)
        e.store.accs
            .getValue("P1")
            .size shouldBe 0
        e.store.entries
            .getValue("P1")
            .getValue("B") shouldBe MappingDraftEntry("B", "SR", SourceClass.SECONDARY)
        val before = e.store.state()
        e.map("C", "gst:SR").let { (it as Refused).code shouldBe ErrorCode.RUL_010 }
        e.store.state() shouldBe before
        e.submit()
        e.map("D", "SR") shouldBe Refused(RefusalReason.LIFECYCLE, status = PackStatus.SUBMITTED)
    }

    @Test
    fun `INV-REQ-380 acceptEntry needs a non-PRIMARY entry, a rationale of 1 to 500 characters and a future expiry`() {
        val e = Env()
        e.draft()
        e.map("A", "SR")
        e.map("B", "ZR", SourceClass.SECONDARY)
        val before = e.store.state()
        e.accept("A") shouldBe Refused(RefusalReason.PRIMARY_ENTRY, names = listOf("A"))
        e.accept("Z") shouldBe Refused(RefusalReason.UNKNOWN_ENTRY, names = listOf("Z"))
        e.accept("B", rationale = " ") shouldBe Refused(RefusalReason.INPUT_INVALID)
        e.accept("B", rationale = "a".repeat(501)) shouldBe Refused(RefusalReason.INPUT_INVALID)
        e.accept("B", expires = TODAY) shouldBe Refused(RefusalReason.INPUT_INVALID)
        e.store.state() shouldBe before
        e.accept("B", rationale = "a".repeat(500), expires = TODAY.plusDays(1)) shouldBe Done(PackStatus.DRAFT)
    }

    @Test
    fun `INV-REQ-380 submit needs a qualifying delta and the DRAFT status`() {
        val e = Env()
        e.draft()
        e.delta = null
        e.submit() shouldBe Refused(RefusalReason.NO_DRY_RUN_DELTA)
        e.store.packs
            .getValue("P1")
            .status shouldBe PackStatus.DRAFT
        e.delta = "d"
        e.submit() shouldBe Done(PackStatus.SUBMITTED)
        e.submit() shouldBe Refused(RefusalReason.LIFECYCLE, status = PackStatus.SUBMITTED)
    }

    @Test
    fun `INV-REQ-380 approve step 1 a missing permission is Forbidden SRT-004 and the authority gets the entityRef`() {
        val e = Env().submitted()
        e.authorityRefs.clear()
        e.denyAll = true
        val before = e.store.state()
        e.calls.clear()
        e.approve().shouldBeInstanceOf<Forbidden>().code shouldBe ErrorCode.SRT_004
        e.authorityRefs shouldBe listOf(Triple("approver-1", "E-001", MappingPackPermissions.APPROVE))
        e.calls shouldBe listOf("authority")
        e.store.state() shouldBe before
    }

    @Test
    fun `INV-REQ-380 approve step 1 a fake that allows only E-001 refuses a command for E-002`() {
        val e = Env().submitted()
        e.allowedEntity = "E-001"
        e.approve(entityRef = "E-002").shouldBeInstanceOf<Forbidden>().code shouldBe ErrorCode.SRT_004
        e.approve(entityRef = "e-001").shouldBeInstanceOf<Forbidden>()
        e.approve(entityRef = "E-001") shouldBe Done(PackStatus.APPROVED)
    }

    @Test
    fun `INV-Q-14652 a user who holds the permission on E-002 cannot act on a pack stored for E-001`() {
        val e = Env().submitted()
        val before = e.store.state()
        val other = "E-002"
        val results =
            listOf(
                e.authoring.mapTaxCode(MapTaxCodeCommand("author-1", other, "P1", "C", "SR", SourceClass.PRIMARY)),
                e.authoring.acceptEntry(AcceptEntryCommand("approver-1", other, "P1", "B", "ok", TODAY.plusDays(5))),
                e.authoring.submit(PackActionCommand("author-1", other, "P1")),
                e.approve(entityRef = other),
                e.authoring.activate(ActivateCommand("approver-1", other, "P1", TODAY, null, "1.0.0")),
            )
        results.forEach { it.shouldBeInstanceOf<Forbidden>().code shouldBe ErrorCode.SRT_004 }
        e.store.state() shouldBe before
        e.holderRequests shouldBe emptyList()
        e.approve() shouldBe Done(PackStatus.APPROVED)
    }

    @Test
    fun `INV-Q-16632 draft and mapTaxCode need AUTHOR, a fake that holds only APPROVE refuses both`() {
        val e = Env()
        e.draft()
        e.heldOnly = MappingPackPermissions.APPROVE
        val before = e.store.state()
        e.draft(id = "P2").shouldBeInstanceOf<Forbidden>().code shouldBe ErrorCode.SRT_004
        e.map("A", "SR").shouldBeInstanceOf<Forbidden>().code shouldBe ErrorCode.SRT_004
        e.store.state() shouldBe before
        e.denyAll = true
        e.draft(id = "P3").shouldBeInstanceOf<Forbidden>()
        e.map("A", "SR").shouldBeInstanceOf<Forbidden>()
        e.store.state() shouldBe before
        e.denyAll = false
        e.heldOnly = MappingPackPermissions.AUTHOR
        e.draft(id = "P2") shouldBe Done(PackStatus.DRAFT)
        e.map("A", "SR") shouldBe Done(PackStatus.DRAFT)
        e.authorityRefs.map { it.third }.takeLast(2) shouldBe
            listOf(MappingPackPermissions.AUTHOR, MappingPackPermissions.AUTHOR)
    }

    @Test
    fun `INV-Q-16632 each command passes its own permission key to the authority`() {
        val e = Env()
        val keys = mutableListOf<String>()

        fun key(call: () -> MappingPackResult): String {
            val n = e.authorityRefs.size
            call()
            e.authorityRefs.size shouldBe n + 1
            return e.authorityRefs
                .last()
                .third
                .also { keys += it }
        }
        key { e.draft() } shouldBe MappingPackPermissions.AUTHOR
        key { e.map("B", "ZR", SourceClass.SECONDARY) } shouldBe MappingPackPermissions.AUTHOR
        key { e.accept("B") } shouldBe MappingPackPermissions.APPROVE
        key { e.submit() } shouldBe MappingPackPermissions.AUTHOR
        key { e.approve() } shouldBe MappingPackPermissions.APPROVE
        key { e.activate() } shouldBe MappingPackPermissions.ACTIVATE
        keys.size shouldBe 6
    }

    @Test
    fun `INV-Q-16634 an entityRef that is empty, blank or over 64 characters is Forbidden with zero authority calls`() {
        val e = Env().submitted()
        val before = e.store.state()
        e.authorityRefs.clear()
        for (ref in listOf("", "   ", "a".repeat(65))) {
            val results =
                listOf(
                    e.authoring.draft(DraftCommand("u", ref, "PX", TENANT, "v")),
                    e.authoring.mapTaxCode(MapTaxCodeCommand("author-1", ref, "P1", "C", "SR", SourceClass.PRIMARY)),
                    e.authoring.acceptEntry(AcceptEntryCommand("approver-1", ref, "P1", "B", "ok", TODAY.plusDays(5))),
                    e.authoring.submit(PackActionCommand("author-1", ref, "P1")),
                    e.approve(entityRef = ref),
                    e.authoring.activate(ActivateCommand("approver-1", ref, "P1", TODAY, null, "1.0.0")),
                )
            results.forEach {
                it shouldBe Forbidden(ErrorCode.SRT_004, "the action is not permitted")
            }
        }
        e.authorityRefs shouldBe emptyList()
        e.store.state() shouldBe before
        val ok = "a".repeat(64)
        e.authoring.draft(DraftCommand("u", ok, "P64", TENANT, "v")) shouldBe Done(PackStatus.DRAFT)
        e.authorityRefs.size shouldBe 1
    }

    @Test
    fun `INV-Q-14652 a pack drafted for E-001 refuses all five commands for E-002 and for the look-alike e-001`() {
        val e = Env().submitted()
        val before = e.store.state()
        for (other in listOf("E-002", "e-001")) {
            listOf(
                e.authoring.mapTaxCode(MapTaxCodeCommand("author-1", other, "P1", "C", "SR", SourceClass.PRIMARY)),
                e.authoring.acceptEntry(AcceptEntryCommand("approver-1", other, "P1", "B", "ok", TODAY.plusDays(5))),
                e.authoring.submit(PackActionCommand("author-1", other, "P1")),
                e.approve(entityRef = other),
                e.authoring.activate(ActivateCommand("approver-1", other, "P1", TODAY, null, "1.0.0")),
            ).forEach { it shouldBe Forbidden(ErrorCode.SRT_004, "the action is not permitted") }
        }
        e.store.state() shouldBe before
        e.holderRequests shouldBe emptyList()
        e.approve(entityRef = "E-001") shouldBe Done(PackStatus.APPROVED)
    }

    @Test
    fun `INV-REQ-380 approve step 2 a status other than SUBMITTED is refused by the lifecycle`() {
        val e = Env()
        e.draft()
        val before = e.store.state()
        e.calls.clear()
        e.approve() shouldBe Refused(RefusalReason.LIFECYCLE, status = PackStatus.DRAFT)
        e.calls shouldBe listOf("authority")
        e.store.state() shouldBe before
    }

    @Test
    fun `INV-REQ-380 approve step 3 the author is not refused locally, the port gets authorRef and approverRef`() {
        val e = Env().submitted()
        val before = e.store.state()
        e.calls.clear()
        e.decision = PackApprovalDecision.Deny("SOLE")
        e.approve(actor = "author-1").shouldBeInstanceOf<Forbidden>().code shouldBe ErrorCode.SRT_004
        e.requests.single().let { it.authorRef shouldBe it.approverRef }
        e.store.state() shouldBe before
        e.decision = PackApprovalDecision.Allow(false, null, "A", emptySet())
        e.approve(actor = "AUTHOR-1") shouldBe Done(PackStatus.APPROVED)
        e.requests.last().authorRef shouldBe "author-1"
        e.requests.last().approverRef shouldBe "AUTHOR-1"
        val f = Env().submitted()
        f.approve(actor = "author-1 ") shouldBe Done(PackStatus.APPROVED)
        f.requests.single().approverRef shouldBe "author-1 "
    }

    @Test
    fun `INV-REQ-380 approve step 4 no dry-run delta is refused and stops the later steps`() {
        val e = Env().submitted()
        e.delta = null
        val before = e.store.state()
        e.calls.clear()
        e.approve() shouldBe Refused(RefusalReason.NO_DRY_RUN_DELTA)
        e.calls shouldBe listOf("authority", "delta")
        e.store.state() shouldBe before
    }

    @Test
    fun `INV-AC-1440 approve step 5 a code outside the catalogue is RUL-009 and no acceptance releases it`() {
        val e = Env()
        e.draft()
        e.map("A", "SR")
        e.map("B", "NEW", SourceClass.SECONDARY)
        e.accept("B")
        e.submit()
        val before = e.store.state()
        val r = e.approve().shouldBeInstanceOf<Refused>()
        r.reason shouldBe RefusalReason.CODE_CHECK
        r.code shouldBe ErrorCode.RUL_009
        r.names shouldBe listOf("B")
        e.calls.contains("decide") shouldBe false
        e.store.state() shouldBe before
        e.catalogueByDate[TODAY] = catalogueOf("SR", "ZR", "NEW")
        e.approve() shouldBe Done(PackStatus.APPROVED)
    }

    @Test
    fun `INV-AC-1440 approve step 5 a namespaced code is RUL-010 and a missing catalogue is RUL-009 for every code`() {
        val e = Env()
        e.draft()
        e.map("A", "SR")
        e.map("B", "ZR")
        e.submit()
        e.catalogue = null
        val r = e.approve().shouldBeInstanceOf<Refused>()
        r.code shouldBe ErrorCode.RUL_009
        r.names shouldBe listOf("A", "B")
        // a namespaced code can only enter by a store written elsewhere; sign-off still reports RUL-010
        e.store.entries.getValue("P1")["B"] = MappingDraftEntry("B", "gst:ZR", SourceClass.PRIMARY)
        e.catalogue = catalogueOf("SR", "ZR")
        e.approve().shouldBeInstanceOf<Refused>().code shouldBe ErrorCode.RUL_010
    }

    @Test
    fun `INV-AC-756 approve step 6 an unaccepted non-PRIMARY entry is RUL-007, the acceptance ends with the draft`() {
        val e = Env()
        e.draft()
        e.map("A", "SR")
        e.map("B", "ZR", SourceClass.SECONDARY)
        e.submit()
        val r = e.approve().shouldBeInstanceOf<Refused>()
        r shouldBe Refused(RefusalReason.SIGN_OFF_BLOCKED, ErrorCode.RUL_007, listOf("B"))
        e.accept("B")
        e.approve() shouldBe Done(PackStatus.APPROVED)
        // a later version is a new draft: the acceptance does not carry over
        e.draft(id = "P2")
        e.map("A", "SR", id = "P2")
        e.map("B", "ZR", SourceClass.SECONDARY, id = "P2")
        e.submit("P2")
        e.approve(id = "P2") shouldBe Refused(RefusalReason.SIGN_OFF_BLOCKED, ErrorCode.RUL_007, listOf("B"))
    }

    @Test
    fun `INV-AC-756 approve step 6 an acceptance that expired or whose entry changed does not release the entry`() {
        val e = Env()
        e.draft()
        e.map("B", "ZR", SourceClass.SECONDARY)
        e.accept("B", expires = TODAY.plusDays(1))
        e.submit()
        e.store.accs.getValue("P1")[0] =
            e.store.accs
                .getValue("P1")[0]
                .copy(expiresOn = TODAY)
        e.approve().shouldBeInstanceOf<Refused>().code shouldBe ErrorCode.RUL_007
    }

    @Test
    fun `INV-AC-1510 approve step 7 an unanswered rate source is refused, an INFERRED one is RUL-007, none passes`() {
        val e = Env().submitted()
        e.rate = null
        val before = e.store.state()
        e.approve() shouldBe Refused(RefusalReason.RATE_SOURCE_UNAVAILABLE)
        e.rate = listOf("RS-1", "RS-2")
        e.approve() shouldBe Refused(RefusalReason.RATE_SOURCE_BLOCKED, ErrorCode.RUL_007, listOf("RS-1", "RS-2"))
        e.calls.contains("decide") shouldBe false
        e.store.state() shouldBe before
        e.rate = emptyList()
        e.approve() shouldBe Done(PackStatus.APPROVED)
    }

    @Test
    fun `INV-REQ-380 activate needs APPROVED, the code check of effectiveFrom and a written holder copy`() {
        val e = Env().submitted()
        e.activate() shouldBe Refused(RefusalReason.LIFECYCLE, status = PackStatus.SUBMITTED)
        e.approve()
        e.map("A", "SR").shouldBeInstanceOf<Refused>() // entries are frozen after DRAFT
        e.catalogueByDate[TODAY] = catalogueOf("SR")
        val before = e.store.state()
        e.activate().shouldBeInstanceOf<Refused>().code shouldBe ErrorCode.RUL_009
        e.holderRequests.size shouldBe 0
        e.catalogueByDate[TODAY] = catalogueOf("SR", "ZR")
        e.holder = HolderCopyResult.Unavailable
        e.activate() shouldBe Refused(RefusalReason.HOLDER_COPY_UNAVAILABLE)
        e.store.state() shouldBe before
        e.store.packs
            .getValue("P1")
            .status shouldBe PackStatus.APPROVED
        e.holder = HolderCopyResult.Written
        e.holderRequests.clear()
        e.activate() shouldBe Done(PackStatus.ARMED)
        e.store.packs
            .getValue("P1")
            .status shouldBe PackStatus.ARMED
        val expected =
            "{\"entries\":[" +
                "{\"entryId\":\"A\",\"internalTaxCode\":\"A\",\"irasCode\":\"SR\",\"sourceClass\":\"PRIMARY\"}," +
                "{\"entryId\":\"B\",\"internalTaxCode\":\"B\",\"irasCode\":\"ZR\",\"sourceClass\":\"SECONDARY\"}]}"
        e.holderRequests.single() shouldBe
            HolderCopyRequest(TENANT, "P1", "MAPPING", "v1", TODAY, null, "1.0.0", expected.toByteArray())
        val rows = expected.removePrefix("{\"entries\":[").removeSuffix("]}").split("},{")
        val swapped = "{\"entries\":[" + rows.reversed().joinToString("},{") + "]}"
        (
            e.holderRequests
                .single()
                .contents
                .contentEquals(swapped.toByteArray())
        ) shouldBe false
    }

    @Test
    fun `INV-REQ-419 the code check of activate uses the catalogue for the requested effectiveFrom`() {
        val e = Env().submitted()
        e.approve()
        val d2 = TODAY.plusDays(10)
        e.catalogueByDate[TODAY] = catalogueOf("SR", "ZR")
        e.catalogueByDate[d2] = catalogueOf("SR")
        e.activate(from = d2).shouldBeInstanceOf<Refused>().code shouldBe ErrorCode.RUL_009
        e.activate(from = TODAY) shouldBe Done(PackStatus.ARMED)
    }

    @Test
    fun `INV-Q-10027 no role name appears in src main of the two modules and the scan finds a planted one`() {
        val roles = listOf("tax.approver", "tax.operator", "agent.operator")
        val dirs = listOf("../rules-domain/src/main", "../rules-application/src/main").map(::File)

        fun hits(extra: File? = null) =
            (dirs + listOfNotNull(extra))
                .flatMap { it.walkTopDown().filter { f -> f.isFile && f.extension == "kt" }.toList() }
                .filter { f -> f.readText().let { t -> roles.any { it in t } } }
        dirs.all { it.isDirectory } shouldBe true
        hits() shouldBe emptyList()
        val tmp = Files.createTempDirectory("rolescan").toFile()
        try {
            File(tmp, "Planted.kt").writeText("val r = \"tax.approver\"")
            hits(tmp).map { it.name } shouldBe listOf("Planted.kt")
        } finally {
            tmp.deleteRecursively()
        }
    }
}

class MappingPackAuthorFactsTest {
    @Test
    fun `INV-AC-19524 approve by the author sends one request, authorRef and approverRef both the author`() {
        val e = Env().submitted()
        e.decision = PackApprovalDecision.Allow(true, "ack-1", "B", emptySet())
        e.approve(actor = "author-1") shouldBe Done(PackStatus.APPROVED)
        e.requests shouldBe
            listOf(PackApprovalRequest("E-001", "P1", "v1", "author-1", "author-1", emptyList()))
        e.store.apps
            .getValue("P1")
            .single()
            .soleOperator shouldBe true
    }

    @Test
    fun `INV-AC-19524 a DENY is Forbidden SRT-004 naming no person and the store is equal before and after`() {
        val e = Env().submitted()
        val before = e.store.state()
        e.decision = PackApprovalDecision.Deny("SOLE")
        val f = e.approve(actor = "author-1").shouldBeInstanceOf<Forbidden>()
        f.code shouldBe ErrorCode.SRT_004
        f.detail.contains("author-1") shouldBe false
        e.requests.size shouldBe 1
        e.store.state() shouldBe before
        e.decision = PackApprovalDecision.Unavailable
        e.approve(actor = "author-1") shouldBe Refused(RefusalReason.DECISION_UNAVAILABLE)
        e.store.state() shouldBe before
    }

    @Test
    fun `INV-AC-19524 an ALLOW with CLIENT_APPROVAL outstanding keeps SUBMITTED and one without becomes APPROVED`() {
        val e = Env().submitted()
        e.decision = PackApprovalDecision.Allow(false, null, "A", setOf("CLIENT_APPROVAL"))
        e.approve(actor = "author-1") shouldBe Done(PackStatus.SUBMITTED)
        e.decision = PackApprovalDecision.Allow(false, null, "A", setOf("CLIENT_ACKNOWLEDGEMENT"))
        e.approve(actor = "author-1") shouldBe Done(PackStatus.APPROVED)
    }

    private fun armedEnv(
        private: Boolean,
        fact: InstallationApproverFact,
    ): Env {
        val e = Env()
        e.privateForm = private
        e.fact = fact
        e.submitted()
        e.approve()
        return e
    }

    @Test
    fun `INV-AC-19524 the activation fact is true for recorded false and nothing recorded, false for recorded true`() {
        val d1 = armedEnv(true, InstallationApproverFact.Recorded(false))
        d1.activate() shouldBe Done(PackStatus.ARMED)
        val d1b = armedEnv(true, InstallationApproverFact.NotRecorded)
        d1b.activate() shouldBe Done(PackStatus.ARMED)
        val d2 = armedEnv(true, InstallationApproverFact.Recorded(true))
        d2.activate() shouldBe Done(PackStatus.ARMED)
        listOf(d1, d1b, d2).map { it.factCalls.size } shouldBe listOf(1, 1, 1)
        listOf(d1, d1b, d2).map {
            it.store.acts
                .getValue("P1")
                .noDistinctApprover
        } shouldBe
            listOf(true, true, false)
    }

    @Test
    fun `INV-AC-19524 an Unavailable installation fact refuses the activation and stores nothing`() {
        val e = armedEnv(true, InstallationApproverFact.Unavailable)
        val before = e.store.state()
        e.activate() shouldBe Refused(RefusalReason.DECISION_UNAVAILABLE, names = listOf("installation-approver-fact"))
        e.store.state() shouldBe before
        e.holderRequests.size shouldBe 0
    }

    @Test
    fun `INV-AC-19524 each Unavailable names its own source and swapped names do not match`() {
        val e = armedEnv(true, InstallationApproverFact.Unavailable)
        val fact = e.activate()
        (fact == Refused(RefusalReason.DECISION_UNAVAILABLE, names = listOf("mapping-acknowledgement"))) shouldBe false
        fact.shouldBeInstanceOf<Refused>().names shouldBe listOf("installation-approver-fact")
        val f = Env().submitted()
        f.decision = PackApprovalDecision.Allow(false, null, "A", setOf("CLIENT_ACKNOWLEDGEMENT"))
        f.approve()
        f.ack = MappingAcknowledgement.Unavailable
        val ack = f.activate()
        (ack == Refused(RefusalReason.DECISION_UNAVAILABLE, names = listOf("installation-approver-fact"))) shouldBe
            false
        ack.shouldBeInstanceOf<Refused>().names shouldBe listOf("mapping-acknowledgement")
    }

    @Test
    fun `INV-AC-19524 in the SaaS form the fact port is not asked and the fact is false`() {
        val e = armedEnv(false, InstallationApproverFact.Recorded(false))
        e.activate() shouldBe Done(PackStatus.ARMED)
        e.factCalls.size shouldBe 0
        e.store.acts
            .getValue("P1")
            .noDistinctApprover shouldBe false
    }

    @Test
    fun `INV-AC-19524 an outstanding CLIENT_ACKNOWLEDGEMENT with none recorded is Forbidden SRT-004`() {
        val e = Env().submitted()
        e.decision = PackApprovalDecision.Allow(false, null, "A", setOf("CLIENT_ACKNOWLEDGEMENT"))
        e.approve() shouldBe Done(PackStatus.APPROVED)
        val before = e.store.state()
        val f = e.activate().shouldBeInstanceOf<Forbidden>()
        f.code shouldBe ErrorCode.SRT_004
        f.detail shouldBe "the client's acknowledgement is outstanding"
        e.ackCalls shouldBe listOf("v1" to "E-001")
        e.store.state() shouldBe before
        e.store.packs
            .getValue("P1")
            .status shouldBe PackStatus.APPROVED
        e.ack = MappingAcknowledgement.Unavailable
        e.activate() shouldBe Refused(RefusalReason.DECISION_UNAVAILABLE, names = listOf("mapping-acknowledgement"))
        e.store.state() shouldBe before
        e.ack = MappingAcknowledgement.Recorded
        e.activate() shouldBe Done(PackStatus.ARMED)
    }

    @Test
    fun `INV-AC-19524 the acknowledgement port is not asked when nothing is outstanding`() {
        val e = Env().submitted()
        e.approve()
        e.activate() shouldBe Done(PackStatus.ARMED)
        e.ackCalls.size shouldBe 0
    }

    @Test
    fun `INV-AC-19524 an acknowledgement of the look-alike version 2026-30 does not release version 2026-3`() {
        val e = Env()
        e.authoring.draft(DraftCommand("author-1", "E-001", "P2", TENANT, "2026.3"))
        e.map("A", "SR", id = "P2")
        e.submit("P2")
        e.decision = PackApprovalDecision.Allow(false, null, "A", setOf("CLIENT_ACKNOWLEDGEMENT"))
        e.approve(id = "P2") shouldBe Done(PackStatus.APPROVED)
        val acknowledged = setOf("2026.30" to "E-001")
        e.ackAnswer = { version, client ->
            if ((version to client) in acknowledged) {
                MappingAcknowledgement.Recorded
            } else {
                MappingAcknowledgement.NotRecorded
            }
        }
        e.activate(id = "P2").shouldBeInstanceOf<Forbidden>().code shouldBe ErrorCode.SRT_004
        e.ackCalls shouldBe listOf("2026.3" to "E-001")
    }

    @Test
    fun `INV-REQ-380 approve step 8 Deny is Forbidden, Unavailable is refused and Allow records one approval`() {
        val e = Env().submitted()
        val before = e.store.state()
        e.decision = PackApprovalDecision.Deny("SOLE")
        val f = e.approve().shouldBeInstanceOf<Forbidden>()
        f.code shouldBe ErrorCode.SRT_004
        f.detail.contains("approver-1") shouldBe false
        f.detail.contains("author-1") shouldBe false
        e.decision = PackApprovalDecision.Unavailable
        e.approve() shouldBe Refused(RefusalReason.DECISION_UNAVAILABLE)
        e.store.state() shouldBe before
        e.decision = PackApprovalDecision.Allow(true, "ack-1", "B", setOf("CLIENT_APPROVAL"))
        e.approve() shouldBe Done(PackStatus.SUBMITTED)
        e.decideArgs.last() shouldBe emptyList()
        e.store.apps["P1"] shouldBe
            listOf(
                MappingApproval(
                    "approver-1",
                    CLOCK.instant(),
                    "delta-1",
                    true,
                    "ack-1",
                    "B",
                    setOf("CLIENT_APPROVAL"),
                ),
            )
        e.decision = PackApprovalDecision.Allow(false, null, "A", emptySet())
        e.approve(actor = "approver-2") shouldBe Done(PackStatus.APPROVED)
        e.decideArgs.last() shouldBe listOf("approver-1")
        e.store.apps
            .getValue("P1")
            .size shouldBe 2
    }
}
