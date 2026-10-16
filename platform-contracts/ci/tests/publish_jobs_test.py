"""INV-T-5317: fails closed when catalogue-publish, bundle-publish or publish-guard-dry-run lose the immutable-release guard.

Parses .gitlab-ci.yml, resolves `!reference` of local hidden jobs, and keeps a reference to a section of platform-build
(.gst-publish-prepare, .gst-publish-upload, .gst-artefact-guard-prepare, .gst-guard-generic) as an opaque marker line
`@section <name>`: the guard's own behaviour is tested in platform-build. Needs PyYAML.
"""
import copy
import os
import re
import unittest

import yaml

CI_FILE = os.path.join(os.path.dirname(__file__), "..", "..", ".gitlab-ci.yml")

PREPARE = "@section .gst-publish-prepare"
UPLOAD = "@section .gst-publish-upload"
GENERIC = "@section .gst-guard-generic"
DRY_ON = re.compile(r"^GST_GUARD_DRY=--dry-run$")
DRY_OFF = re.compile(r"^GST_GUARD_DRY=$")
MAVEN_CHECK = re.compile(r"python3 \"\$GST_RELEASE_DIR/publish_guard\.py\"")
STAGE_GRADLE = re.compile(r"gradle .*publish\b.*-I \"\$GST_RELEASE_DIR/stage-publish\.init\.gradle\".*-PgstStageDir=\"\$GST_PUBLISH_STAGE\"")
PUT_OR_POST = re.compile(r"(--request|-X)\s+(PUT|POST)\b|--upload-file|\s-T\s", re.I)


class Ref(list):
    pass


class Loader(yaml.SafeLoader):
    pass


Loader.add_constructor("!reference", lambda loader, node: Ref(loader.construct_sequence(node)))


def load(text):
    return yaml.load(text, Loader=Loader)


def resolve(doc, key, seen=()):
    """The script of job `key` as a flat list of lines; a section not defined in this file becomes a marker line."""
    out = []
    for item in doc[key]["script"]:
        if isinstance(item, Ref):
            name, field = item[0], item[1]
            if name in seen:
                raise ValueError(f"circular !reference to {name}")
            if name in doc:
                out.extend(resolve_field(doc, name, field, seen + (key,)))
            else:
                out.append(f"@section {name}" if field == "script" else f"@section {name}.{field}")
        else:
            out.append(" ".join(str(item).split()))
    return out


def resolve_field(doc, name, field, seen):
    if field != "script":
        raise ValueError(f"unsupported !reference field {field}")
    return resolve(doc, name, seen)


def is_dry_check(line):
    """A direct guard call is a check only with --dry-run (the Maven guard has no section with a dry run)."""
    return bool(MAVEN_CHECK.search(line)) and re.search(r"\s--dry-run(\s|$)", line) is not None


def analyse(lines):
    """Walks the script keeping the shell state of GST_GUARD_DRY; returns (checks, uploads) as lists of line indexes."""
    dry, checks, uploads = False, [], []
    for i, line in enumerate(lines):
        if DRY_ON.match(line):
            dry = True
        elif DRY_OFF.match(line):
            dry = False
        elif line == GENERIC:
            (checks if dry else uploads).append(i)
        elif line == UPLOAD:
            uploads.append(i)
        elif is_dry_check(line):
            checks.append(i)
    return checks, uploads


FORBIDDEN_OUTSIDE_SECTIONS = ("gstMavenUrl", "gstMavenPassword", "gstMavenUser", "CI_JOB_TOKEN")


def forbidden_lines(lines):
    """Lines that reach a registry by themselves: the only line allowed to hold the job token is the Maven dry-run check."""
    found = []
    for line in lines:
        if line.startswith("@section "):
            continue
        if is_dry_check(line):
            if PUT_OR_POST.search(line):
                found.append(line)
            continue
        if any(w in line for w in FORBIDDEN_OUTSIDE_SECTIONS) or PUT_OR_POST.search(line) or "packages/" in line:
            found.append(line)
    return found


