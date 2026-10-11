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

Replay topics (INV-T-3606, coordinator rulings INV-Q-3601, INV-Q-3625,
INV-Q-3644, INV-REQ-1205 b3, b4): derived, never hand-entered, from the
producer and subscriber lists of the event topics above — one topic per
(producer, subscriber) pair, `gst.<producer>.replay-<subscriber>.v1`
(service names without `-svc`; for the producer `every service`, the
namespace its event topics share, i.e. gst.platform.replay-<subscriber>.v1),
that producer's relay alone INSERTs and that subscriber alone SELECTs,
carrying the event types the subscriber subscribes to from that producer
plus the ReplayCompleted completion marker, and `content_topic` true when
any carried type is a content topic.

Request topics (owner ruling INV-Q-3599): one per subscriber of the event
topics, `gst.<consumer>.replay-requested.v1`, producers [that consumer],
subscribers every producer of a topic it subscribes to (`every service` is
one value), carrying ReplayRequested, content_topic false. ReplayRequested
and ReplayCompleted (INV-REQ-1205 b4) are the only type names held here.

Record topics (owner ruling INV-Q-3627): a message declaring
`x-record-topic: true` gives its topic `record_topic: true`; every other
topic (event, replay, request) gets `record_topic: false`.

Derived families render under their own keys (`replay_topics:`,
`replay_request_topics:`) so the event topics keep their shape; they are
listed in DERIVED_FAMILIES as (key, derive function, validate function).

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
# Message-level record-topic marker (INV-T-3606, owner ruling INV-Q-3627), same indent rule.
_RECORD_RE = re.compile(r"^      x-record-topic:[ \t]*(?P<value>.*?)[ \t]*$", re.MULTILINE)
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
        record_m = _RECORD_RE.search(body)
        record_value = record_m.group("value") if record_m else "false"
        if record_value not in ("true", "false"):
            raise SystemExit(
                f"gst-ci: {path}: message '{key}' has x-record-topic: '{record_value}'"
                " — must be true or false"
            )
        topics.append(
            {
                "topic": address,
                "event_type": name_m.group("name"),
                "producers": _split_list(ec_m.group("producers")),
                "subscribers": _split_list(ec_m.group("consumers")),
                "content_topic": content_value == "true",
                "record_topic": record_value == "true",
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


_SVC_SUFFIX = "-svc"

# The replay protocol's two type names, fixed by INV-REQ-1205 b4 (owner ruling
# INV-Q-3599, coordinator ruling INV-Q-3644); their schemas are INV-T-3634's.
# They are the only type names this generator holds.
REPLAY_REQUESTED_TYPE = "com.dynokore.gst.platform.ReplayRequested.v1"
REPLAY_COMPLETED_TYPE = "com.dynokore.gst.platform.ReplayCompleted.v1"


def short_service_name(service: str) -> str:
    """A service name as it appears in a topic address: without `-svc`
    (EventLogSchema topic form gst.<service>.<event-name>.v<N>)."""
    return service[: -len(_SVC_SUFFIX)] if service.endswith(_SVC_SUFFIX) else service


def producer_namespace(producer: str, topics: list[dict]) -> str:
    """The address segment a producer's replay topics use. For a service, its
    name without `-svc`; for `every service` (INV-Q-3625), the one namespace
    its event topics already share (read from their addresses, not named)."""
    if producer != EVERY_SERVICE:
        return short_service_name(producer)
    spaces = sorted({t["topic"].split(".")[1] for t in topics if EVERY_SERVICE in t["producers"]})
    if len(spaces) != 1:
        raise SystemExit(
            f"gst-ci: '{EVERY_SERVICE}' topics span namespaces {spaces} — need exactly one"
            " for their replay topics (INV-Q-3625)"
        )
    return spaces[0]


def replay_topic_address(namespace: str, subscriber: str) -> str:
    return f"gst.{namespace}.replay-{short_service_name(subscriber)}.v1"


def request_topic_address(consumer: str) -> str:
    return f"gst.{short_service_name(consumer)}.replay-requested.v1"


def replay_pairs(topics: list[dict]) -> dict[tuple[str, str], list[dict]]:
    """Every (producer, subscriber) pair of the event topics, with the event
    topics that pair shares; `every service` is one producer (INV-Q-3625)."""
    pairs: dict[tuple[str, str], list[dict]] = {}
    for t in topics:
        for producer in t["producers"]:
            for subscriber in t["subscribers"]:
                pairs.setdefault((producer, subscriber), []).append(t)
    return pairs


def producers_by_consumer(topics: list[dict]) -> dict[str, list[str]]:
    """Each subscriber of the event topics, with every producer of a topic it
    subscribes to (`every service` counting as one value, INV-Q-3599)."""
    out: dict[str, set[str]] = {}
    for t in topics:
        for subscriber in t["subscribers"]:
            out.setdefault(subscriber, set()).update(t["producers"])
    return {c: sorted(p) for c, p in out.items()}


def _entry(topic, event_types, producers, subscribers, content=False):
    return {"topic": topic, "event_types": event_types, "producers": producers,
            "subscribers": subscribers, "content_topic": content, "record_topic": False}


def derive_replay_topics(topics: list[dict]) -> list[dict]:
    """One replay topic per (producer, subscriber) pair (INV-Q-3601,
    INV-Q-3625), carrying that subscriber's types from that producer plus the
    completion marker (INV-Q-3644)."""
    replay = [
        _entry(
            replay_topic_address(producer_namespace(producer, topics), subscriber),
            sorted({t["event_type"] for t in carried} | {REPLAY_COMPLETED_TYPE}),
            [producer],
            [subscriber],
            any(t["content_topic"] for t in carried),
        )
        for (producer, subscriber), carried in replay_pairs(topics).items()
    ]
    return sorted(replay, key=lambda r: r["topic"])


def derive_request_topics(topics: list[dict]) -> list[dict]:
    """One request topic per consumer (owner ruling INV-Q-3599): the consumer's
    relay inserts ReplayRequested, every producer it subscribes to reads it."""
    request = [
        _entry(request_topic_address(consumer), [REPLAY_REQUESTED_TYPE], [consumer], producers)
        for consumer, producers in producers_by_consumer(topics).items()
    ]
    return sorted(request, key=lambda r: r["topic"])


def _check_one(r: dict, errors: list[str]) -> None:
    for field in ("producers", "subscribers"):
        if len(r[field]) != 1:
            errors.append(f"topic '{r['topic']}' has {len(r[field])} {field} — must have one")


def validate_replay_topics(topics: list[dict], replay: list[dict]) -> list[str]:
    """Returns one error per missing pair, extra topic, replay topic with
    other than one producer and one subscriber, carried type its subscriber
    does not subscribe to from that producer (or subscribed type missing),
    missing completion type, or content_topic disagreeing with the carried
    types (INV-Q-3601, INV-Q-3625, INV-Q-3644)."""
    errors: list[str] = []
    pairs = replay_pairs(topics)
    by_address = {replay_topic_address(producer_namespace(p, topics), s): (p, s) for p, s in pairs}
    content_by_type = {t["event_type"]: t["content_topic"] for t in topics}
    seen = set()
    for r in replay:
        name = r["topic"]
        _check_one(r, errors)
        pair = (r["producers"][0] if r["producers"] else None, r["subscribers"][0] if r["subscribers"] else None)
        if pair not in pairs or by_address.get(name) != pair:
            errors.append(f"replay topic '{name}' matches no (producer, subscriber) pair of the event topics")
            continue
        seen.add(pair)
        subscribed = {t["event_type"] for t in pairs[pair]}
        if REPLAY_COMPLETED_TYPE not in r["event_types"]:
            errors.append(f"replay topic '{name}' lacks the completion type {REPLAY_COMPLETED_TYPE}")
        for et in r["event_types"]:
            if et not in subscribed and et != REPLAY_COMPLETED_TYPE:
                errors.append(f"replay topic '{name}' carries {et}, which {pair[1]} does not subscribe to from {pair[0]}")
        for et in sorted(subscribed - set(r["event_types"])):
            errors.append(f"replay topic '{name}' lacks {et}, which {pair[1]} subscribes to from {pair[0]}")
        expected = any(content_by_type.get(et, False) for et in r["event_types"])
        if r["content_topic"] != expected:
            errors.append(f"replay topic '{name}' has content_topic {str(r['content_topic']).lower()} — its types make it {str(expected).lower()}")
    for p, s in sorted(set(pairs) - seen):
        errors.append(f"pair ({p}, {s}) lacks its replay topic {replay_topic_address(producer_namespace(p, topics), s)}")
    return errors


def validate_request_topics(topics: list[dict], request: list[dict]) -> list[str]:
    """Returns one error per consumer lacking its request topic, extra topic,
    request topic whose producers are not [its consumer], whose subscribers
    differ from its consumer's producers, or which carries anything but
    ReplayRequested or is a content topic (INV-Q-3599)."""
    errors: list[str] = []
    expected = producers_by_consumer(topics)
    by_address = {request_topic_address(c): c for c in expected}
    seen = set()
    for r in request:
        name = r["topic"]
        consumer = by_address.get(name)
        if consumer is None:
            errors.append(f"request topic '{name}' matches no consumer of the event topics")
            continue
        seen.add(consumer)
        if r["producers"] != [consumer]:
            errors.append(f"request topic '{name}' has producers {r['producers']} — must be [{consumer}]")
        if sorted(r["subscribers"]) != expected[consumer]:
            errors.append(f"request topic '{name}' has subscribers {sorted(r['subscribers'])} — {consumer}'s producers are {expected[consumer]}")
        if r["event_types"] != [REPLAY_REQUESTED_TYPE]:
            errors.append(f"request topic '{name}' carries {r['event_types']} — must carry only {REPLAY_REQUESTED_TYPE}")
        if r["content_topic"]:
            errors.append(f"request topic '{name}' has content_topic true — must be false")
    for c in sorted(set(expected) - seen):
        errors.append(f"consumer {c} lacks its request topic {request_topic_address(c)}")
    return errors


# Derived topic families: (topics.yaml key, derive, validate). A new family
# is one more entry.
DERIVED_FAMILIES = [
    ("replay_topics", derive_replay_topics, validate_replay_topics),
    ("replay_request_topics", derive_request_topics, validate_request_topics),
]


def derive_all(topics: list[dict]) -> dict[str, list[dict]]:
    derived = {key: derive(topics) for key, derive, _ in DERIVED_FAMILIES}
    names = [t["topic"] for t in topics] + [d["topic"] for ds in derived.values() for d in ds]
    dups = sorted({n for n in names if names.count(n) > 1})
    if dups:
        raise SystemExit(f"gst-ci: derived topic(s) collide with another topic: {', '.join(dups)}")
    return derived


def validate_derived(topics: list[dict], derived: dict[str, list[dict]]) -> list[str]:
    return [e for key, _, validate in DERIVED_FAMILIES for e in validate(topics, derived.get(key, []))]


def render_yaml(topics: list[dict], derived: dict[str, list[dict]] | None = None) -> str:
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
        "#",
        "# record_topic (INV-T-3606, owner ruling INV-Q-3627): true when the",
        "# message declares x-record-topic: true — the purge never removes such",
        "# a topic above evidence-svc's recorded position; false elsewhere.",
        "#",
        "# replay_topics (INV-T-3606, rulings INV-Q-3601, INV-Q-3625, INV-Q-3644):",
        "# one per (producer, subscriber) pair of the topics above, derived — the",
        "# producer's relay INSERTs its replay (INV-REQ-1205 b4) and only that",
        "# subscriber SELECTs; it carries the types the subscriber subscribes",
        "# to from that producer plus ReplayCompleted, and is a content topic",
        "# when any of them is.",
        "#",
        "# replay_request_topics (INV-T-3606, owner ruling INV-Q-3599): one per",
        "# consumer — its relay INSERTs ReplayRequested, every producer it",
        "# subscribes to SELECTs.",
        "topics:",
    ]
    for t in topics:
        lines.append(f"  - topic: {t['topic']}")
        lines.append(f"    event_type: {t['event_type']}")
        lines.append(f"    content_topic: {'true' if t['content_topic'] else 'false'}")
        lines.append(f"    record_topic: {'true' if t['record_topic'] else 'false'}")
        lines.append("    producers:")
        lines.append(_list_block("      ", t["producers"]).rstrip("\n"))
        lines.append("    subscribers:")
        lines.append(_list_block("      ", t["subscribers"]).rstrip("\n"))
    for key, entries in (derived or {}).items():
        lines.append(f"{key}:")
        if not entries:
            lines[-1] += " []"
        for r in entries:
            lines.append(f"  - topic: {r['topic']}")
            lines.append("    event_types:")
            lines.append(_list_block("      ", r["event_types"]).rstrip("\n"))
            lines.append(f"    content_topic: {'true' if r['content_topic'] else 'false'}")
            lines.append(f"    record_topic: {'true' if r['record_topic'] else 'false'}")
            lines.append("    producers:")
            lines.append(_list_block("      ", r["producers"]).rstrip("\n"))
            lines.append("    subscribers:")
            lines.append(_list_block("      ", r["subscribers"]).rstrip("\n"))
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
_RECORD_TOPIC_LINE_RE = re.compile(r"^    record_topic: (?P<value>\S+)$", re.MULTILINE)


def validate_topics_yaml(text: str) -> list[str]:
    """Returns one error per topic entry of a topics.yaml text that lacks
    `content_topic` or `record_topic`, carries one more than once, or carries
    a value other than true/false (INV-T-3386, INV-T-3606)."""
    errors = []
    entries = list(_TOPIC_ENTRY_RE.finditer(text))
    for i, m in enumerate(entries):
        end = entries[i + 1].start() if i + 1 < len(entries) else len(text)
        topic = m.group("topic")
        for field, line_re in (("content_topic", _CONTENT_TOPIC_LINE_RE), ("record_topic", _RECORD_TOPIC_LINE_RE)):
            values = [c.group("value") for c in line_re.finditer(text, m.end(), end)]
            if not values:
                errors.append(f"topic '{topic}' lacks {field}")
            elif len(values) > 1:
                errors.append(f"topic '{topic}' has {field} {len(values)} times")
            elif values[0] not in ("true", "false"):
                errors.append(f"topic '{topic}' has {field}: {values[0]} — must be true or false")
    return errors


def validate_record_topics(text: str, topics: list[dict]) -> list[str]:
    """Returns one error per topic entry whose record_topic disagrees with its
    message's x-record-topic marker; derived topics must be false (INV-Q-3627)."""
    marker = {t["topic"]: t["record_topic"] for t in topics}
    errors = []
    entries = list(_TOPIC_ENTRY_RE.finditer(text))
    for i, m in enumerate(entries):
        end = entries[i + 1].start() if i + 1 < len(entries) else len(text)
        value = _RECORD_TOPIC_LINE_RE.search(text, m.end(), end)
        if value is None or value.group("value") not in ("true", "false"):
            continue  # reported by validate_topics_yaml
        expected = marker.get(m.group("topic"), False)
        if (value.group("value") == "true") != expected:
            errors.append(
                f"topic '{m.group('topic')}' has record_topic {value.group('value')}"
                f" — its message's x-record-topic makes it {str(expected).lower()}"
            )
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
    derived = derive_all(topics)
    derived_errors = validate_derived(topics, derived)
    if derived_errors:
        print("gst-ci: derived topics fail their check (INV-Q-3601):")
        for e in derived_errors:
            print(f"  {e}")
        return 1
    rendered = render_yaml(topics, derived)

    if mode == "--write":
        target.write_text(rendered)
        n_derived = sum(len(d) for d in derived.values())
        print(f"gst-ci: wrote {len(topics)} topic(s) and {n_derived} derived topic(s) to {target}")
        return 0

    existing = target.read_text() if target.exists() else ""
    errors = validate_topics_yaml(existing) or validate_record_topics(existing, topics)
    if errors:
        print(f"gst-ci: {target} fails the content_topic/record_topic check (INV-T-3386, INV-T-3606):")
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
