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
import subprocess
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
OLD = "gst.tenancy.installation-status-changed.v1"
NEW = "gst.platform.installation-status-changed.v1"


def _topic(name, etype, producers, subscribers):
    return {"topic": name, "event_type": f"com.dynokore.gst.{name.split('.')[1]}.{etype}.v1", "producers": producers, "subscribers": subscribers}


def base():
    """Return (events, topics): three feeds, two plain events, two model-only ids and the exempt topic."""
    ev = []
    tp = []
    for n in ("FeedA", "FeedB", "FeedC"):
        ev.append({"id": n, "producers": ["a-svc"], "consumers": ["b-svc"], "feeds_runtime_replicas": True})
        tp.append(_topic(f"gst.x.feed-{n[-1].lower()}.v1", n, ["a-svc"], ["b-svc", "c-svc"]))
    ev.append({"id": "Plain", "producers": ["a-svc"], "consumers": ["b-svc"], "feeds_runtime_replicas": False})
    tp.append(_topic("gst.x.plain.v1", "Plain", ["a-svc"], ["b-svc"]))
    ev.append({"id": "Every", "producers": ["every service with scheduled jobs"], "consumers": ["c-svc"]})
    tp.append(_topic("gst.x.every.v1", "Every", ["every service"], ["c-svc"]))
    for n in chk.MODEL_ONLY:
        ev.append({"id": n, "producers": ["a-svc"], "consumers": ["b-svc"], "feeds_runtime_replicas": False})
    tp.append(_topic("gst.access.authority-changed.v1", "AuthorityChanged", ["a-svc"], []))
    ev.append({"id": "InstallationStatusChanged", "producers": ["a-svc"], "consumers": ["b-svc"], "feeds_runtime_replicas": False})
    tp.append(_topic(OLD, "InstallationStatusChanged", ["a-svc"], ["b-svc"]))
    tp.append(_topic(NEW, "InstallationStatusChanged", ["a-svc"], ["b-svc"]))
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

    def test_superseded_topics_are_exact(self):
        self.assertEqual(chk.SUPERSEDED_TOPICS, {OLD: NEW})


