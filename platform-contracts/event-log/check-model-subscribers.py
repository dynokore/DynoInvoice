#!/usr/bin/env python3
"""event-log/check-model-subscribers.py — INV-T-13621 (INV-Q-9000 A, INV-AC-13637)

Usage: python3 event-log/check-model-subscribers.py <model-architecture-dir> <contracts-root>
       python3 event-log/check-model-subscribers.py --pin event-log/model-pin.txt --docs-repo <invoice-docs clone> <contracts-root>

Pinned mode (INV-T-16628, INV-Q-16624 D): events.yaml and services.yaml are read
by `git show <pin>:model/architecture/<file>` at the commit named in the pin
file, which must be an ancestor of origin/main of the clone; the rules are the
same. The scheduled job runs the first form against the head of invoice-docs main.

Compares the architecture model (events.yaml, services.yaml of invoice-docs)
with event-log/topics.yaml: every consumer of a model event is a subscriber of
its topic, every producer a producer of it, and the replica feeds (the events
whose model flag feeds_runtime_replicas is true) are subscribed by every
service except their producers. Names are compared by string equality. One
line per finding; exit 1 on any finding (unreadable or malformed input, a
missing key and a duplicate event id are findings), exit 0 otherwise.

Order of work: a contract that is ahead of the model is accepted, a model that
is ahead of the contract is rejected.
"""
from __future__ import annotations

import re
import subprocess
import sys
import tempfile
from pathlib import Path

import yaml

# Exemptions are exact and shrink-only; the unit test asserts them by equality.
MODEL_ONLY = frozenset({"ReplayRequested", "ReplayCompleted"})
EXEMPT_TOPICS = frozenset({"gst.access.authority-changed.v1"})
EXEMPT_TYPES: frozenset[str] = frozenset()
# Old topic kept beside its replacement during a deprecation window (INV-Q-16626 A, expand-contract). Exact
# names, old topic to replacement; shrink-only: the pair is deleted with its old topic, and a pair whose old
# topic or replacement is missing from topics.yaml is a finding.
SUPERSEDED_TOPICS = {
    "gst.tenancy.installation-status-changed.v1": "gst.platform.installation-status-changed.v1",
}
# Address rule (INV-T-17778): the topic address is derived from the event type; no exemption.
EVENT_TYPE_RE = re.compile(r"^com\.dynokore\.gst\.([a-z][a-z0-9]*)\.([A-Z][A-Za-z0-9]*)\.v([0-9]+)$")
NON_EVENT_SERVICES = frozenset({"console-web"})
EVERY_SERVICE = "every service"
PRODUCER_ALIASES = frozenset({
    "every service",
    "every service with scheduled jobs",
    "every service owning a variation point",
})


def _load(path: Path, findings: list[str]):
    try:
        with open(path, encoding="utf-8") as f:
            return yaml.safe_load(f)
    except (OSError, UnicodeError, yaml.YAMLError) as e:
        findings.append(f"{path.name}: unreadable or malformed ({type(e).__name__})")
        return None


def _str_list(value, what: str, findings: list[str]):
    if not isinstance(value, list) or not all(isinstance(v, str) for v in value):
        findings.append(f"{what}: not a list of strings")
        return None
    return value


def _entries(doc, key: str, fname: str, findings: list[str]) -> list[dict]:
    if not isinstance(doc, dict) or not isinstance(doc.get(key), list):
        findings.append(f"{fname}: missing or malformed key {key}")
        return []
    out = []
    for i, e in enumerate(doc[key]):
        if isinstance(e, dict):
            out.append(e)
        else:
            findings.append(f"{fname}: {key}[{i}] is not a mapping")
    return out


