#!/usr/bin/env python3
"""event-log/test_generate_topics.py — INV-T-3386, INV-T-3542, INV-T-3606

Tests generate-topics.py's content-topic marker (owner ruling INV-Q-3249,
INV-REQ-1205 b4): x-invoice-content is copied into topics.yaml as
content_topic on every topic, and the check fails when a topic lacks it.
INV-T-3606 (rulings INV-Q-3601, INV-Q-3625, INV-Q-3644, INV-Q-3599,
INV-Q-3627): one replay topic per (producer, subscriber) pair with the
completion type, one request topic per consumer, record_topic from the marker.
Stdlib only. Run: python3 -m unittest discover -s event-log -p 'test_*.py'
"""
from __future__ import annotations

import importlib.util
import io
import tempfile
import unittest
from contextlib import redirect_stdout
from pathlib import Path

_HERE = Path(__file__).resolve().parent
_spec = importlib.util.spec_from_file_location("generate_topics", _HERE / "generate-topics.py")
gen = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(gen)


def _asyncapi(messages: dict[str, str | None], producers: str = "a-svc") -> str:
    """A minimal asyncapi.yaml in the generator's subset: one channel per
    message; the value is the x-invoice-content line's value, or None to
    omit the line."""
    channels = ["channels:"]
    comps = ["components:", "  messages:"]
    for key, marker in messages.items():
        channels += [
            f"  ch{key}:",
            f"    address: 'gst.t.{key.lower()}.v1'",
            "    messages:",
            f"      {key}:",
            f"        $ref: '#/components/messages/{key}'",
        ]
        comps += [
            f"    {key}:",
            f"      name: com.example.{key}.v1",
            "      description: >-",
            f"        Emitted by: {producers}. Consumed by: b-svc.",
            "        x-invoice-content: true",  # folded text, indent 8: never a marker
        ]
        if marker is not None:
            comps.append(f"      x-invoice-content: {marker}")
        comps.append("      contentType: application/cloudevents+json")
        comps.append("")
    return "asyncapi: 3.0.0\n" + "\n".join(channels) + "\n" + "\n".join(comps) + "\n"


class ProducerCheckTest(unittest.TestCase):
    """INV-T-3542 (INV-Q-3539): a producer is a service name or exactly
    `every service`; any other wording fails --check (and --write)."""

    def _repo(self, producers: str) -> Path:
        tmp = Path(tempfile.mkdtemp())
        (tmp / "svc").mkdir()
        (tmp / "svc" / "asyncapi.yaml").write_text(_asyncapi({"Ev": None}, producers))
        return tmp

    def _run(self, *argv: str) -> int:
        with redirect_stdout(io.StringIO()):
            return gen.main(["generate-topics.py", *argv])

    def test_service_names_and_every_service_pass(self):
        for producers in ("a-svc", "a-svc, console-bff", "every service", "tax-code-svc"):
            repo = self._repo(producers)
            out = repo / "topics.yaml"
            self.assertEqual(self._run(str(repo), "--write", str(out)), 0, producers)
            self.assertEqual(self._run(str(repo), "--check", str(out)), 0, producers)

    def test_other_producer_wording_fails_check(self):
        good = self._repo("every service")
        out = good / "topics.yaml"
        self.assertEqual(self._run(str(good), "--write", str(out)), 0)
        for producers in (
            "every service with scheduled jobs",
            "every service owning a variation point",
            "Every service",
            "a-svc, every service with scheduled jobs",
            "notification",
        ):
            bad = self._repo(producers)
            (bad / "topics.yaml").write_text(out.read_text())
            self.assertEqual(self._run(str(bad), "--check", str(bad / "topics.yaml")), 1, producers)
            self.assertEqual(len(gen.validate_producers(gen.collect_topics(bad))), 1, producers)

    def test_committed_catalogue_producers_pass(self):
        self.assertEqual(gen.validate_producers(gen.collect_topics(_HERE.parent)), [])


