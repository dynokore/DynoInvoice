"""Tests for check-drift.py's installation-parameter row derivation (INV-T-3377; INV-Q-3202, P57).

Run: python3 -m unittest discover -s catalogue/variation-points -p 'test_*.py'
"""
import importlib.util
import unittest
from pathlib import Path

_spec = importlib.util.spec_from_file_location("check_drift", Path(__file__).with_name("check-drift.py"))
cd = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(cd)


def vp(src, req, param=None):
    cat = {"installation_parameter": param} if param else {}
    return {"k": req, "src": src, "rev": "r", "io": {"catalogue": cat}, "t": "t", "beh": None}


def ref(row, req):
    return {"row": row, "requirement": req, "name": "n"}


def fetcher(*rows):
    calls = []

    def fetch(keys):
        calls.append(sorted(keys))
        return [r for r in rows if r["k"] in keys]

    fetch.calls = calls
    return fetch


class DeriveParameterRows(unittest.TestCase):
    def test_derives_the_row_id_from_the_referencer(self):
        rows = [vp(["VP-57"], "INV-REQ-1318", ref("VP-79", "INV-REQ-3210"))]
        out = cd.derive_parameter_rows(rows, fetcher(vp([], "INV-REQ-3210")))
        self.assertEqual([("INV-REQ-3210", ["VP-79"])], [(d["k"], d["src"]) for d in out])

    def test_rows_without_a_parameter_row_fetch_nothing(self):
        fetch = fetcher()
        self.assertEqual([], cd.derive_parameter_rows([vp(["VP-1"], "INV-REQ-1")], fetch))
        self.assertEqual([], fetch.calls)

    def test_referencers_that_disagree_fail(self):
        rows = [
            vp(["VP-57"], "INV-REQ-1318", ref("VP-79", "INV-REQ-3210")),
            vp(["VP-58"], "INV-REQ-1319", ref("VP-81", "INV-REQ-3210")),
        ]
        with self.assertRaisesRegex(cd.DerivationError, "disagree on its row id"):
            cd.derive_parameter_rows(rows, fetcher(vp([], "INV-REQ-3210")))

    def test_referencers_that_agree_pass(self):
        rows = [
            vp(["VP-57"], "INV-REQ-1318", ref("VP-79", "INV-REQ-3210")),
            vp(["VP-58"], "INV-REQ-1319", ref("VP-79", "INV-REQ-3210")),
        ]
        self.assertEqual(1, len(cd.derive_parameter_rows(rows, fetcher(vp([], "INV-REQ-3210")))))

    def test_a_differing_source_id_on_the_fetched_requirement_fails(self):
        rows = [vp(["VP-57"], "INV-REQ-1318", ref("VP-79", "INV-REQ-3210"))]
        for src in (["VP-90"], ["FR-X.1"]):
            with self.assertRaisesRegex(cd.DerivationError, "disagree with the derived id"):
                cd.derive_parameter_rows(rows, fetcher(vp(src, "INV-REQ-3210")))

    def test_once_keel_has_the_source_id_derived_and_stored_must_agree(self):
        # The requirement now comes back from the source-id query itself: agreement adds nothing.
        agree = [vp(["VP-57"], "INV-REQ-1318", ref("VP-79", "INV-REQ-3210")), vp(["VP-79"], "INV-REQ-3210")]
        fetch = fetcher()
        self.assertEqual([], cd.derive_parameter_rows(agree, fetch))
        self.assertEqual([], fetch.calls)
        disagree = [vp(["VP-57"], "INV-REQ-1318", ref("VP-79", "INV-REQ-3210")), vp(["VP-82"], "INV-REQ-3210")]
        with self.assertRaisesRegex(cd.DerivationError, "stored source ids"):
            cd.derive_parameter_rows(disagree, fetcher())

    def test_a_derived_id_that_collides_with_an_existing_row_fails(self):
        rows = [vp(["VP-57"], "INV-REQ-1318", ref("VP-57", "INV-REQ-3210"))]
        with self.assertRaisesRegex(cd.DerivationError, "collides with INV-REQ-1318"):
            cd.derive_parameter_rows(rows, fetcher(vp([], "INV-REQ-3210")))

    def test_an_unpublished_requirement_fails(self):
        rows = [vp(["VP-57"], "INV-REQ-1318", ref("VP-79", "INV-REQ-3210"))]
        with self.assertRaisesRegex(cd.DerivationError, "not a published INV requirement"):
            cd.derive_parameter_rows(rows, fetcher())

    def test_a_malformed_row_id_fails(self):
        rows = [vp(["VP-57"], "INV-REQ-1318", ref("79", "INV-REQ-3210"))]
        with self.assertRaisesRegex(cd.DerivationError, "malformed"):
            cd.derive_parameter_rows(rows, fetcher())


if __name__ == "__main__":
    unittest.main()
