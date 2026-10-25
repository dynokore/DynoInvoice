"""Tests for check-drift.py's installation-parameter row derivation (INV-T-3377; INV-Q-3202, P57) and set_permission (INV-T-10133).

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


def row(vp_id, perm=None):
    io = {"set_permission": perm} if perm is not None else {}
    return {"k": "INV-REQ-1", "src": [vp_id], "rev": "r", "io": io, "t": "t", "beh": None}


class SetPermission(unittest.TestCase):
    """INV-T-10133: io_contract.set_permission, and the one retired-row constant."""

    def test_the_constant_holds_exactly_vp_27(self):
        self.assertEqual({"VP-27"}, set(cd.RETIRED_SET_PERMISSION))
        self.assertEqual("vp-27.set", cd.RETIRED_SET_PERMISSION["VP-27"])

    def test_a_published_key_is_kept(self):
        self.assertEqual("vp-26.set", cd.resolve_set_permissions([row("VP-26", "vp-26.set")])[0]["io"]["set_permission"])

    def test_vp_27_without_a_key_yields_the_constant(self):
        self.assertEqual("vp-27.set", cd.resolve_set_permissions([row("VP-27")])[0]["io"]["set_permission"])

    def test_vp_27_with_another_key_fails(self):
        with self.assertRaisesRegex(cd.SetPermissionError, "VP-27"):
            cd.resolve_set_permissions([row("VP-27", "vp-26.set")])

    def test_other_rows_without_a_key_fail(self):
        for vp_id in ("VP-26", "VP-82", "VP-270", "VP-27 "):
            with self.subTest(vp_id), self.assertRaisesRegex(cd.SetPermissionError, vp_id):
                cd.resolve_set_permissions([row(vp_id)])

    def test_an_empty_key_fails(self):
        with self.assertRaises(cd.SetPermissionError):
            cd.resolve_set_permissions([row("VP-26", "")])

    def test_the_body_writes_set_permission_and_no_set_by(self):
        body = cd.render_body(cd.resolve_set_permissions([{**row("VP-26", "vp-26.set"), "io": {"set_permission": "vp-26.set", "status": "available"}}]))
        self.assertIn('  set_permission: "vp-26.set"\n', body)
        self.assertNotIn("set_by", body)


if __name__ == "__main__":
    unittest.main()
