package com.dynokore.gst.tenancy.acceptance.gates

import org.yaml.snakeyaml.Yaml
import java.io.File

/**
 * The contract gate (INV-T-4925): `operations.tsv` lists every operationId of the tenancy OpenAPI document and
 * every channel of its AsyncAPI document with the task that implements it. It fails when
 * - the contract holds an operationId or channel the table does not list (or lists twice);
 * - the table lists an operationId or channel the contract does not hold;
 * - a row's task is marked `done` while its operation still answers 501: the generated interface answers 501 for an
 *   operation no adapters class overrides, and an override that calls `notImplemented("<operationId>")` is an explicit
 *   stub; or a row is `pending` while the operation is implemented (a stale row);
 * - a channel row is done and no test class `<Channel>PayloadSchemaTest` exists (INV-Q-7391);
 * - a row is malformed: not `name`, `kind`, `task`, `state` columns, an unknown kind or state, a name the ruling
 *   (INV-Q-7388, INV-Q-7391) does not classify, or a task other than the one the ruling assigns it.
 *
 * Deny by default: anything the gate cannot classify is a finding.
 */
object OperationsGate {
    const val NAME = "operations.tsv"
    private val OVERRIDE = Regex("""\boverride\s+fun\s+(\w+)\s*\(""")
    private val STUB = Regex("""notImplemented\(\s*"([^"\\]+)"\s*\)""")
    private val CLASS = Regex("""\bclass\s+(\w+)""")
    private val HEADER = listOf("name", "kind", "task", "state")

    /** The suffix of the payload-schema test class every channel of a done task must have (INV-Q-7391). */
    const val PAYLOAD_TEST_SUFFIX = "PayloadSchemaTest"

    /** The class names declared in the given test sources. */
    fun classNames(testSources: List<File>): Set<String> =
        testSources
            .filter { it.isFile && it.extension == "kt" }
            .flatMap {
                CLASS
                    .findAll(SourceText.stripCommentsAndStrings(it.readText()))
                    .map { m ->
                        m.groupValues[1]
                    }.toList()
            }.toSet()

    fun contractOperations(openapi: String): Set<String> {
        val root = Yaml().load<Map<String, Any?>>(openapi)
        val paths = root["paths"] as? Map<*, *> ?: return emptySet()
        return paths.values
            .filterIsInstance<Map<*, *>>()
            .flatMap { item -> item.values.filterIsInstance<Map<*, *>>().mapNotNull { it["operationId"] as? String } }
            .toSet()
    }

    /** The channel keys under `components.messages` of the AsyncAPI document (the channel name of the table). */
    fun contractChannels(asyncapi: String): Set<String> {
        val root = Yaml().load<Map<String, Any?>>(asyncapi)
        val components = root["components"] as? Map<*, *>
        return (components?.get("messages") as? Map<*, *>)
            ?.keys
            ?.filterIsInstance<String>()
            ?.toSet()
            .orEmpty()
    }

    /** The functions the adapters' main sources override (an operation is implemented only if one overrides it). */
    fun overrides(adapterSources: List<File>): Set<String> = matches(adapterSources, OVERRIDE)

    /** The operationIds the adapters' main sources explicitly answer 501 for. */
    fun stubs(adapterSources: List<File>): Set<String> = matches(adapterSources, STUB)

    private fun matches(
        files: List<File>,
        pattern: Regex,
    ): Set<String> =
        files
            .filter { it.isFile && it.extension == "kt" }
            .flatMap { pattern.findAll(SourceText.stripComments(it.readText())).map { m -> m.groupValues[1] }.toList() }
            .toSet()

    /**
     * What the code answers: which operations are overridden, which of those call `notImplemented`, and the test
     * classes that exist (a done channel needs its payload-schema test class).
     */
    data class Code(
        val implemented: Set<String>,
        val stubbed: Set<String>,
        val testClasses: Set<String> = emptySet(),
    )

    private data class Contract(
        val operations: Set<String>,
        val channels: Set<String>,
    )

    fun check(
        openapi: String,
        asyncapi: String,
        table: String?,
        code: Code,
        assigned: (kind: String, name: String) -> String? = { kind, name -> Assignments.of(kind)[name] },
    ): List<Finding> {
        val parsed = table?.let(Tsv::rows) ?: return listOf(Finding(NAME, NAME, "the table is missing or empty"))
        val (header, rows) = parsed
        val operations = contractOperations(openapi)
        val channels = contractChannels(asyncapi)
        val contract = Contract(operations, channels)
        val findings = ArrayList<Finding>()
        if (header != HEADER) findings += Finding(NAME, "header", "expected columns $HEADER, found $header")
        val seen = HashSet<List<String>>()
        for (row in rows) {
            findings +=
                if (row.size == HEADER.size &&
                    row.none { it.isEmpty() }
                ) {
                    rowFindings(row, seen, contract, code, assigned)
                } else {
                    listOf(malformed(row))
                }
        }
        val listed = rows.filter { it.size == HEADER.size }.map { it[1] to it[0] }.toSet()
        findings += unlisted("operation", operations, listed, "operationId")
        findings += unlisted("channel", channels, listed, "channel")
        findings +=
            code.stubbed.filter { it !in operations }.sorted().map {
                Finding(NAME, it, "a 501 stub names an operationId the contract does not hold")
            }
        return findings
    }