class TestCheck(Case):
    def test_clean_fixture_exits_0(self):
        code, out = self.run_check()
        self.assertEqual((code, out[1:]), (0, []))

    def test_feed_subscriber_removed(self):
        self.topic("gst.x.feed-a.v1")["subscribers"] = ["c-svc"]
        code, out = self.run_check()
        self.assertEqual(code, 1)
        self.assertIn("FeedA: subscribers of gst.x.feed-a.v1 differ from the derived list: missing [b-svc], extra []", out)
        self.assertIn("FeedA: consumer b-svc is not a subscriber of gst.x.feed-a.v1", out)

    def test_non_event_service_added(self):
        self.topic("gst.x.feed-a.v1")["subscribers"].append("console-web")
        _, out = self.run_check()
        self.assertIn("FeedA: subscribers of gst.x.feed-a.v1 differ from the derived list: missing [], extra [console-web]", out)

    def test_look_alike_subscriber(self):
        self.topic("gst.x.feed-a.v1")["subscribers"] = ["b-svc", "c"]
        _, out = self.run_check()
        self.assertIn("FeedA: subscribers of gst.x.feed-a.v1 differ from the derived list: missing [c-svc], extra [c]", out)

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

    def test_superseded_pair_passes_rule_1(self):
        code, out = self.run_check()
        self.assertEqual((code, out[1:]), (0, []))
        self.assertFalse([ln for ln in out if "more than one topic" in ln])

    def test_superseded_old_topic_removed_is_stale(self):
        self.topics = [t for t in self.topics if t["topic"] != OLD]
        _, out = self.run_check()
        self.assertEqual(out[1:], [f"superseded topic {OLD} is stale"])

    def test_superseded_replacement_removed_is_stale(self):
        self.topics = [t for t in self.topics if t["topic"] != NEW]
        _, out = self.run_check()
        self.assertEqual(out[1:], ["InstallationStatusChanged: no topic carries the event type", f"superseded topic {OLD} is stale"])

    def test_superseded_rules_apply_to_the_replacement(self):
        self.topic(NEW)["subscribers"] = ["c-svc"]
        _, out = self.run_check()
        self.assertIn(f"InstallationStatusChanged: consumer b-svc is not a subscriber of {NEW}", out)

    def test_superseded_old_topic_still_needs_an_event(self):
        self.events = [e for e in self.events if e["id"] != "InstallationStatusChanged"]
        _, out = self.run_check()
        self.assertIn(f"topic {OLD}: event type InstallationStatusChanged has no event in events.yaml", out)

    def test_superseded_lookalike_keys_still_fail_rule_1(self):
        for bad in (OLD.replace(".v1", ".v2"), OLD.upper(), OLD + " ", NEW):
            with self.subTest(bad=bad):
                self.topics = [t for t in self.topics if t["topic"] not in (OLD, NEW)]
                # the real pair is kept present (so it is not stale) under another event type
                self.topics.append(_topic(OLD, "Plain2", ["a-svc"], ["b-svc"]))
                self.topics.append(_topic(NEW, "Plain2", ["a-svc"], ["b-svc"]))
                self.events = [e for e in self.events if e["id"] != "Plain2"]
                self.events.append({"id": "Plain2", "producers": ["a-svc"], "consumers": ["b-svc"], "feeds_runtime_replicas": False})
                self.topics.append(_topic("gst.q.swap-a.v1", "InstallationStatusChanged", ["a-svc"], ["b-svc"]))
                self.topics.append(_topic(bad, "InstallationStatusChanged", ["a-svc"], ["b-svc"]))
                _, out = self.run_check()
                self.assertTrue([ln for ln in out if ln.startswith("InstallationStatusChanged: more than one topic carries the event type")], out)

    def test_model_only_with_topic_or_missing_is_stale(self):
        self.topics.append(_topic("gst.x.replay-requested.v1", "ReplayRequested", ["a-svc"], []))
        _, out = self.run_check()
        self.assertIn("exemption ReplayRequested is stale", out)
        self.events = [e for e in self.events if e["id"] != "ReplayCompleted"]
        self.topics = [t for t in self.topics if "replay-requested" not in t["topic"]]
        _, out = self.run_check()
        self.assertEqual(out[1:], ["exemption ReplayCompleted is stale"])

    def test_flag_dropped_is_found(self):
        self.event("FeedA")["feeds_runtime_replicas"] = False
        _, out = self.run_check()
        self.assertIn(
            "FeedA: feeds_runtime_replicas is not true but the subscribers of gst.x.feed-a.v1 cover every service", out)

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
        self.topic("gst.x.feed-a.v1")["producers"] = ["every service"]
        self.topic("gst.x.feed-a.v1")["subscribers"] = ["b-svc", "c-svc", "a-svc"]
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


def _git(repo, *args):
    return subprocess.run(["git", "-C", str(repo), "-c", "user.name=t", "-c", "user.email=t@t", *args],
                          capture_output=True, text=True, check=True).stdout.strip()