class ContentTopicTest(unittest.TestCase):
    def _repo(self, messages: dict[str, str | None]) -> Path:
        tmp = Path(tempfile.mkdtemp())
        (tmp / "svc").mkdir()
        (tmp / "svc" / "asyncapi.yaml").write_text(_asyncapi(messages))
        return tmp

    def _run(self, *argv: str) -> int:
        with redirect_stdout(io.StringIO()):
            return gen.main(["generate-topics.py", *argv])

    def test_marker_is_copied_and_absent_means_false(self):
        repo = self._repo({"Content": "true", "Plain": None, "Explicit": "false"})
        by_topic = {t["topic"]: t["content_topic"] for t in gen.collect_topics(repo)}
        self.assertEqual(
            by_topic,
            {"gst.t.content.v1": True, "gst.t.plain.v1": False, "gst.t.explicit.v1": False},
        )
        rendered = gen.render_yaml(gen.collect_topics(repo))
        self.assertEqual(rendered.count("    content_topic: "), 3)
        self.assertEqual(gen.validate_topics_yaml(rendered), [])

    def test_invalid_marker_value_fails(self):
        repo = self._repo({"Bad": "yes"})
        with self.assertRaises(SystemExit):
            gen.collect_topics(repo)

    def test_check_fails_when_a_topic_lacks_content_topic(self):
        repo = self._repo({"Content": "true", "Plain": None})
        out = repo / "topics.yaml"
        self.assertEqual(self._run(str(repo), "--write", str(out)), 0)
        self.assertEqual(self._run(str(repo), "--check", str(out)), 0)
        stripped = out.read_text().replace("    content_topic: false\n", "", 1)
        self.assertEqual(gen.validate_topics_yaml(stripped), ["topic 'gst.t.plain.v1' lacks content_topic"])
        out.write_text(stripped)
        self.assertEqual(self._run(str(repo), "--check", str(out)), 1)

    def test_check_rejects_non_boolean_content_topic(self):
        text = "topics:\n  - topic: gst.x.v1\n    event_type: x\n    content_topic: maybe\n    record_topic: false\n"
        self.assertEqual(len(gen.validate_topics_yaml(text)), 1)

    def test_committed_topics_yaml_has_content_topic_on_every_topic(self):
        text = (_HERE / "topics.yaml").read_text()
        self.assertEqual(gen.validate_topics_yaml(text), [])
        self.assertEqual(text.count("  - topic: "), text.count("    content_topic: "))

    def test_generator_names_no_topic_or_event(self):
        src = (_HERE / "generate-topics.py").read_text()
        for name in ("CanonicalVersionCreated", "PayloadReady", "canonical-version-created", "payload-ready"):
            self.assertNotIn(name, src)


def _topic(name, et, producers, subscribers, content=False, record=False):
    return {"topic": name, "event_type": et, "producers": producers,
            "subscribers": subscribers, "content_topic": content, "record_topic": record}


DONE = gen.REPLAY_COMPLETED_TYPE
REQ = gen.REPLAY_REQUESTED_TYPE


