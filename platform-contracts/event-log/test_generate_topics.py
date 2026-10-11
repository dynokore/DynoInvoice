#!/usr/bin/env python3
"""event-log/test_generate_topics.py — INV-T-3386, INV-T-3542

Tests generate-topics.py's content-topic marker (owner ruling INV-Q-3249,
INV-REQ-1205 b4): x-invoice-content is copied into topics.yaml as
content_topic on every topic, and the check fails when a topic lacks it.
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
        text = "topics:\n  - topic: gst.x.v1\n    event_type: x\n    content_topic: maybe\n"
        self.assertEqual(len(gen.validate_topics_yaml(text)), 1)

    def test_committed_topics_yaml_has_content_topic_on_every_topic(self):
        text = (_HERE / "topics.yaml").read_text()
        self.assertEqual(gen.validate_topics_yaml(text), [])
        self.assertEqual(text.count("  - topic: "), text.count("    content_topic: "))

    def test_generator_names_no_topic_or_event(self):
        src = (_HERE / "generate-topics.py").read_text()
        for name in ("CanonicalVersionCreated", "PayloadReady", "canonical-version-created", "payload-ready"):
            self.assertNotIn(name, src)


if __name__ == "__main__":
    unittest.main()