class TestPinned(Case):
    """INV-T-16628: pinned mode on small git repositories built with git init."""

    def setUp(self):
        super().setUp()
        self.docs = self.root / "docs"
        self.docs.mkdir()
        _git(self.docs, "init", "-q", "-b", "main")

    def commit(self, events, services=SERVICES, msg="c"):
        m = self.docs / "model" / "architecture"
        m.mkdir(parents=True, exist_ok=True)
        (m / "events.yaml").write_text(yaml.safe_dump({"events": events}))
        (m / "services.yaml").write_text(yaml.safe_dump({"services": [{"id": x} for x in services]}))
        _git(self.docs, "add", "-A")
        _git(self.docs, "commit", "-q", "-m", msg)
        return _git(self.docs, "rev-parse", "HEAD")

    def set_origin_main(self):
        _git(self.docs, "update-ref", "refs/remotes/origin/main", "main")

    def run_pin(self, pin, pin_name="model-pin.txt", topics=None):
        topics = self.topics if topics is None else topics
        (self.root / "event-log" / "topics.yaml").write_text(yaml.safe_dump({"topics": topics}))
        pf = self.root / "event-log" / pin_name
        if pin is not None:
            pf.write_bytes(pin if isinstance(pin, bytes) else pin.encode())
        out = io.StringIO()
        with redirect_stdout(out):
            code = chk.main(["x", "--pin", str(self.root / "event-log" / "model-pin.txt"),
                             "--docs-repo", str(self.docs), str(self.root)])
        return code, out.getvalue().splitlines()

    def test_well_formed_pin_exits_0(self):
        sha = self.commit(self.events)
        self.set_origin_main()
        code, out = self.run_pin(sha + "\n")
        self.assertEqual(code, 0, out)
        self.assertEqual(out[0], f"model pin: {sha} (invoice-docs main {sha[:7]})")

    def test_malformed_pins(self):
        sha = self.commit(self.events)
        self.set_origin_main()
        bad = {"empty": "", "blank": "\n", "uppercase": sha.upper() + "\n", "39": sha[:39] + "\n",
               "41": sha + "a\n", "7": sha[:7] + "\n", "branch": "main\n", "two lines": sha + "\n" + sha + "\n",
               "trailing space": sha + " \n", "no newline": sha, "crlf": sha + "\r\n"}
        for name, text in bad.items():
            with self.subTest(name):
                self.assertEqual(self.run_pin(text), (1, ["model-pin: event-log/model-pin.txt is malformed"]))

    def test_pin_file_missing_and_lookalike(self):
        sha = self.commit(self.events)
        self.set_origin_main()
        want = (1, ["model-pin: event-log/model-pin.txt is missing"])
        self.assertEqual(self.run_pin(None), want)
        self.assertEqual(self.run_pin(sha + "\n", pin_name="model-pin.txt.bak"), want)

    def test_commit_not_in_clone(self):
        self.commit(self.events)
        self.set_origin_main()
        sha = "1" * 40
        self.assertEqual(self.run_pin(sha + "\n"),
                         (1, [f"model-pin: commit {sha} cannot be read in the invoice-docs clone"]))

    def test_commit_not_ancestor_of_origin_main(self):
        self.commit(self.events)
        _git(self.docs, "checkout", "-q", "-b", "side")
        side = self.commit(self.events + [{"id": "Side", "producers": [], "consumers": []}])
        _git(self.docs, "checkout", "-q", "main")
        self.set_origin_main()
        self.assertEqual(self.run_pin(side + "\n"),
                         (1, [f"model-pin: commit {side} is not an ancestor of invoice-docs main"]))

    def test_pin_before_event_the_topics_list(self):
        a = self.commit(self.events)
        self.commit(self.events + [{"id": "Later", "producers": ["a-svc"], "consumers": ["b-svc"]}])
        self.set_origin_main()
        topics = self.topics + [_topic("gst.x.later.v1", "Later", ["a-svc"], ["b-svc"])]
        code, out = self.run_pin(a + "\n", topics=topics)
        self.assertEqual(code, 1)
        self.assertIn("topic gst.x.later.v1: event type Later has no event in events.yaml", out)

    def test_pin_at_model_event_without_topic(self):
        self.commit(self.events)
        b = self.commit(self.events + [{"id": "Later", "producers": ["a-svc"], "consumers": ["b-svc"]}])
        self.set_origin_main()
        code, out = self.run_pin(b + "\n")
        self.assertEqual(code, 1)
        self.assertIn("Later: no topic carries the event type", out)

    def test_lookalike_event_type(self):
        sha = self.commit(self.events + [{"id": "IdentityTripleChanged", "producers": ["a-svc"], "consumers": []}])
        self.set_origin_main()
        topics = self.topics + [_topic("gst.x.identity-triple-change.v1", "IdentityTripleChange", ["a-svc"], [])]
        code, out = self.run_pin(sha + "\n", topics=topics)
        self.assertEqual(code, 1)
        self.assertIn("topic gst.x.identity-triple-change.v1: event type IdentityTripleChange has no event in events.yaml", out)

    def test_pinned_run_ignores_the_working_tree_head(self):
        a = self.commit(self.events)
        self.commit(self.events + [{"id": "Later", "producers": ["a-svc"], "consumers": ["b-svc"]}])
        self.set_origin_main()
        self.assertEqual(self.run_pin(a + "\n")[0], 0)


class _Loader(yaml.SafeLoader):
    pass


_Loader.add_multi_constructor("!", lambda loader, suffix, node: loader.construct_sequence(node, deep=True))
CI_FILE = _HERE.parent / ".gitlab-ci.yml"
HEAD_JOB, PIN_JOB = "model-subscribers-head-check", "invoice-docs-checks"
UNPINNED = "python3 event-log/check-model-subscribers.py /tmp/invoice-docs/model/architecture ."