    private fun malformed(row: List<String>) =
        Finding(NAME, row.joinToString(" "), "a row has the four columns $HEADER, all filled")

    private fun unlisted(
        kind: String,
        contract: Set<String>,
        listed: Set<Pair<String, String>>,
        noun: String,
    ) = contract.filter { kind to it !in listed }.sorted().map {
        Finding(NAME, it, "the contract holds this $noun but the table does not list it")
    }

    private fun rowFindings(
        row: List<String>,
        seen: MutableSet<List<String>>,
        contract: Contract,
        code: Code,
        assigned: (String, String) -> String?,
    ): List<Finding> {
        val id = row[0]
        val kind = row[1]
        val task = row[2]
        val state = row[3]
        val out = ArrayList<Finding>()
        if (!seen.add(listOf(kind, id))) out += Finding(NAME, id, "listed twice")
        val expected = assigned(kind, id)
        if (expected == null) {
            out += Finding(NAME, id, "the $kind has no assigned task in the ruling; classify it first")
        } else if (task != expected) {
            out += Finding(NAME, id, "task '$task' is not the assigned task $expected")
        }
        if (state != "done" && state != "pending") out += Finding(NAME, id, "state '$state' is not done or pending")
        when (kind) {
            "operation" -> {
                out += operationFindings(id, task, state, contract.operations, code)
            }

            "channel" -> {
                if (id !in contract.channels) {
                    out += Finding(NAME, id, "the table lists a channel the contract does not hold")
                }
                if (state == "done" && id + PAYLOAD_TEST_SUFFIX !in code.testClasses) {
                    out += Finding(NAME, id, "task $task is done but no test class ${id + PAYLOAD_TEST_SUFFIX} exists")
                }
            }

            else -> {
                out += Finding(NAME, id, "kind '$kind' is not operation or channel")
            }
        }
        return out
    }

    private fun operationFindings(
        id: String,
        task: String,
        state: String,
        operations: Set<String>,
        code: Code,
    ): List<Finding> {
        val out = ArrayList<Finding>()
        if (id !in operations) out += Finding(NAME, id, "the table lists an operationId the contract does not hold")
        val answers501 = id in code.stubbed || id !in code.implemented
        if (state == "done" &&
            answers501
        ) {
            out += Finding(NAME, id, "task $task is done but the operation still answers 501")
        }
        if (state == "pending" && !answers501) {
            out += Finding(NAME, id, "task $task is pending but the operation is implemented (stale row)")
        }
        return out
    }
}

/** The task each operation and channel belongs to (coordinator rulings INV-Q-7388 and INV-Q-7391). */
internal object Assignments {
    private fun of(
        task: String,
        vararg names: String,
    ) = names.associateWith { task }

    val operations: Map<String, String> =
        of("INV-T-4925", "createTenant", "createEntity", "getEntityStructure", "createGroup", "registerBusinessUnit") +
            of("INV-T-4925", "moveBusinessUnit", "updateBusinessUnit") +
            of("INV-T-4926", "assessPhase", "setEntityStatus") +
            of("INV-T-4927", "setIssuanceMode", "recordElection", "electRateSource", "getElectionStatus") +
            of("INV-T-10125", "approveElectionChange") +
            of("INV-T-4929", "changeFilingFrequency", "confirmPeriods") +
            of("INV-T-4930", "recordExtension", "listPeriodRecords") +
            of(
                "INV-T-4931",
                "certifySandbox",
                "goLive",
                "suspendEntity",
                "deregisterEntity",
                "recordRenditionResponsibility",
                "offboardTenant",
            ) +
            of("INV-T-4932", "createWorkspace", "archiveWorkspace") +
            of(
                "INV-T-4933",
                "installLicence",
                "getUsageCounters",
                "answerQuestionnaire",
                "recordAppliedOperatingProfile",
                "getAppliedOperatingProfile",
                "recordEntitlement",
            ) +
            of("INV-T-8142", "openTenant") +
            of("INV-T-8179", "closeTenant") +
            of("INV-T-3290", "recordInstallationAction") +
            of("INV-T-7382", "recordMigrationCutover", "getMigrationCutover")

    val channels: Map<String, String> =
        of("INV-T-4925", "OrganisationStructureChanged", "ExampleHappened") +
            of("INV-T-4926", "EntityProfileChanged") +
            of("INV-T-4929", "FilingFrequencyChanged") +
            of("INV-T-4930", "PeriodExtended", "PeriodDueDateChanged") +
            of("INV-T-4933", "InstallationStatusChanged")

    fun of(kind: String): Map<String, String> =
        when (kind) {
            "operation" -> operations
            "channel" -> channels
            else -> emptyMap()
        }
}
