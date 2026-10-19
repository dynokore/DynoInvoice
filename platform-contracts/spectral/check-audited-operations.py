#!/usr/bin/env python3
"""spectral/check-audited-operations.py — INV-T-7365 (INV-Q-7360 A, INV-REQ-2588, INV-AC-2592)

Fails closed when the table catalogue/audited-operations.tsv does not map each of the 69 audited
operation names of INV-REQ-2588 behavior 1 to operations of the contracts. Columns, tab separated:
listedName, operationId, service, discriminator. `service` is the name of a contract directory
with an `openapi.yaml` at its root; an operationId is searched in that file alone. The operationId
UNCONTRACTED marks a name whose contract task has not yet added the operation. The discriminator
is `-`, or a GrantRequest.granteeKind value on the operation grantRole of access (inviteUser is
STAFF, inviteExternalIdentity is EXTERNAL). Cells are compared by equality, never trimmed.

Every finding is one line printed as written in the task; exit 1 on any finding.

Usage: check-audited-operations.py [--names-file FILE] [ROOT]      (default ROOT: the current directory)
--names-file replaces AUDITED_NAMES by the lines of FILE (for the self-test only).
Standard library plus PyYAML, as the other spectral checks.
"""
from __future__ import annotations

import re
import sys
from pathlib import Path

import yaml

AUDITED_NAMES = (
    "acknowledgeMapping",
    "decideElevation",
    "grantRole",
    "inviteDiagnostics",
    "inviteExternalIdentity",
    "inviteUser",
    "issueSignatoryLink",
    "managePrincipal",
    "mapIdpGroup",
    "recordSoleOperator",
    "requestElevation",
    "resealBreakGlass",
    "runAccessReview",
    "unlockBreakGlass",
    "applyOperatingProfile",
    "copyAnonymisedSample",
    "correctPersonalData",
    "exportSupportBundle",
    "promoteConfiguration",
    "authorRegulatoryPack",
    "issueLicence",
    "managePlan",
    "provisionTenant",
    "publishPack",
    "publishTrain",
    "recordQualification",
    "setSupportWindow",
    "signPack",
    "closePrivacyCase",
    "openPrivacyCase",
    "placeLegalHold",
    "recordSecurityCase",
    "replayFeed",
    "sendTestEvent",
    "enterRate",
    "importCounterparties",
    "importRates",
    "sendCounterpartyRequest",
    "submitCounterpartyDetails",
    "manageWebhook",
    "replayWebhook",
    "setAlertPolicy",
    "acknowledgeControlRelaxation",
    "pauseJob",
    "runJobNow",
    "setPolicy",
    "runSampleCheck",
    "annotateVariance",
    "attestWorkPaper",
    "uploadGstReturn",
    "acceptSourceClassEntry",
    "acknowledgePackDelta",
    "activatePack",
    "importMappingSpreadsheet",
    "resolveAmendmentHold",
    "withdrawDocument",
    "answerQuestionnaire",
    "changeFilingFrequency",
    "confirmPeriods",
    "createWorkspace",
    "electRateSource",
    "installLicence",
    "recordElection",
    "recordExtension",
    "recordRenditionResponsibility",
    "setEntityStatus",
    "setIssuanceMode",
    "rotateCredential",
    "testConnection",
)
assert len(AUDITED_NAMES) == 69 and len(set(AUDITED_NAMES)) == 69, "AUDITED_NAMES must have 69 distinct elements"

HEADER = ["listedName", "operationId", "service", "discriminator"]
TABLE = "audited-operations.tsv"
UNCONTRACTED = "UNCONTRACTED"
GRANT_OP, GRANT_SERVICE = "grantRole", "access"
INVITE_KIND = {"inviteUser": "STAFF", "inviteExternalIdentity": "EXTERNAL"}


def operation_ids(doc) -> set[str]:
    """The operationId of every operation under `paths` of a parsed OpenAPI document."""
    ids: set[str] = set()
    paths = doc.get("paths") if isinstance(doc, dict) else None
    for item in (paths.values() if isinstance(paths, dict) else ()):
        for op in (item.values() if isinstance(item, dict) else ()):
            if isinstance(op, dict) and isinstance(op.get("operationId"), str):
                ids.add(op["operationId"])
    return ids


def granted_kinds(doc):
    """The GrantRequest.granteeKind enum as a list of strings, or None."""
    try:
        members = doc["components"]["schemas"]["GrantRequest"]["properties"]["granteeKind"]["enum"]
    except (KeyError, TypeError):
        return None
    return [m for m in members if isinstance(m, str)] if isinstance(members, list) else None


def load_services(root: Path, findings: list[str]) -> dict[str, dict]:
    """service directory name -> {"ids": set, "doc": parsed} for every <dir>/openapi.yaml at the root."""
    services: dict[str, dict] = {}
    for path in sorted(root.glob("*/openapi.yaml")):
        name = path.parent.name
        try:
            doc = yaml.safe_load(path.read_text())
        except (yaml.YAMLError, OSError, UnicodeError) as e:
            findings.append(f"{TABLE}: {name}/openapi.yaml cannot be parsed: {str(e).splitlines()[0] if str(e) else type(e).__name__}")
            continue
        services[name] = {"ids": operation_ids(doc), "doc": doc}
    return services


