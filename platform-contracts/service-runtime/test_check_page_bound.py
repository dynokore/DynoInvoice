"""INV-T-4915 (INV-Q-5509): the twelve probes of check-page-bound.py (each on a temporary copy of the files) and the CI wiring probes."""
import copy
import importlib.util
import json
import shutil
import tempfile
import unittest
from pathlib import Path

HERE = Path(__file__).resolve().parent
ROOT = HERE.parent
spec = importlib.util.spec_from_file_location("check_page_bound", HERE / "check-page-bound.py")
cpb = importlib.util.module_from_spec(spec)
spec.loader.exec_module(cpb)


LINE = "python3 service-runtime/check-page-bound.py"


class PageBoundProbes(unittest.TestCase):
    def setUp(self):
        self.tmp = Path(tempfile.mkdtemp())
        for rel in (cpb.SCHEMA, cpb.VALID, cpb.INVALID, cpb.REGISTRY):
            (self.tmp / rel).parent.mkdir(parents=True, exist_ok=True)
            shutil.copy(ROOT / rel, self.tmp / rel)

    def tearDown(self):
        shutil.rmtree(self.tmp)

    def edit(self, rel, fn):
        p = self.tmp / rel
        o = json.loads(p.read_text())
        fn(o)
        p.write_text(json.dumps(o))

    def failed(self, item):
        failures = cpb.check(self.tmp)
        self.assertTrue(any(f.startswith(f"({item})") for f in failures), failures)

    def test_clean_copy_passes(self):
        self.assertEqual(cpb.check(self.tmp), [])

    def test_max_items_501(self):
        self.edit(cpb.SCHEMA, lambda o: o["properties"]["documents"].update(maxItems=501)); self.failed("a")

    def test_max_items_499(self):
        self.edit(cpb.SCHEMA, lambda o: o["properties"]["documents"].update(maxItems=499)); self.failed("a")

    def test_max_items_removed(self):
        self.edit(cpb.SCHEMA, lambda o: o["properties"]["documents"].pop("maxItems")); self.failed("a")

    def test_max_items_on_items_only(self):
        def f(o):
            d = o["properties"]["documents"]
            d.pop("maxItems")
            d["items"]["maxItems"] = 500
        self.edit(cpb.SCHEMA, f); self.failed("a")

    def test_description_without_api_42(self):
        self.edit(cpb.SCHEMA, lambda o: o.update(description=o["description"].replace("API-42", "the standard"))); self.failed("b")

    def test_description_look_alike_api_24(self):
        self.edit(cpb.SCHEMA, lambda o: o.update(description=o["description"].replace("API-42", "API-24"))); self.failed("b")

    def test_description_without_1_mb(self):
        self.edit(cpb.SCHEMA, lambda o: o.update(description=o["description"].replace("1 MB", "the limit"))); self.failed("b")

    def test_valid_sample_499(self):
        self.edit(cpb.VALID, lambda o: o["documents"].pop()); self.failed("c")

    def test_valid_sample_501(self):
        self.edit(cpb.VALID, lambda o: o["documents"].append(copy.deepcopy(o["documents"][0]))); self.failed("c")

    def test_invalid_sample_500(self):
        self.edit(cpb.INVALID, lambda o: o["documents"].pop()); self.failed("d")

    def test_invalid_sample_501_with_a_second_error(self):
        self.edit(cpb.INVALID, lambda o: o["documents"][0].update(transactionDate="not-a-date")); self.failed("d")

    def test_invalid_sample_removed_from_schema_txt(self):
        p = self.tmp / cpb.REGISTRY
        p.write_text("".join(l for l in p.read_text().splitlines(True) if "pack-dry-run-sample-recorded" not in l))
        self.failed("e")

    def test_ci_line_in_contract_rules_only(self):
        text = (ROOT / ".gitlab-ci.yml").read_text()
        self.assertEqual(self.job_lines(text, LINE), ["contract-rules"])

    def test_ci_line_in_another_job_does_not_count(self):
        text = (ROOT / ".gitlab-ci.yml").read_text().replace(f"    - {LINE}\n", "")
        text = text.replace("asyncapi-validate:\n", f"asyncapi-validate:\n  script:\n    - {LINE}\n", 1)
        self.assertNotEqual(self.job_lines(text, LINE), ["contract-rules"])

    def test_ci_line_removed_from_the_job_fails(self):
        text = (ROOT / ".gitlab-ci.yml").read_text().replace(f"    - {LINE}\n", "")
        self.assertEqual(self.job_lines(text, LINE), [])

    @staticmethod
    def job_lines(text, line):
        """Names of the top-level jobs whose block holds the exact command line `- <line>`."""
        jobs, current = [], None
        for raw in text.splitlines():
            if raw and not raw[0].isspace() and raw.endswith(":") and not raw.startswith("#"):
                current = raw[:-1]
            elif raw.strip() == f"- {line}" and current:
                jobs.append(current)
        return jobs

    def test_unloadable_file_exits_non_zero(self):
        (self.tmp / cpb.VALID).write_text("{")
        self.assertEqual(cpb.main(["x", str(self.tmp)]), 2)


if __name__ == "__main__":
    unittest.main()