class ReplayTopicTest(unittest.TestCase):
    """INV-T-3606 (INV-Q-3601, INV-Q-3625, INV-Q-3644, INV-REQ-1205 b3, b4)."""

    TOPICS = [
        _topic("gst.a.x.v1", "X", ["a-svc"], ["b-svc", "console-bff"], content=True),
        _topic("gst.a.y.v1", "Y", ["a-svc"], ["b-svc"]),
        _topic("gst.platform.z.v1", "Z", ["a-svc", "c-svc"], ["b-svc"]),
        _topic("gst.platform.w.v1", "W", [gen.EVERY_SERVICE], ["b-svc"]),
        _topic("gst.c.none.v1", "N", ["c-svc"], []),
    ]

    def _derived(self):
        return gen.derive_replay_topics(self.TOPICS)

    def test_one_topic_per_pair_with_its_types_and_content(self):
        by = {r["topic"]: r for r in self._derived()}
        self.assertEqual(sorted(by), [
            "gst.a.replay-b.v1", "gst.a.replay-console-bff.v1", "gst.c.replay-b.v1",
            "gst.platform.replay-b.v1"])
        self.assertEqual(by["gst.a.replay-b.v1"]["event_types"], sorted(["X", "Y", "Z", DONE]))
        self.assertTrue(by["gst.a.replay-b.v1"]["content_topic"])
        self.assertEqual(by["gst.a.replay-console-bff.v1"]["event_types"], sorted(["X", DONE]))
        self.assertEqual(by["gst.c.replay-b.v1"]["event_types"], sorted(["Z", DONE]))
        self.assertFalse(by["gst.c.replay-b.v1"]["content_topic"])
        self.assertEqual(by["gst.platform.replay-b.v1"]["producers"], [gen.EVERY_SERVICE])
        self.assertEqual(by["gst.platform.replay-b.v1"]["event_types"], sorted(["W", DONE]))
        for r in by.values():
            self.assertEqual((len(r["producers"]), len(r["subscribers"])), (1, 1))
            self.assertFalse(r["record_topic"])
        self.assertEqual(gen.validate_replay_topics(self.TOPICS, self._derived()), [])

    def _errors_after(self, mutate):
        replay = self._derived()
        mutate({r["topic"]: r for r in replay}, replay)
        return gen.validate_replay_topics(self.TOPICS, replay)

    def test_missing_pair_fails(self):
        errs = self._errors_after(lambda by, l: l.remove(by["gst.c.replay-b.v1"]))
        self.assertEqual(errs, ["pair (c-svc, b-svc) lacks its replay topic gst.c.replay-b.v1"])
        errs = self._errors_after(lambda by, l: l.remove(by["gst.platform.replay-b.v1"]))
        self.assertEqual(errs, ["pair (every service, b-svc) lacks its replay topic gst.platform.replay-b.v1"])

    def test_second_subscriber_fails(self):
        errs = self._errors_after(lambda by, l: by["gst.a.replay-b.v1"]["subscribers"].append("console-bff"))
        self.assertTrue(any("2 subscribers" in e for e in errs), errs)

    def test_second_producer_fails(self):
        errs = self._errors_after(lambda by, l: by["gst.c.replay-b.v1"]["producers"].append("a-svc"))
        self.assertTrue(any("2 producers" in e for e in errs), errs)
        errs = self._errors_after(lambda by, l: by["gst.platform.replay-b.v1"]["producers"].append("a-svc"))
        self.assertTrue(any("2 producers" in e for e in errs), errs)

    def test_unsubscribed_type_fails(self):
        errs = self._errors_after(lambda by, l: by["gst.a.replay-console-bff.v1"]["event_types"].append("Y"))
        self.assertTrue(any("carries Y" in e for e in errs), errs)

    def test_missing_completion_type_fails(self):
        errs = self._errors_after(lambda by, l: by["gst.c.replay-b.v1"]["event_types"].remove(DONE))
        self.assertEqual(errs, [f"replay topic 'gst.c.replay-b.v1' lacks the completion type {DONE}"])

    def test_content_topic_disagreeing_fails(self):
        for name, value in (("gst.a.replay-b.v1", False), ("gst.c.replay-b.v1", True)):
            errs = self._errors_after(lambda by, l: by[name].update(content_topic=value))
            self.assertTrue(any("content_topic" in e for e in errs), (name, errs))

    def test_every_service_namespace_must_be_single(self):
        topics = self.TOPICS + [_topic("gst.other.v.v1", "V", [gen.EVERY_SERVICE], ["b-svc"])]
        with self.assertRaises(SystemExit):
            gen.derive_replay_topics(topics)


class RequestTopicTest(unittest.TestCase):
    """INV-T-3606 (owner ruling INV-Q-3599)."""

    TOPICS = ReplayTopicTest.TOPICS

    def _errors_after(self, mutate):
        request = gen.derive_request_topics(self.TOPICS)
        mutate({r["topic"]: r for r in request}, request)
        return gen.validate_request_topics(self.TOPICS, request)

    def test_one_request_topic_per_consumer(self):
        by = {r["topic"]: r for r in gen.derive_request_topics(self.TOPICS)}
        self.assertEqual(sorted(by), ["gst.b.replay-requested.v1", "gst.console-bff.replay-requested.v1"])
        b = by["gst.b.replay-requested.v1"]
        self.assertEqual(b["producers"], ["b-svc"])
        self.assertEqual(b["subscribers"], ["a-svc", "c-svc", gen.EVERY_SERVICE])
        self.assertEqual(b["event_types"], [REQ])
        self.assertFalse(b["content_topic"])
        self.assertFalse(b["record_topic"])
        self.assertEqual(by["gst.console-bff.replay-requested.v1"]["subscribers"], ["a-svc"])
        self.assertEqual(self._errors_after(lambda by, l: None), [])

    def test_missing_request_topic_fails(self):
        errs = self._errors_after(lambda by, l: l.remove(by["gst.console-bff.replay-requested.v1"]))
        self.assertEqual(errs, ["consumer console-bff lacks its request topic gst.console-bff.replay-requested.v1"])

    def test_subscribers_differing_from_producers_fail(self):
        for change in (lambda s: s.remove("c-svc"), lambda s: s.append("d-svc")):
            errs = self._errors_after(lambda by, l: change(by["gst.b.replay-requested.v1"]["subscribers"]))
            self.assertTrue(any("subscribers" in e for e in errs), errs)