def check(model_dir: Path, root: Path) -> tuple[list[str], list[str]]:
    """Returns (findings, feed ids sorted)."""
    f: list[str] = []
    events_doc = _load(model_dir / "events.yaml", f)
    services_doc = _load(model_dir / "services.yaml", f)
    topics_doc = _load(root / "event-log" / "topics.yaml", f)
    if f:
        return f, []
    events = _entries(events_doc, "events", "events.yaml", f)
    services = _entries(services_doc, "services", "services.yaml", f)
    topics = _entries(topics_doc, "topics", "topics.yaml", f)

    universe: set[str] = set()
    for s in services:
        if isinstance(s.get("id"), str):
            universe.add(s["id"])
        else:
            f.append("services.yaml: a service has no string id")
    universe -= NON_EVENT_SERVICES

    by_id: dict[str, dict] = {}
    for e in events:
        eid = e.get("id")
        if not isinstance(eid, str):
            f.append("events.yaml: an event has no string id")
        elif eid in by_id:
            f.append(f"{eid}: duplicate event id in events.yaml")
        else:
            by_id[eid] = e

    # topics: (name, type, producers, subscribers)
    tinfo: dict[str, dict] = {}
    for t in topics:
        name, etype = t.get("topic"), t.get("event_type")
        if not isinstance(name, str) or not isinstance(etype, str) or len(etype.split(".")) < 2:
            f.append("topics.yaml: a topic entry has no string topic or event_type")
            continue
        if name in tinfo:
            f.append(f"topic {name}: duplicate topic in topics.yaml")
            continue
        m = EVENT_TYPE_RE.fullmatch(etype)
        if m is None:
            f.append(f"topic {name}: event type {etype} is malformed")
        else:
            kebab = re.sub(r"(?<!^)(?=[A-Z])", "-", m.group(2)).lower()
            expected = f"gst.{m.group(1)}.{kebab}.v{m.group(3)}"
            if name != expected:
                f.append(f"topic {name}: address differs from the address derived from event type {etype} (expected {expected})")
        prod = _str_list(t.get("producers"), f"topic {name}: producers", f)
        subs = _str_list(t.get("subscribers"), f"topic {name}: subscribers", f)
        tinfo[name] = {"type": etype.split(".")[-2], "producers": prod, "subscribers": subs}

    types_of: dict[str, list[str]] = {}
    for name, ti in tinfo.items():
        if name not in EXEMPT_TOPICS and name not in SUPERSEDED_TOPICS:
            types_of.setdefault(ti["type"], []).append(name)

    feeds: set[str] = set()
    for eid, e in by_id.items():
        flag = e.get("feeds_runtime_replicas")
        if flag is True:
            feeds.add(eid)
        elif "feeds_runtime_replicas" in e and flag is not False:
            f.append(f"{eid}: feeds_runtime_replicas is not a boolean")

    for eid, e in by_id.items():
        if eid in MODEL_ONLY:
            continue
        names = types_of.get(eid, [])
        if not names:
            f.append(f"{eid}: no topic carries the event type")
            continue
        if len(names) > 1:
            f.append(f"{eid}: more than one topic carries the event type: [{', '.join(sorted(names))}]")
            continue
        topic = names[0]
        ti = tinfo[topic]
        consumers = _str_list(e.get("consumers"), f"{eid}: consumers", f)
        producers = _str_list(e.get("producers"), f"{eid}: producers", f)
        subs = ti["subscribers"]
        tprod = ti["producers"]
        if consumers is not None and subs is not None:
            for c in consumers:
                if c not in subs:
                    f.append(f"{eid}: consumer {c} is not a subscriber of {topic}")
        if producers is not None and tprod is not None:
            for p in producers:
                name = EVERY_SERVICE if p in PRODUCER_ALIASES else p
                if name not in tprod:
                    f.append(f"{eid}: producer {name} is not a producer of {topic}")
        if producers is None or subs is None:
            continue
        derived = universe - {p for p in producers if p in universe}
        if eid in feeds:
            if set(subs) != derived:
                f.append(
                    f"{eid}: subscribers of {topic} differ from the derived list: "
                    f"missing [{', '.join(sorted(derived - set(subs)))}], "
                    f"extra [{', '.join(sorted(set(subs) - derived))}]")
        elif set(subs) == derived:
            f.append(f"{eid}: feeds_runtime_replicas is not true but the subscribers of {topic} cover every service")

    for name, ti in sorted(tinfo.items()):
        if name in EXEMPT_TOPICS:
            continue
        if ti["type"] not in by_id and ti["type"] not in EXEMPT_TYPES:
            f.append(f"topic {name}: event type {ti['type']} has no event in events.yaml")

    all_types = {ti["type"] for ti in tinfo.values()}
    for name in sorted(EXEMPT_TOPICS):
        if name not in tinfo:
            f.append(f"exemption {name} is stale")
    for name in sorted(EXEMPT_TYPES):
        if name in by_id or name not in all_types:
            f.append(f"exemption {name} is stale")
    for old, new in sorted(SUPERSEDED_TOPICS.items()):
        if old not in tinfo or new not in tinfo:
            f.append(f"superseded topic {old} is stale")
    for name in sorted(MODEL_ONLY):
        if name not in by_id or name in types_of:
            f.append(f"exemption {name} is stale")
    return f, sorted(feeds)


PIN_RE = re.compile(r"^[0-9a-f]{40}\n$")
PIN_FILES = ("events.yaml", "services.yaml")


def _git(repo: Path, *args: str) -> subprocess.CompletedProcess:
    return subprocess.run(["git", "-C", str(repo), *args], capture_output=True, check=False)


def run_pinned(pin_file: Path, docs_repo: Path, root: Path) -> int:
    """Pinned mode: fail closed with one finding line, exit 1; otherwise the unchanged rules."""
    shown = "event-log/model-pin.txt"
    try:
        raw = pin_file.read_bytes().decode("utf-8")
    except FileNotFoundError:
        print(f"model-pin: {shown} is missing")
        return 1
    except (OSError, UnicodeError):
        print(f"model-pin: {shown} is malformed")
        return 1
    if not PIN_RE.fullmatch(raw):
        print(f"model-pin: {shown} is malformed")
        return 1
    sha = raw[:40]
    if _git(docs_repo, "cat-file", "-e", f"{sha}^{{commit}}").returncode != 0:
        print(f"model-pin: commit {sha} cannot be read in the invoice-docs clone")
        return 1
    if _git(docs_repo, "merge-base", "--is-ancestor", sha, "origin/main").returncode != 0:
        print(f"model-pin: commit {sha} is not an ancestor of invoice-docs main")
        return 1
    head = _git(docs_repo, "rev-parse", "--short", "origin/main").stdout.decode().strip()
    print(f"model pin: {sha} (invoice-docs main {head})")
    with tempfile.TemporaryDirectory() as tmp:
        for name in PIN_FILES:
            r = _git(docs_repo, "show", f"{sha}:model/architecture/{name}")
            if r.returncode != 0:
                print(f"model-pin: {name} cannot be read at commit {sha}")
                return 1
            (Path(tmp) / name).write_bytes(r.stdout)
        findings, feeds = check(Path(tmp), root)
    print(f"replica feeds: {', '.join(feeds)}")
    for line in findings:
        print(line)
    return 1 if findings else 0


def main(argv: list[str]) -> int:
    if len(argv) == 6 and argv[1] == "--pin" and argv[3] == "--docs-repo":
        return run_pinned(Path(argv[2]), Path(argv[4]), Path(argv[5]))
    if len(argv) != 3:
        print("usage: check-model-subscribers.py <model-architecture-dir> <contracts-root>")
        return 2
    findings, feeds = check(Path(argv[1]), Path(argv[2]))
    print(f"replica feeds: {', '.join(feeds)}")
    for line in findings:
        print(line)
    return 1 if findings else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