def ci_wiring_problems(doc) -> list[str]:
    """Returns what is wrong with the model-subscribers wiring of a parsed .gitlab-ci.yml (empty when right)."""
    p = []
    head, pin = doc.get(HEAD_JOB), doc.get(PIN_JOB)
    if not isinstance(head, dict) or not isinstance(pin, dict):
        return [f"job {HEAD_JOB} or {PIN_JOB} is missing"]
    if head.get("rules") != [{"if": '$CI_PIPELINE_SOURCE == "schedule"'}]:
        p.append("head job rules are not exactly the schedule rule")
    for k in ("image", "stage", "needs", "extends"):
        if head.get(k) != pin.get(k):
            p.append(f"head job {k} differs from {PIN_JOB}")
    hs = [x for x in head.get("script", []) if isinstance(x, str)]
    if not any(UNPINNED in x for x in hs):
        p.append("head job does not run the unpinned invocation")
    if any("--pin" in x for x in hs):
        p.append("head job uses --pin")
    ps = [x for x in pin.get("script", []) if isinstance(x, str)]
    mine = [x for x in ps if "model-subscribers-check" in x]
    want = ("gst_check model-subscribers-check python3 event-log/check-model-subscribers.py "
            "--pin event-log/model-pin.txt --docs-repo /tmp/invoice-docs .")
    if mine != [want]:
        p.append("invoice-docs-checks does not hold exactly the pinned model-subscribers-check line")
    if any(UNPINNED in x for x in ps):
        p.append("invoice-docs-checks holds an unpinned invocation")
    if "gst_skip" in str(head.get("extends")) or ".gst-skip-verified" in str(head.get("extends")):
        p.append("head job extends .gst-skip-verified")
    return p


class TestCiWiring(unittest.TestCase):
    def load(self):
        return yaml.load(CI_FILE.read_text(encoding="utf-8"), Loader=_Loader)

    def test_ci_file_is_wired(self):
        self.assertEqual(ci_wiring_problems(self.load()), [])

    def test_changed_rule_fails(self):
        doc = self.load()
        doc[HEAD_JOB]["rules"] = [{"if": '$CI_PIPELINE_SOURCE != "schedule"'}]
        self.assertTrue(ci_wiring_problems(doc))

    def test_extra_rule_entry_fails(self):
        doc = self.load()
        doc[HEAD_JOB]["rules"].append({"when": "on_success"})
        self.assertTrue(ci_wiring_problems(doc))

    def test_lookalike_job_name_fails(self):
        doc = self.load()
        doc[HEAD_JOB + "2"] = doc.pop(HEAD_JOB)
        self.assertTrue(ci_wiring_problems(doc))

    def test_pin_in_head_job_or_unpinned_in_pin_job_fails(self):
        doc = self.load()
        doc[HEAD_JOB]["script"].append("x --pin y")
        self.assertTrue(ci_wiring_problems(doc))
        doc = self.load()
        doc[PIN_JOB]["script"].append("gst_check model-subscribers-check " + UNPINNED)
        self.assertTrue(ci_wiring_problems(doc))


TWAVM = "com.dynokore.gst.platform.TenantWideArchiveVolumeMeasured.v1"
TWAVM_ADDR = "gst.platform.tenant-wide-archive-volume-measured.v1"
TWAVM_LINE = (
    "topic {t}: address differs from the address derived from event type " + TWAVM + " (expected " + TWAVM_ADDR + ")"
)


