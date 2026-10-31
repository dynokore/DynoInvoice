#!/usr/bin/env python3
"""event-log/test_check_model_subscribers.py — INV-T-13621 (INV-Q-9000 A, INV-AC-13637)

Tests check-model-subscribers.py on small fixture files written to a
temporary directory. Stdlib and yaml only.
Run: python3 -m unittest discover -s event-log -p 'test_*.py'
"""
from __future__ import annotations

import copy
import importlib.util
import io
import tempfile
import unittest
from contextlib import redirect_stdout
from pathlib import Path

import yaml

_HERE = Path(__file__).resolve().parent
_spec = importlib.util.spec_from_file_location("check_model_subscribers", _HERE / "check-model-subscribers.py")
chk = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(chk)

SERVICES = ["a-svc", "b-svc", "c-svc", "console-web"]
UNIVERSE = ["a-svc", "b-svc", "c-svc"]
GONE = "gst.old.gone.v1"


def _topic(name, etype, producers, subscribers):
    return {"topic": name, "event_type": f"com.example.{etype}.v1", "producers": producers, "subscribers": subscribers}


def base():
    """Return (events, topics): three feeds, two plain events, two model-only ids and the exempt topic."""
    ev = []
    tp = []
    for n in ("FeedA", "FeedB", "FeedC"):
        ev.append({"id": n, "producers": ["a-svc"], "consumers": ["b-svc"], "feeds_runtime_replicas": True})
        tp.append(_topic(f"gst.x.{n.lower()}.v1", n, ["a-svc"], ["b-svc", "c-svc"]))
    ev.append({"id": "Plain", "producers": ["a-svc"], "consumers": ["b-svc"], "feeds_runtime_replicas": False})
    tp.append(_topic("gst.x.plain.v1", "Plain", ["a-svc"], ["b-svc"]))
    ev.append({"id": "Every", "producers": ["every service with scheduled jobs"], "consumers": ["c-svc"]})
    tp.append(_topic("gst.x.every.v1", "Every", ["every service"], ["c-svc"]))
    for n in chk.MODEL_ONLY:
        ev.append({"id": n, "producers": ["a-svc"], "consumers": ["b-svc"], "feeds_runtime_replicas": False})
    tp.append(_topic("gst.access.authority-changed.v1", "FeedA", ["a-svc"], []))
    return ev, tp


class Case(unittest.TestCase):
    def setUp(self):
        self._tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self._tmp.cleanup)
        self.root = Path(self._tmp.name)
        (self.root / "model").mkdir()
        (self.root / "event-log").mkdir()
        self.events, self.topics = base()

    def run_check(self, events=None, topics=None, services=SERVICES, raw_events=None):
        events = self.events if events is None else events
        topics = self.topics if topics is None else topics
        m = self.root / "model"
        (m / "events.yaml").write_text(raw_events if raw_events is not None else yaml.safe_dump({"events": events}))
        (m / "services.yaml").write_text(yaml.safe_dump({"services": [{"id": s} for s in services]}))
        (self.root / "event-log" / "topics.yaml").write_text(yaml.safe_dump({"topics": topics}))
        out = io.StringIO()
        with redirect_stdout(out):
            code = chk.main(["x", str(m), str(self.root)])
        return code, out.getvalue().splitlines()

    def topic(self, name):
        return next(t for t in self.topics if t["topic"] == name)

    def event(self, eid):
        return next(e for e in self.events if e["id"] == eid)


class TestConstants(unittest.TestCase):
    def test_exemption_constants_are_exact(self):
        self.assertEqual(chk.MODEL_ONLY, {"ReplayRequested", "ReplayCompleted"})
        self.assertEqual(chk.EXEMPT_TOPICS, {"gst.access.authority-changed.v1"})
        self.assertEqual(chk.EXEMPT_TYPES, set())
        self.assertEqual(chk.NON_EVENT_SERVICES, {"console-web"})