class PublishJobs(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        with open(CI_FILE) as f:
            cls.doc = load(f.read())

    # -- the same assertions on any document, so the probes below can run them on a mutated copy --------------------
    def check_publish_job(self, doc, key, with_maven):
        self.assertIn(key, doc, f"{key}: job missing")
        job = doc[key]
        lines = resolve(doc, key)
        variables = job.get("variables", {})
        if with_maven:
            self.assertEqual("true", str(variables.get("GST_PUBLISH_REQUIRE_STAGE", "")).lower(), f"{key}: GST_PUBLISH_REQUIRE_STAGE")
        checks, uploads = analyse(lines)
        self.assertTrue(uploads, f"{key}: no upload section (.gst-publish-upload / .gst-guard-generic) is called")
        self.assertIn(GENERIC, [lines[i] for i in uploads], f"{key}: the generic packages are not uploaded through .gst-guard-generic")
        self.assertTrue(checks, f"{key}: no --dry-run check before the first upload")
        self.assertLess(max(checks), min(uploads), f"{key}: a --dry-run check comes after the first upload")
        self.assertEqual([], forbidden_lines(lines), f"{key}: a line reaches the registry outside the guard sections")
        self.assertIn(GENERIC, [lines[i] for i in checks], f"{key}: the generic files are not checked with --dry-run")
        if with_maven:
            self.assertIn(PREPARE, lines, f"{key}: .gst-publish-prepare is not called")
            stage = [i for i, l in enumerate(lines) if STAGE_GRADLE.search(l)]
            self.assertTrue(stage, f"{key}: no Gradle publish into the staging directory (-I stage-publish.init.gradle -PgstStageDir)")
            self.assertLess(min(stage), min(uploads), f"{key}: staging does not come before the upload")
            self.assertTrue(any(lines[i] != GENERIC and lines[i] != UPLOAD for i in checks), f"{key}: the Maven stage is not checked with --dry-run")
            self.assertIn(UPLOAD, lines, f"{key}: the Maven upload section is missing")
            self.assertEqual("$CI_PROJECT_ID", variables.get("MAVEN_REGISTRY_PROJECT_ID"), f"{key}: MAVEN_REGISTRY_PROJECT_ID")
        else:
            self.assertNotIn(UPLOAD, lines)
        self.assertIn("@section .gst-artefact-guard-prepare", lines, f"{key}: .gst-artefact-guard-prepare is not called")

    def check_dry_run_job(self, doc):
        keys = [k for k in doc if k == "publish-guard-dry-run"]
        self.assertEqual(["publish-guard-dry-run"], keys, "publish-guard-dry-run: the job must exist exactly once")
        job = doc["publish-guard-dry-run"]
        self.assertEqual("publish", job.get("stage"), "publish-guard-dry-run: stage")
        self.assertEqual([], job.get("needs"), "publish-guard-dry-run: needs must be []")
        conditions = [r.get("if", "") for r in job.get("rules", [])]
        self.assertTrue(any('$CI_PIPELINE_SOURCE == "merge_request_event"' in c for c in conditions),
                        "publish-guard-dry-run: rules lack the merge_request_event condition")
        self.assertFalse(any(r.get("when") == "never" for r in job.get("rules", [])),
                         "publish-guard-dry-run: a rule disables the job")
        self.assertFalse(job.get("allow_failure", False), "publish-guard-dry-run: allow_failure must be absent or false")
        lines = resolve(doc, "publish-guard-dry-run")
        text = "\n".join(lines)
        self.assertIn("--dry-run", text, "publish-guard-dry-run: no --dry-run")
        self.assertNotIn(UPLOAD, lines, "publish-guard-dry-run: calls .gst-publish-upload")
        checks, uploads = analyse(lines)
        self.assertEqual([], uploads, "publish-guard-dry-run: a guard call without --dry-run (an upload)")
        self.assertTrue(checks, "publish-guard-dry-run: no check")
        self.assertIsNone(re.search(r"(--request|-X)\s+(PUT|POST)\b", text, re.I), "publish-guard-dry-run: curl PUT or POST")
        self.assertNotIn("gstMavenPassword", text, "publish-guard-dry-run: gstMavenPassword")
        self.assertEqual([], forbidden_lines(lines), "publish-guard-dry-run: a line reaches the registry outside the guard sections")
        self.assertTrue(any(STAGE_GRADLE.search(l) for l in lines), "publish-guard-dry-run: the Maven stage is not built")
        self.assertIn(GENERIC, [lines[i] for i in checks], "publish-guard-dry-run: the generic files are not checked")
        self.assertIn("printTrainVersion", text, "publish-guard-dry-run: the bundle zip is not built")
        self.assertIn("catalogue", text)

    # -- the repository's own file ----------------------------------------------------------------------------------
    def test_catalogue_publish(self):
        self.check_publish_job(self.doc, "catalogue-publish", True)

    def test_bundle_publish(self):
        self.check_publish_job(self.doc, "bundle-publish", False)

    def test_publish_guard_dry_run(self):
        self.check_dry_run_job(self.doc)

    def test_no_direct_put_in_any_publish_job(self):
        for key in ("catalogue-publish", "bundle-publish", "publish-guard-dry-run"):
            self.assertEqual([], forbidden_lines(resolve(self.doc, key)), key)

    # -- probes: each mutation must make a named assertion fail -----------------------------------------------------
    def mutated(self, key, edit):
        doc = copy.deepcopy(self.doc)
        edit(doc[key])
        return doc

    def fails(self, message, call, *args):
        with self.assertRaises(AssertionError) as ctx:
            call(*args)
        self.assertIn(message, str(ctx.exception))

    def test_probe_delete_upload_section(self):
        def edit(job):
            job["script"] = [i for i in job["script"] if not (isinstance(i, Ref) and i[0] == ".gst-publish-upload")]
        doc = self.mutated("catalogue-publish", edit)
        # the Maven upload is also reached through a local hidden job in other layouts; here it is a direct section
        self.fails("the Maven upload section is missing", self.check_publish_job, doc, "catalogue-publish", True)

    def test_probe_delete_generic_upload(self):
        def edit(job):
            job["script"] = job["script"][:-1]
        doc = self.mutated("bundle-publish", edit)
        self.fails("no upload section", self.check_publish_job, doc, "bundle-publish", False)

    def test_probe_restore_direct_curl_put(self):
        def edit(job):
            job["script"].append('curl --fail --request PUT --header "JOB-TOKEN: ${CI_JOB_TOKEN}" --upload-file x "${CI_API_V4_URL}/projects/${CI_PROJECT_ID}/packages/generic/p/1/x"')
        for key, maven in (("catalogue-publish", True), ("bundle-publish", False)):
            self.fails("outside the guard sections", self.check_publish_job, self.mutated(key, edit), key, maven)

    def test_probe_move_upload_before_checks(self):
        def edit(job):
            s = job["script"]
            i = next(n for n, x in enumerate(s) if isinstance(x, Ref) and x[0] == ".gst-publish-upload")
            s.insert(0, s.pop(i))
        doc = self.mutated("catalogue-publish", edit)
        self.fails("comes after the first upload", self.check_publish_job, doc, "catalogue-publish", True)

    def test_probe_move_generic_upload_before_checks(self):
        def edit(job):
            s = job["script"]
            s.insert(1, Ref([".gst-guard-generic", "script"]))
        doc = self.mutated("bundle-publish", edit)
        self.fails("comes after the first upload", self.check_publish_job, doc, "bundle-publish", False)

    def test_probe_require_stage_unset(self):
        doc = self.mutated("catalogue-publish", lambda j: j["variables"].pop("GST_PUBLISH_REQUIRE_STAGE"))
        self.fails("GST_PUBLISH_REQUIRE_STAGE", self.check_publish_job, doc, "catalogue-publish", True)

    def test_probe_gradle_gets_registry(self):
        def edit(job):
            job["script"].insert(1, 'gradle -p catalogue publish -PgstMavenPassword="${CI_JOB_TOKEN}"')
        doc = self.mutated("catalogue-publish", edit)
        self.fails("outside the guard sections", self.check_publish_job, doc, "catalogue-publish", True)

    def test_probe_delete_dry_run_flag(self):
        def edit(job):
            job["script"] = [i for i in job["script"] if not (isinstance(i, str) and i == "GST_GUARD_DRY=--dry-run")]
        self.fails("without --dry-run", self.check_dry_run_job, self.mutated("publish-guard-dry-run", edit))

    def test_probe_delete_dry_run_flag_everywhere(self):
        doc = copy.deepcopy(self.doc)
        doc[".maven-guard-dry-run"]["script"] = [s.replace("--dry-run", "") for s in doc[".maven-guard-dry-run"]["script"]]
        doc["publish-guard-dry-run"]["script"] = [i for i in doc["publish-guard-dry-run"]["script"] if i != "GST_GUARD_DRY=--dry-run"]
        self.fails("--dry-run", self.check_dry_run_job, doc)

    def test_probe_allow_failure(self):
        doc = self.mutated("publish-guard-dry-run", lambda j: j.update(allow_failure=True))
        self.fails("allow_failure", self.check_dry_run_job, doc)

    def test_probe_main_branch_condition(self):
        doc = self.mutated("publish-guard-dry-run", lambda j: j.update(rules=[{"if": "$CI_COMMIT_BRANCH == $CI_DEFAULT_BRANCH"}]))
        self.fails("merge_request_event", self.check_dry_run_job, doc)

    def test_probe_add_upload_section(self):
        doc = self.mutated("publish-guard-dry-run", lambda j: j["script"].append(Ref([".gst-publish-upload", "script"])))
        self.fails("calls .gst-publish-upload", self.check_dry_run_job, doc)

    def test_probe_rename_job(self):
        doc = copy.deepcopy(self.doc)
        doc["publish-guard-dryrun"] = doc.pop("publish-guard-dry-run")
        self.fails("must exist exactly once", self.check_dry_run_job, doc)

    def test_probe_delete_job(self):
        doc = copy.deepcopy(self.doc)
        del doc["publish-guard-dry-run"]
        self.fails("must exist exactly once", self.check_dry_run_job, doc)

    def test_probe_dry_run_curl_post(self):
        doc = self.mutated("publish-guard-dry-run", lambda j: j["script"].append('curl --request POST "$X"'))
        self.fails("curl PUT or POST", self.check_dry_run_job, doc)

    def test_probe_dry_run_needs(self):
        doc = self.mutated("publish-guard-dry-run", lambda j: j.pop("needs"))
        self.fails("needs must be []", self.check_dry_run_job, doc)

    def test_probe_dry_run_guard_call_without_flag(self):
        def edit(job):
            job["script"].append('GUARD_PASSWORD="${CI_JOB_TOKEN}" python3 "$GST_RELEASE_DIR/publish_guard.py" --stage s --registry r')
        self.fails("outside the guard sections", self.check_dry_run_job, self.mutated("publish-guard-dry-run", edit))


if __name__ == "__main__":
    unittest.main()