class TestAddressRule(unittest.TestCase):
    """INV-T-17778 / INV-AC-17777: the topic address equals the address derived from its event type."""

    def run_topics(self, entries, extra_events=()):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            (root / "model").mkdir()
            (root / "event-log").mkdir()
            (root / "model" / "events.yaml").write_text(yaml.safe_dump({"events": [
                {"id": "TenantWideArchiveVolumeMeasured", "producers": ["a-svc"], "consumers": ["b-svc"], "feeds_runtime_replicas": False},
                {"id": "PolicyChanged", "producers": ["a-svc"], "consumers": ["b-svc"], "feeds_runtime_replicas": False},
            ] + [{"id": e, "producers": ["a-svc"], "consumers": ["b-svc"], "feeds_runtime_replicas": False} for e in extra_events]}))
            (root / "model" / "services.yaml").write_text(yaml.safe_dump({"services": [{"id": "a-svc"}, {"id": "b-svc"}, {"id": "c-svc"}]}))
            topics = [{"topic": n, "event_type": e, "producers": ["a-svc"], "subscribers": ["b-svc"]} for n, e in entries]
            (root / "event-log" / "topics.yaml").write_text(yaml.safe_dump({"topics": topics}))
            out = io.StringIO()
            with redirect_stdout(out):
                code = chk.main(["x", str(root / "model"), str(root)])
            return code, out.getvalue().splitlines()

    def single(self, addr):
        code, out = self.run_topics([(addr, TWAVM)])
        return code, [l for l in out if "address differs" in l or "is malformed" in l]

    def test_INV_AC_17777_1_all_addresses_equal_exits_0(self):
        pairs = [
            (TWAVM_ADDR, TWAVM),
            ("gst.platform.policy-changed.v1", "com.dynokore.gst.platform.PolicyChanged.v1"),
            ("gst.access.authority-changed.v1", "com.dynokore.gst.access.AuthorityChanged.v1"),
            (OLD, "com.dynokore.gst.tenancy.InstallationStatusChanged.v1"),
            (NEW, "com.dynokore.gst.platform.InstallationStatusChanged.v1"),
        ]
        code, out = self.run_topics(pairs, extra_events=sorted(chk.MODEL_ONLY) + ["InstallationStatusChanged"])
        self.assertEqual(code, 0, out)
        self.assertEqual([l for l in out if "address differs" in l or "is malformed" in l], [])

    def _differs(self, addr):
        code, lines = self.single(addr)
        self.assertEqual(code, 1)
        self.assertEqual(lines, [TWAVM_LINE.format(t=addr)])

    def test_INV_AC_17777_2_other_domain(self):
        self._differs("gst.evidence.tenant-wide-archive-volume-measured.v1")

    def test_INV_AC_17777_3_other_version(self):
        self._differs("gst.platform.tenant-wide-archive-volume-measured.v2")

    def test_INV_AC_17777_4_upper_case_letter(self):
        self._differs("gst.platform.Tenant-wide-archive-volume-measured.v1")

    def test_INV_AC_17777_5_trailing_space(self):
        self._differs(TWAVM_ADDR + " ")

    def test_INV_AC_17777_6_no_version(self):
        self._differs("gst.platform.tenant-wide-archive-volume-measured")

    def test_INV_AC_17777_7_prefix_of_name(self):
        self._differs("gst.platform.tenant-wide-archive-volume.v1")

    def test_INV_AC_17777_8_swapped_pair(self):
        pol = "com.dynokore.gst.platform.PolicyChanged.v1"
        code, out = self.run_topics([("gst.platform.policy-changed.v1", TWAVM), (TWAVM_ADDR, pol)])
        self.assertEqual(code, 1)
        self.assertEqual(len([l for l in out if "address differs" in l]), 2)

    def test_INV_AC_17777_9_event_type_without_version(self):
        et = "com.dynokore.gst.platform.TenantWideArchiveVolumeMeasured"
        code, out = self.run_topics([(TWAVM_ADDR, et)])
        self.assertEqual(code, 1)
        self.assertIn(f"topic {TWAVM_ADDR}: event type {et} is malformed", out)

    def test_INV_AC_17777_10_other_root(self):
        et = "com.example.gst.platform.TenantWideArchiveVolumeMeasured.v1"
        code, out = self.run_topics([(TWAVM_ADDR, et)])
        self.assertEqual(code, 1)
        self.assertIn(f"topic {TWAVM_ADDR}: event type {et} is malformed", out)

    def test_INV_AC_17777_11_exempt_topic_is_checked(self):
        et = "com.dynokore.gst.access.AuthorityChanged.v1"
        code, out = self.run_topics([("gst.platform.authority-changed.v1", et)])
        self.assertEqual(code, 1)
        self.assertIn(
            f"topic gst.platform.authority-changed.v1: address differs from the address derived from event type {et}"
            " (expected gst.access.authority-changed.v1)", out)

    def test_INV_AC_17777_12_address_correct_for_another_type(self):
        code, lines = self.single("gst.platform.policy-changed.v1")
        self.assertEqual(code, 1)
        self.assertEqual(lines, [TWAVM_LINE.format(t="gst.platform.policy-changed.v1")])

    def test_INV_AC_17777_trailing_newline_event_type_is_malformed(self):
        code, lines = self.run_topics([(TWAVM_ADDR, TWAVM + "\n")])
        self.assertEqual(code, 1)
        self.assertTrue(any("is malformed" in l for l in lines))


if __name__ == "__main__":
    unittest.main()
