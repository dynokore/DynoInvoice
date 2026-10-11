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

Content topics (INV-T-3386, owner ruling INV-Q-3249, INV-REQ-1205 b4): a
message whose events carry invoice content (FR-AH.12) declares
`x-invoice-content: true` at message level in its asyncapi.yaml; every topic
in topics.yaml gets `content_topic: true|false` copied from that marker
(absent = false; any value other than true/false is an error). The generator
names no topic or event: the eventlog purge picks the content window
(INV-ADR-2772) or the event-log retention from this attribute alone.
--check also validates the committed file itself: every topic entry must
carry `content_topic: true` or `content_topic: false`, or the check fails
naming the topic (test_generate_topics.py covers this and the copying).

Producers (INV-T-3542, coordinator ruling INV-Q-3539, INV-REQ-815 b4): every
producer value must be a service name (`<name>-svc` or `console-bff`) or
exactly `every service` — the wording for a platform event service-runtime
emits in any service. Any other phrase fails both modes, naming the topic.

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
# Message-level content marker (6-space indent, so text inside a folded
# `description: >-` block, indented 8, never matches).
_CONTENT_RE = re.compile(r"^      x-invoice-content:[ \t]*(?P<value>.*?)[ \t]*$", re.MULTILINE)
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
        content_m = _CONTENT_RE.search(body)
        content_value = content_m.group("value") if content_m else "false"
        if content_value not in ("true", "false"):
            raise SystemExit(
                f"gst-ci: {path}: message '{key}' has x-invoice-content: '{content_value}'"
                " — must be true or false"
            )
        topics.append(
            {
                "topic": address,
                "event_type": name_m.group("name"),
                "producers": _split_list(ec_m.group("producers")),
                "subscribers": _split_list(ec_m.group("consumers")),
                "content_topic": content_value == "true",
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
        "#",
        "# content_topic (INV-T-3386, owner ruling INV-Q-3249): true when the",
        "# message declares x-invoice-content: true (its events carry invoice",
        "# content, FR-AH.12) — the purge keeps such a topic for the content",
        "# window (INV-ADR-2772), every other topic for the event-log retention.",
        "topics:",
    ]
    for t in topics:
        lines.append(f"  - topic: {t['topic']}")
        lines.append(f"    event_type: {t['event_type']}")
        lines.append(f"    content_topic: {'true' if t['content_topic'] else 'false'}")
        lines.append("    producers:")
        lines.append(_list_block("      ", t["producers"]).rstrip("\n"))
        lines.append("    subscribers:")
        lines.append(_list_block("      ", t["subscribers"]).rstrip("\n"))
    return "\n".join(lines) + "\n"


# A producer is a service name or exactly `every service` — the catalogue's
# wording for a platform event that service-runtime emits in any service
# (INV-REQ-815 b4, coordinator ruling INV-Q-3539). Any other phrase fails.
_SERVICE_NAME_RE = re.compile(r"^(?:[a-z][a-z0-9]*(?:-[a-z0-9]+)*-svc|console-bff)$")
EVERY_SERVICE = "every service"


def validate_producers(topics: list[dict]) -> list[str]:
    """Returns one error per producer value that is neither a service name
    (`<name>-svc` or `console-bff`) nor exactly `every service` (INV-Q-3539)."""
    errors = []
    for t in topics:
        for producer in t["producers"]:
            if producer != EVERY_SERVICE and not _SERVICE_NAME_RE.match(producer):
                errors.append(
                    f"topic '{t['topic']}' has producer '{producer}' — must be a service name"
                    f" (<name>-svc or console-bff) or exactly '{EVERY_SERVICE}'"
                )
    return errors


_TOPIC_ENTRY_RE = re.compile(r"^  - topic: (?P<topic>\S+)$", re.MULTILINE)
_CONTENT_TOPIC_LINE_RE = re.compile(r"^    content_topic: (?P<value>\S+)$", re.MULTILINE)


def validate_topics_yaml(text: str) -> list[str]:
    """Returns one error per topic entry of a topics.yaml text that lacks
    `content_topic`, carries it more than once, or carries a value other
    than true/false (INV-T-3386)."""
    errors = []
    entries = list(_TOPIC_ENTRY_RE.finditer(text))
    for i, m in enumerate(entries):
        end = entries[i + 1].start() if i + 1 < len(entries) else len(text)
        values = [c.group("value") for c in _CONTENT_TOPIC_LINE_RE.finditer(text, m.end(), end)]
        topic = m.group("topic")
        if not values:
            errors.append(f"topic '{topic}' lacks content_topic")
        elif len(values) > 1:
            errors.append(f"topic '{topic}' has content_topic {len(values)} times")
        elif values[0] not in ("true", "false"):
            errors.append(f"topic '{topic}' has content_topic: {values[0]} — must be true or false")
    return errors


def main(argv: list[str]) -> int:
    if len(argv) != 4 or argv[2] not in ("--write", "--check"):
        print(__doc__)
        return 2
    repo_root = Path(argv[1])
    mode, target = argv[2], Path(argv[3])

    topics = collect_topics(repo_root)
    producer_errors = validate_producers(topics)
    if producer_errors:
        print("gst-ci: */asyncapi.yaml fails the producer check (INV-Q-3539):")
        for e in producer_errors:
            print(f"  {e}")
        return 1
    rendered = render_yaml(topics)

    if mode == "--write":
        target.write_text(rendered)
        print(f"gst-ci: wrote {len(topics)} topic(s) to {target}")
        return 0

    existing = target.read_text() if target.exists() else ""
    errors = validate_topics_yaml(existing)
    if errors:
        print(f"gst-ci: {target} fails the content_topic check (INV-T-3386):")
        for e in errors:
            print(f"  {e}")
        return 1
    if existing != rendered:
        print(f"gst-ci: {target} is out of date with */asyncapi.yaml — regenerate it:")
        print(f"  python3 event-log/generate-topics.py . --write {target}")
        return 1
    print(f"gst-ci: {target} matches every */asyncapi.yaml — no drift ({len(topics)} topic(s)).")
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))