class RecordTopicTest(unittest.TestCase):
    """INV-T-3606 (owner ruling INV-Q-3627)."""

    def _repo(self, record: str | None) -> Path:
        tmp = Path(tempfile.mkdtemp())
        (tmp / "svc").mkdir()
        text = _asyncapi({"Rec": None, "Plain": None})
        if record is not None:
            text = text.replace("      name: com.example.Rec.v1\n",
                                f"      name: com.example.Rec.v1\n      x-record-topic: {record}\n")
        (tmp / "svc" / "asyncapi.yaml").write_text(text)
        return tmp

    def _run(self, *argv: str) -> int:
        with redirect_stdout(io.StringIO()):
            return gen.main(["generate-topics.py", *argv])

    def test_marker_is_copied_and_derived_topics_are_false(self):
        repo = self._repo("true")
        by = {t["topic"]: t["record_topic"] for t in gen.collect_topics(repo)}
        self.assertEqual(by, {"gst.t.rec.v1": True, "gst.t.plain.v1": False})
        out = repo / "topics.yaml"
        self.assertEqual(self._run(str(repo), "--write", str(out)), 0)
        text = out.read_text()
        self.assertEqual(text.count("    record_topic: true\n"), 1)
        self.assertEqual(text.count("  - topic: "), text.count("    record_topic: "))
        self.assertEqual(self._run(str(repo), "--check", str(out)), 0)

    def test_record_topic_disagreeing_with_marker_fails(self):
        repo = self._repo("true")
        topics = gen.collect_topics(repo)
        out = repo / "topics.yaml"
        self.assertEqual(self._run(str(repo), "--write", str(out)), 0)
        flipped = out.read_text().replace("    record_topic: true\n", "    record_topic: false\n")
        self.assertEqual(gen.validate_record_topics(flipped, topics),
                         ["topic 'gst.t.rec.v1' has record_topic false — its message's x-record-topic makes it true"])
        out.write_text(flipped)
        self.assertEqual(self._run(str(repo), "--check", str(out)), 1)
        text = out.read_text().replace("    record_topic: false\n", "    record_topic: true\n")
        self.assertTrue(any("gst.a.replay-b.v1" in e for e in gen.validate_record_topics(text, topics)))

    def test_invalid_marker_value_fails(self):
        with self.assertRaises(SystemExit):
            gen.collect_topics(self._repo("yes"))

    def test_committed_record_topics(self):
        topics = gen.collect_topics(_HERE.parent)
        text = (_HERE / "topics.yaml").read_text()
        self.assertEqual(gen.validate_record_topics(text, topics), [])
        self.assertEqual(sorted(t["event_type"].rsplit(".", 2)[-2] for t in topics if t["record_topic"]),
                         ["AuditEntryRecorded", "DecisionTraceRecorded"])


class CommittedCatalogueTest(unittest.TestCase):
    def test_committed_catalogue_has_every_derived_topic(self):
        topics = gen.collect_topics(_HERE.parent)
        derived = gen.derive_all(topics)
        self.assertEqual(gen.validate_derived(topics, derived), [])
        pairs = {(p, s) for t in topics for p in t["producers"] for s in t["subscribers"]}
        self.assertEqual(len(derived["replay_topics"]), len(pairs))
        consumers = {s for t in topics for s in t["subscribers"]}
        self.assertEqual(len(derived["replay_request_topics"]), len(consumers))
        text = (_HERE / "topics.yaml").read_text()
        for ds in derived.values():
            for r in ds:
                self.assertIn(f"  - topic: {r['topic']}\n", text)

    def test_check_fails_on_hand_edited_replay_topic(self):
        repo = Path(tempfile.mkdtemp())
        (repo / "svc").mkdir()
        (repo / "svc" / "asyncapi.yaml").write_text(_asyncapi({"Ev": "true"}))
        out = repo / "topics.yaml"
        with redirect_stdout(io.StringIO()):
            self.assertEqual(gen.main(["g", str(repo), "--write", str(out)]), 0)
            self.assertIn("  - topic: gst.a.replay-b.v1\n", out.read_text())
            out.write_text(out.read_text().replace("    content_topic: true\n", "    content_topic: false\n"))
            self.assertEqual(gen.main(["g", str(repo), "--check", str(out)]), 1)


if __name__ == "__main__":
    unittest.main()