def check(root: Path, names: tuple[str, ...]) -> tuple[list[str], int]:
    findings: list[str] = []
    table = root / "catalogue" / TABLE
    try:
        text = table.read_text()
    except (OSError, UnicodeError) as e:
        return [f"{TABLE}: cannot be read: {type(e).__name__}"], 0
    lines = text.split("\n")
    if lines and lines[-1] == "":
        lines.pop()
    if not lines or not any(l.strip() for l in lines):
        return [f"{TABLE}: is empty"], 0
    services = load_services(root, findings)
    access = services.get(GRANT_SERVICE)
    kinds = granted_kinds(access["doc"]) if access else None
    if kinds is None:
        findings.append(f"{TABLE}: {GRANT_SERVICE}/openapi.yaml has no GrantRequest.granteeKind enum")
    if lines[0].split("\t") != HEADER:
        findings.append(f"{TABLE}: the header differs from {', '.join(HEADER)}")

    seen: dict[tuple[str, str, str], int] = {}
    listed: set[str] = set()
    pairs: set[tuple[str, str]] = set()  # (service, operationId) of the data lines
    plain_grant = 0
    for no, raw in enumerate(lines[1:], 2):
        cells = raw.split("\t")
        if len(cells) != 4:
            findings.append(f"{TABLE}: line {no} has {len(cells)} columns, not four")
            continue
        if any(not c.strip() for c in cells):
            findings.append(f"{TABLE}: line {no} has an empty cell")
            continue
        name, op, svc, disc = cells
        if name not in names:
            findings.append(f"{TABLE}: line {no}: listedName '{name}' is not one of the audited operations")
        else:
            listed.add(name)
        key = (name, op, svc)
        if key in seen:
            findings.append(f"{TABLE}: line {no} repeats line {seen[key]}")
        else:
            seen[key] = no
        pairs.add((svc, op))
        info = services.get(svc) if re.fullmatch(r"[A-Za-z0-9_-]+", svc) else None
        if info is None:
            findings.append(f"{TABLE}: line {no}: service '{svc}' has no openapi.yaml")
        elif op == UNCONTRACTED:
            if name in info["ids"]:
                findings.append(f"{TABLE}: line {no}: UNCONTRACTED, but {svc}/openapi.yaml has the operationId '{name}'")
        elif op not in info["ids"]:
            findings.append(f"{TABLE}: line {no}: operationId '{op}' is not an operationId of {svc}/openapi.yaml")
        if op == GRANT_OP and svc == GRANT_SERVICE and disc == "-":
            plain_grant += 1
        if disc == "-":
            if name in INVITE_KIND:
                findings.append(f"{TABLE}: line {no}: the discriminator of {name} must be {INVITE_KIND[name]}")
            continue
        if not (op == GRANT_OP and svc == GRANT_SERVICE):
            findings.append(f"{TABLE}: line {no}: discriminator '{disc}' is allowed only on operationId grantRole of access")
            continue
        if kinds is not None and disc not in kinds:
            findings.append(f"{TABLE}: line {no}: discriminator '{disc}' is not a member of GrantRequest.granteeKind")
        elif name in INVITE_KIND and disc != INVITE_KIND[name]:
            findings.append(f"{TABLE}: line {no}: the discriminator of {name} must be {INVITE_KIND[name]}")
    for name in names:
        if name not in listed:
            findings.append(f"{TABLE}: audited operation '{name}' has no line")
    for name in names:
        for d, info in sorted(services.items()):
            if name in info["ids"] and (d, name) not in pairs:
                findings.append(f"{TABLE}: audited operation '{name}' is an operationId of {d}/openapi.yaml and has no line for it")
    if plain_grant != 1:
        findings.append(f"{TABLE}: grantRole must have exactly one line with discriminator -")
    return findings, len(lines) - 1


def main(argv: list[str]) -> int:
    args = argv[1:]
    names = AUDITED_NAMES
    if args[:1] == ["--names-file"]:
        if len(args) < 2:
            print("audited-operations: --names-file needs a file")
            return 1
        try:
            names = tuple(l for l in Path(args[1]).read_text().split("\n") if l)
        except (OSError, UnicodeError) as e:
            print(f"audited-operations: cannot read the names file: {type(e).__name__}")
            return 1
        args = args[2:]
    if len(args) > 1:
        print("audited-operations: usage: check-audited-operations.py [--names-file FILE] [ROOT]")
        return 1
    root = Path(args[0]) if args else Path(".")
    findings, count = check(root, names)
    if findings:
        print("gst-ci: audited-operations FAIL")
        for f in findings:
            print(f)
        return 1
    print(f"audited operations: {len(names)} names, {count} lines")
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))
