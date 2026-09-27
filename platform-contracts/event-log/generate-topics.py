#!/usr/bin/env python3
"""event-log/generate-topics.py — INV-T-2855 (T11)

Generates `topics.yaml` — one entry per event-log topic, with its channel
address, event type, producer(s) and subscriber list — from every real
event message this repository's `*/asyncapi.yaml` files declare (INV-REQ-1205,
FR-AH.9: "each service's outbox is published by its own relay into topics in
an `eventlog` schema"; a topic per event type, one relay INSERT grant on the
producer's own topic, one consumer SELECT grant per subscriber).

Dependency-free (stdlib only, mirrors check-payload-compat.py and
submission/schemas/generate-lifecycle-states.py's style — no pip install in
CI, no general YAML parser): a real event's message declares its channel
address, its `name:` (the CloudEvents type), and a description that follows
INV-T-2853 (T9)'s own convention literally — "Emitted by: <comma-separated
services, or a descriptive phrase>. Consumed by: <comma-separated services,
or 'none'>." — the small, regular subset of `asyncapi.yaml` this parser
understands. A placeholder message (the INV-T-2849 scaffold's
`ExampleHappened`) carries no such description and is skipped.

Two modes:
    generate-topics.py <repo-root> --write <out.yaml>
        Regenerates topics.yaml from every */asyncapi.yaml under repo-root.
    generate-topics.py <repo-root> --check <existing.yaml>
        Regenerates in memory and compares byte-for-byte with the existing
        file. Exits 0 if they match (no drift), 1 and a message otherwise.
        This is the CI drift-check job's script.
"""
from __future__ import annotations

import re
import sys
from pathlib import Path

# A channel block: its own key, its address, and the message key its one
# entry `$ref`s (T9/T10's own convention: one message per channel).
_CHANNEL_RE = re.compile(
    r"^  \S+:\n"
    r"    address: '(?P<address>[^']+)'\n"
    r"    messages:\n"
    r"      (?P<msg_key>\S+):\n"
    r"        \$ref: '#/components/messages/(?P=msg_key)'",
    re.MULTILINE,
)

# A message block under components.messages: its key, its `name:` (event
# type), and everything up to the next same-indent key (or end of file) —
# searched separately for the "Emitted by: ... Consumed by: ..." sentence.
_MESSAGE_RE = re.compile(
    r"^    (?P<key>\S+):\n"
    r"(?P<body>(?:      .*\n?|\n)*)",
    re.MULTILINE,
)
_NAME_RE = re.compile(r"^      name: (?P<name>\S+)$", re.MULTILINE)
_EMITTED_CONSUMED_RE = re.compile(
    r"Emitted by: (?P<producers>.+?)\. Consumed by: (?P<consumers>.+?)\.",
    re.DOTALL,
)


def _split_list(text: str) -> list[str]:
    """Splits a comma-separated service list; 'none' becomes an empty list;
    a descriptive phrase with no commas (e.g. 'every service') stays as its
    own single-element list — still a list, per this task's done-when."""
    text = text.strip()
    if text.lower() == "none":
        return []
    return [part.strip() for part in text.split(",")]


def parse_asyncapi_file(path: Path) -> list[dict]:
    text = path.read_text()
    components_idx = text.find("\ncomponents:")
    if components_idx == -1:
        return []
    head, components_text = text[:components_idx], text[components_idx:]

    address_by_msg_key: dict[str, str] = {}
    for m in _CHANNEL_RE.finditer(head):
        address_by_msg_key[m.group("msg_key")] = m.group("address")

    messages_idx = components_text.find("  messages:\n")
    if messages_idx == -1:
        return []
    messages_text = components_text[messages_idx + len("  messages:\n"):]

    topics = []
    for m in _MESSAGE_RE.finditer(messages_text):
        key, body = m.group("key"), m.group("body")
        name_m = _NAME_RE.search(body)
        ec_m = _EMITTED_CONSUMED_RE.search(body.replace("\n", " "))
        if name_m is None or ec_m is None:
            continue  # a placeholder message (no catalogue description) — skip
        address = address_by_msg_key.get(key)
        if address is None:
            continue
        topics.append(
            {
                "topic": address,
                "event_type": name_m.group("name"),
                "producers": _split_list(ec_m.group("producers")),
                "subscribers": _split_list(ec_m.group("consumers")),
            }
        )
    return topics


def collect_topics(repo_root: Path) -> list[dict]:
    topics: list[dict] = []
    for asyncapi_file in sorted(repo_root.glob("*/asyncapi.yaml")):
        topics.extend(parse_asyncapi_file(asyncapi_file))
    topics.sort(key=lambda t: t["topic"])
    seen = set()
    for t in topics:
        if t["topic"] in seen:
            raise SystemExit(f"gst-ci: duplicate topic '{t['topic']}' across asyncapi.yaml files")
        seen.add(t["topic"])
    return topics


def render_yaml(topics: list[dict]) -> str:
    def _list_block(indent: str, items: list[str]) -> str:
        if not items:
            return f"{indent}[]\n"
        return "".join(f"{indent}- {item}\n" for item in items)

    lines = [
        "# event-log/topics.yaml — INV-T-2855 (T11)",
        "#",
        "# Generated by generate-topics.py from every */asyncapi.yaml in this",
        "# repository — never hand-edited (CI's event-log-topics-drift job",
        "# regenerates and diffs this file; a drift fails the pipeline).",
        "#",
        "# One entry per event-log topic (INV-REQ-1205, FR-AH.9): the channel",
        "# address every producer's relay INSERTs into, the CloudEvents type it",
        "# carries, the producer(s) whose relay holds that INSERT grant, and the",
        "# subscriber(s) whose consumer holds the matching SELECT grant — the",
        "# `eventlog` schema's only two grants (INV-REQ-1205's own text).",
        "topics:",
    ]
    for t in topics:
        lines.append(f"  - topic: {t['topic']}")
        lines.append(f"    event_type: {t['event_type']}")
        lines.append("    producers:")
        lines.append(_list_block("      ", t["producers"]).rstrip("\n"))
        lines.append("    subscribers:")
        lines.append(_list_block("      ", t["subscribers"]).rstrip("\n"))
    return "\n".join(lines) + "\n"


def main(argv: list[str]) -> int:
    if len(argv) != 4 or argv[2] not in ("--write", "--check"):
        print(__doc__)
        return 2
    repo_root = Path(argv[1])
    mode, target = argv[2], Path(argv[3])

    topics = collect_topics(repo_root)
    rendered = render_yaml(topics)

    if mode == "--write":
        target.write_text(rendered)
        print(f"gst-ci: wrote {len(topics)} topic(s) to {target}")
        return 0

    existing = target.read_text() if target.exists() else ""
    if existing != rendered:
        print(f"gst-ci: {target} is out of date with */asyncapi.yaml — regenerate it:")
        print(f"  python3 event-log/generate-topics.py . --write {target}")
        return 1
    print(f"gst-ci: {target} matches every */asyncapi.yaml — no drift ({len(topics)} topic(s)).")
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))