class TestCheck(Case):
    def test_clean_fixture_exits_0(self):
        code, out = self.run_check()
        self.assertEqual((code, out[1:]), (0, []))

    def test_feed_subscriber_removed(self):
        self.topic("gst.x.feeda.v1")["subscribers"] = ["c-svc"]
        code, out = self.run_check()
        self.assertEqual(code, 1)
        self.assertIn("FeedA: subscribers of gst.x.feeda.v1 differ from the derived list: missing [b-svc], extra []", out)
        self.assertIn("FeedA: consumer b-svc is not a subscriber of gst.x.feeda.v1", out)

    def test_non_event_service_added(self):
        self.topic("gst.x.feeda.v1")["subscribers"].append("console-web")
        _, out = self.run_check()
        self.assertIn("FeedA: subscribers of gst.x.feeda.v1 differ from the derived list: missing [], extra [console-web]", out)

    def test_look_alike_subscriber(self):
        self.topic("gst.x.feeda.v1")["subscribers"] = ["b-svc", "c"]
        _, out = self.run_check()
        self.assertIn("FeedA: subscribers of gst.x.feeda.v1 differ from the derived list: missing [c-svc], extra [c]", out)

    def test_case_variant_is_not_equal(self):
        self.topic("gst.x.plain.v1")["subscribers"] = ["B-svc"]
        _, out = self.run_check()
        self.assertIn("Plain: consumer b-svc is not a subscriber of gst.x.plain.v1", out)

    def test_producer_removed(self):
        self.topic("gst.x.plain.v1")["producers"] = []
        _, out = self.run_check()
        self.assertEqual(
            [l for l in out if "producer" in l], ["Plain: producer a-svc is not a producer of gst.x.plain.v1"])

    def test_every_service_alias_maps_to_every_service(self):
        self.topic("gst.x.every.v1")["producers"] = ["every service with scheduled jobs"]
        _, out = self.run_check()
        self.assertIn("Every: producer every service is not a producer of gst.x.every.v1", out)

    def test_model_consumer_without_subscriber(self):
        self.event("Plain")["consumers"].append("c-svc")
        code, out = self.run_check()
        self.assertEqual(code, 1)
        self.assertIn("Plain: consumer c-svc is not a subscriber of gst.x.plain.v1", out)

    def test_contract_ahead_of_model_is_allowed(self):
        self.topic("gst.x.every.v1")["subscribers"].append("b-svc")
        self.topic("gst.x.every.v1")["producers"].append("b-svc")
        code, out = self.run_check()
        self.assertEqual((code, out[1:]), (0, []))

    def test_exempt_topic_deleted_is_stale(self):
        self.topics = [t for t in self.topics if t["topic"] != "gst.access.authority-changed.v1"]
        _, out = self.run_check()
        self.assertEqual(out[1:], ["exemption gst.access.authority-changed.v1 is stale"])

    def test_exempt_topic_is_not_checked(self):
        # present, subscriber-less and carrying a modelled type: no finding, and the type has one non-exempt topic
        code, _ = self.run_check()
        self.assertEqual(code, 0)

    def test_topic_without_model_event(self):
        self.topics.append(_topic("gst.x.stray.v1", "Stray", ["a-svc"], []))
        _, out = self.run_check()
        self.assertEqual(out[1:], ["topic gst.x.stray.v1: event type Stray has no event in events.yaml"])

    def test_exempt_type_works_and_goes_stale(self):
        self.topics.append(_topic("gst.x.stray.v1", "Stray", ["a-svc"], []))
        chk.EXEMPT_TYPES = frozenset({"Stray"})
        self.addCleanup(lambda: setattr(chk, "EXEMPT_TYPES", frozenset()))
        self.assertEqual(self.run_check()[1][1:], [])
        self.events.append({"id": "Stray", "producers": ["a-svc"], "consumers": []})
        _, out = self.run_check()
        self.assertIn("exemption Stray is stale", out)

    def test_event_without_topic(self):
        self.events.append({"id": "Lonely", "producers": ["a-svc"], "consumers": []})
        _, out = self.run_check()
        self.assertEqual(out[1:], ["Lonely: no topic carries the event type"])

    def test_two_topics_for_one_id(self):
        self.topics.append(_topic("gst.y.plain.v1", "Plain", ["a-svc"], ["b-svc"]))
        _, out = self.run_check()
        self.assertIn("Plain: more than one topic carries the event type: [gst.x.plain.v1, gst.y.plain.v1]", out)

    def test_model_only_with_topic_or_missing_is_stale(self):
        self.topics.append(_topic("gst.x.replayrequested.v1", "ReplayRequested", ["a-svc"], []))
        _, out = self.run_check()
        self.assertIn("exemption ReplayRequested is stale", out)
        self.events = [e for e in self.events if e["id"] != "ReplayCompleted"]
        self.topics = [t for t in self.topics if "replayrequested" not in t["topic"]]
        _, out = self.run_check()
        self.assertEqual(out[1:], ["exemption ReplayCompleted is stale"])

    def test_flag_dropped_is_found(self):
        self.event("FeedA")["feeds_runtime_replicas"] = False
        _, out = self.run_check()
        self.assertIn(
            "FeedA: feeds_runtime_replicas is not true but the subscribers of gst.x.feeda.v1 cover every service", out)

    def test_new_flag_is_enforced_without_script_edit(self):
        self.event("Plain")["feeds_runtime_replicas"] = True
        _, out = self.run_check()
        self.assertIn(
            "Plain: subscribers of gst.x.plain.v1 differ from the derived list: missing [c-svc], extra []", out)

    def test_flag_not_boolean(self):
        self.event("FeedA")["feeds_runtime_replicas"] = "yes"
        _, out = self.run_check()
        self.assertIn("FeedA: feeds_runtime_replicas is not a boolean", out)

    def test_producer_excluded_only_by_equality(self):
        self.event("FeedA")["producers"] = ["every service owning a variation point"]
        self.topic("gst.x.feeda.v1")["producers"] = ["every service"]
        self.topic("gst.x.feeda.v1")["subscribers"] = ["b-svc", "c-svc", "a-svc"]
        _, out = self.run_check()
        self.assertEqual(out[1:], [])

    def test_replica_feeds_line_follows_model(self):
        def printed():
            return set(self.run_check()[1][0].removeprefix("replica feeds: ").split(", "))

        def parsed():
            doc = yaml.safe_load((self.root / "model" / "events.yaml").read_text())
            return {e["id"] for e in doc["events"] if e.get("feeds_runtime_replicas") is True}

        self.assertEqual(printed(), parsed())
        self.assertEqual(len(parsed()), 3)
        self.run_check()
        self.event("Plain")["feeds_runtime_replicas"] = True
        self.topic("gst.x.plain.v1")["subscribers"] = ["b-svc", "c-svc"]
        self.assertEqual(printed(), parsed())
        self.assertEqual(len(parsed()), 4)

    def test_invalid_yaml_exits_1(self):
        code, out = self.run_check(raw_events="events: [unclosed\n  - : :")
        self.assertEqual(code, 1)
        self.assertTrue(any(l.startswith("events.yaml: unreadable or malformed") for l in out))

    def test_missing_file_and_key_fail_closed(self):
        self.run_check()
        (self.root / "model" / "services.yaml").unlink()
        out = io.StringIO()
        with redirect_stdout(out):
            code = chk.main(["x", str(self.root / "model"), str(self.root)])
        self.assertEqual(code, 1)
        self.run_check(raw_events="other: []\n")
        out = io.StringIO()
        with redirect_stdout(out):
            self.assertEqual(chk.main(["x", str(self.root / "model"), str(self.root)]), 1)
        self.assertIn("events.yaml: missing or malformed key events", out.getvalue())

    def test_duplicate_event_id(self):
        self.events.append(copy.deepcopy(self.event("Plain")))
        _, out = self.run_check()
        self.assertIn("Plain: duplicate event id in events.yaml", out)

    def test_missing_subscribers_key(self):
        del self.topic("gst.x.plain.v1")["subscribers"]
        code, out = self.run_check()
        self.assertEqual(code, 1)
        self.assertIn("topic gst.x.plain.v1: subscribers: not a list of strings", out)


if __name__ == "__main__":
    unittest.main()
