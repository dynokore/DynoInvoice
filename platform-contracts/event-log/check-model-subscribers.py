#!/usr/bin/env python3
"""event-log/check-model-subscribers.py — INV-T-13621 (INV-Q-9000 A, INV-AC-13637)

Usage: python3 event-log/check-model-subscribers.py <model-architecture-dir> <contracts-root>

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

import sys
from pathlib import Path

import yaml

# Exemptions are exact and shrink-only; the unit test asserts them by equality.
MODEL_ONLY = frozenset({"ReplayRequested", "ReplayCompleted"})
EXEMPT_TOPICS = frozenset({"gst.access.authority-changed.v1"})
EXEMPT_TYPES: frozenset[str] = frozenset()
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
        prod = _str_list(t.get("producers"), f"topic {name}: producers", f)
        subs = _str_list(t.get("subscribers"), f"topic {name}: subscribers", f)
        tinfo[name] = {"type": etype.split(".")[-2], "producers": prod, "subscribers": subs}

    types_of: dict[str, list[str]] = {}
    for name, ti in tinfo.items():
        if name not in EXEMPT_TOPICS:
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
    for name in sorted(MODEL_ONLY):
        if name not in by_id or name in types_of:
            f.append(f"exemption {name} is stale")
    return f, sorted(feeds)


def main(argv: list[str]) -> int:
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
