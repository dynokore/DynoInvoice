"""Tests for ci/rebase_check.py on throwaway git repositories (no network: GST_REBASE_OLD_SHA gives OLD)."""
import os, subprocess, sys, tempfile, unittest

sys.path.insert(0, os.path.join(os.path.dirname(__file__), ".."))
import rebase_check  # noqa: E402


def run(*a, cwd):
    return subprocess.run(a, cwd=cwd, check=True, capture_output=True, text=True).stdout.strip()


class RebaseCheckTest(unittest.TestCase):
    def setUp(self):
        self.d = tempfile.mkdtemp()
        g = lambda *a: run("git", *a, cwd=self.d)
        g("init", "-q", "-b", "main"); g("config", "user.email", "t@t"); g("config", "user.name", "t")
        self.g = g
        self.write("a/x.yaml", "x\n"); self.write("b/y.yaml", "y\n"); self.write("a/long.txt", "".join(f"l{i}\n" for i in range(20))); self.write("ci/tool.py", "t\n")
        g("add", "-A"); g("commit", "-qm", "base")
        self.base = g("rev-parse", "HEAD")

    def write(self, path, text):
        p = os.path.join(self.d, path); os.makedirs(os.path.dirname(p), exist_ok=True)
        open(p, "w").write(text)

    def commit(self, path, text, msg):
        self.write(path, text); self.g("add", "-A"); self.g("commit", "-qm", msg)
        return self.g("rev-parse", "HEAD")

    def scenario(self, main_path, mr_path="a/x.yaml", mr_text="x2\n", main_text="m\n"):
        """MR commit on base (OLD); main moves by one commit; MR rebased onto it (HEAD)."""
        self.g("checkout", "-qb", "mr")
        old = self.commit(mr_path, mr_text, "mr")
        self.g("checkout", "-q", "main")
        new_base = self.commit(main_path, main_text, "main moves")
        self.g("checkout", "-q", "mr"); self.g("rebase", "-q", "main")
        return old, new_base, self.g("rev-parse", "HEAD")

    def decide(self, old, new_base, head, **extra):
        env = {"CI_COMMIT_SHA": head, "CI_MERGE_REQUEST_DIFF_BASE_SHA": new_base,
               "CI_MERGE_REQUEST_TARGET_BRANCH_NAME": "main", "GST_REBASE_OLD_SHA": old,
               "GST_REBASE_AGGREGATE_PATHS": "ci/* .gitlab-ci.yml", **extra}
        cwd = os.getcwd(); os.chdir(self.d)
        try:
            return rebase_check.decide(env)
        finally:
            os.chdir(cwd)

    def test_disjoint_rebase_is_rebase_only(self):
        only, reason = self.decide(*self.scenario("b/y.yaml"))
        self.assertTrue(only, reason)

    def test_shared_file_runs_in_full(self):
        lines = [f"l{i}\n" for i in range(20)]
        mine, theirs = ["L0\n"] + lines[1:], lines[:19] + ["L19\n"]
        only, reason = self.decide(*self.scenario("a/long.txt", mr_path="a/long.txt", mr_text="".join(mine),
                                                  main_text="".join(theirs)))
        self.assertFalse(only); self.assertIn("changed files of this merge request", reason)

    def test_aggregate_path_on_target_runs_in_full(self):
        only, reason = self.decide(*self.scenario("ci/tool.py"))
        self.assertFalse(only); self.assertIn("aggregate", reason)

    def test_changed_merge_request_runs_in_full(self):
        old, new_base, head = self.scenario("b/y.yaml")
        head2 = self.commit("a/x.yaml", "x3\n", "mr round 2")
        only, reason = self.decide(old, new_base, head2)
        self.assertFalse(only); self.assertIn("differs", reason)

    def test_same_base_runs_in_full(self):
        self.g("checkout", "-qb", "mr")
        old = self.commit("a/x.yaml", "x2\n", "mr")
        head = self.commit("a/x.yaml", "x3\n", "mr round 2")
        only, reason = self.decide(old, self.base, head)
        self.assertFalse(only); self.assertIn("same base", reason)

    def test_no_earlier_pipeline_runs_in_full(self):
        old, new_base, head = self.scenario("b/y.yaml")
        saved = rebase_check.previous_green_sha
        rebase_check.previous_green_sha = lambda env: None
        try:
            only, reason = self.decide("", new_base, head)
        finally:
            rebase_check.previous_green_sha = saved
        self.assertFalse(only); self.assertIn("no earlier", reason)

    def test_main_writes_false_on_error(self):
        out = os.path.join(self.d, "rebase.env")
        env = {k: v for k, v in os.environ.items() if not k.startswith(("CI_", "GST_"))}
        subprocess.run([sys.executable, os.path.join(os.path.dirname(__file__), "..", "rebase_check.py"), out],
                       env=env, check=True, capture_output=True)
        self.assertEqual(open(out).read(), "GST_REBASE_ONLY=false\n")


if __name__ == "__main__":
    unittest.main()
