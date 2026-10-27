"""Probes of route-scopes/generate-routes.py (INV-T-9042). Each probe edits a copy of the tree."""
import glob
import importlib.util
import os
import shutil
import subprocess
import sys
import tempfile
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.dirname(HERE)
GEN = os.path.join(HERE, "generate-routes.py")
TSV = os.path.join(HERE, "machine-routes.tsv")
TX = "  /v1/transactions:\n    post:\n"
TX_ALSO = "      x-principal-also: certification\n"


def read(path, mode="r"):
    with open(path, mode, **({} if "b" in mode else {"encoding": "utf-8"})) as fh:
        return fh.read()


def write(path, text):
    with open(path, "w", encoding="utf-8") as fh:
        fh.write(text)


def run(root, mode, target):
    p = subprocess.run([sys.executable, GEN, root, mode, target], capture_output=True, text=True)
    return p.returncode, p.stderr + p.stdout


class RouteScopesTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.mkdtemp()
        self.addCleanup(shutil.rmtree, self.tmp)
        for f in glob.glob(os.path.join(REPO, "*", "openapi.yaml")):
            d = os.path.join(self.tmp, os.path.basename(os.path.dirname(f)))
            os.makedirs(d)
            shutil.copy(f, d)
        self.out = os.path.join(self.tmp, "out.tsv")
        shutil.copy(TSV, self.out)

    def edit(self, service, old, new, count=1):
        f = os.path.join(self.tmp, service, "openapi.yaml")
        with open(f, encoding="utf-8") as fh:
            text = fh.read()
        self.assertEqual(text.count(old), count, old)
        with open(f, "w", encoding="utf-8") as fh:
            fh.write(text.replace(old, new))

    def append_paths(self, service, block):
        f = os.path.join(self.tmp, service, "openapi.yaml")
        text = read(f)
        self.assertIn("\npaths:\n", text)
        write(f, text.replace("\npaths:\n", "\npaths:\n" + block, 1))

    def tx_block(self):
        text = read(os.path.join(self.tmp, "ingestion", "openapi.yaml"))
        start = text.index(TX)
        end = text.index("\n  /", start + 1)
        return text[start:end + 1]

    def test_committed_file_matches_tree(self):
        code, out = run(REPO, "--check", TSV)
        self.assertEqual(code, 0, out)

    def test_row_counts_and_post_transactions(self):
        lines = read(TSV).split("\n")
        self.assertEqual(lines[0], "method\tpath_template\tscope\talso_scope")
        self.assertEqual(lines[-1], "")
        rows = lines[1:-1]
        self.assertEqual(len(rows), 20)
        self.assertIn("post\t/v1/transactions\tingest\tcertification", rows)
        self.assertEqual(sum(r.endswith("\tcertification") for r in rows if r.split("\t")[2] == "ingest"), 7)
        self.assertEqual(sum(r.endswith("\t-") for r in rows), 13)
        self.assertEqual(rows, sorted(rows, key=lambda r: (r.split("\t")[1], r.split("\t")[0])))

    def test_ap_callback_stays_out(self):
        self.assertNotIn("notifications", read(TSV))

    def test_scope_changed_check_fails_naming_row(self):
        text = read(os.path.join(self.tmp, "ingestion", "openapi.yaml"))
        i = text.index(TX)
        j = text.index("      x-principal: ingest\n", i)
        write(os.path.join(self.tmp, "ingestion", "openapi.yaml"),
            text[:j] + "      x-principal: reporting\n" + text[j + len("      x-principal: ingest\n"):])
        code, out = run(self.tmp, "--check", self.out)
        self.assertEqual(code, 1)
        # the marker x-principal-also is only legal on ingest, so the operation is refused by name
        self.assertIn("POST /v1/transactions", out)

    def test_also_removed_check_fails_naming_row(self):
        block = self.tx_block()
        self.assertIn(TX_ALSO, block)
        self.edit("ingestion", block, block.replace(TX_ALSO, ""))
        code, out = run(self.tmp, "--check", self.out)
        self.assertEqual(code, 1)
        self.assertIn("post\\t/v1/transactions\\tingest\\t-", out)

    def test_also_wrong_case_write_fails(self):
        block = self.tx_block()
        self.edit("ingestion", block, block.replace(TX_ALSO, "      x-principal-also: Certification\n"))
        code, out = run(self.tmp, "--write", self.out)
        self.assertEqual(code, 1)
        self.assertIn("POST /v1/transactions", out)

    def test_also_on_non_ingest_write_fails(self):
        self.append_paths("ingestion", "  /v1/probe:\n    get:\n      x-principal: reporting\n" + TX_ALSO)
        code, out = run(self.tmp, "--write", self.out)
        self.assertEqual(code, 1)
        self.assertIn("GET /v1/probe", out)

    def test_missing_x_principal_write_fails_naming_it(self):
        self.append_paths("ingestion", "  /v1/probe:\n    get:\n      operationId: probe\n")
        code, out = run(self.tmp, "--write", self.out)
        self.assertEqual(code, 1)
        self.assertIn("GET /v1/probe", out)

    def test_unknown_scope_write_fails(self):
        self.append_paths("ingestion", "  /v1/probe:\n    get:\n      x-principal: ingesst\n")
        code, out = run(self.tmp, "--write", self.out)
        self.assertEqual(code, 1)
        self.assertIn("ingesst", out)

    def test_duplicate_method_path_write_fails(self):
        self.append_paths("reconciliation", self.tx_block())
        code, out = run(self.tmp, "--write", self.out)
        self.assertEqual(code, 1)
        self.assertIn("duplicates", out)

    def test_internal_prefix_does_not_change_file(self):
        self.append_paths("ingestion", "  /internal/v1/probe:\n    post:\n      x-principal: ingest\n")
        code, out = run(self.tmp, "--check", self.out)
        self.assertEqual(code, 0, out)

    def test_v10_prefix_does_not_change_file(self):
        self.append_paths("ingestion", "  /v10/probe:\n    get:\n      x-principal: ingest\n")
        code, out = run(self.tmp, "--check", self.out)
        self.assertEqual(code, 0, out)

    def test_any_byte_difference_fails_check(self):
        for suffix in ("\n", " "):
            with open(self.out, "a", encoding="utf-8") as fh:
                fh.write(suffix)
            code, _ = run(self.tmp, "--check", self.out)
            self.assertEqual(code, 1)
            shutil.copy(TSV, self.out)

    def test_write_is_stable(self):
        code, _ = run(self.tmp, "--write", self.out)
        self.assertEqual(code, 0)
        self.assertEqual(read(self.out, "rb"), read(TSV, "rb"))


if __name__ == "__main__":
    unittest.main()
