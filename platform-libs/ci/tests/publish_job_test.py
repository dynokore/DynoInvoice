#!/usr/bin/env python3
"""INV-T-5316: the maven-publish job of .gitlab-ci.yml publishes only through the immutable-release guard.

Parses .gitlab-ci.yml (no third-party module: the Gradle image has none), takes the `maven-publish` job's variables
and script, resolves `!reference [.gst-publish-prepare|.gst-publish-upload, script]` to marker lines (the sections
themselves live in platform-build's ci/publish.gitlab-ci.yml, tested there by INV-T-5257) and asserts:
  - prepare runs before any Gradle publish, upload after it;
  - no line passes gstMavenUrl / gstMavenPassword / CI_JOB_TOKEN to Gradle or PUTs to the registry itself;
  - GST_PUBLISH_REQUIRE_STAGE is "true";
  - the BOM is staged only for BOM_ACTION = publish.
Any unknown shape of the job fails (deny by default). `--probes` also runs the mutation probes: each broken copy of the
job must fail its named assertion.
"""
import re
import sys
from pathlib import Path

CI = Path(__file__).resolve().parents[2] / ".gitlab-ci.yml"
PREPARE, UPLOAD = "@@prepare@@", "@@upload@@"
REF = re.compile(r"^!reference \[\s*(\.gst-publish-(?:prepare|upload))\s*,\s*script\s*\]$")
STAGE_TASK = "PublicationToGstStageRepository"


def job_block(text, name):
    lines = text.splitlines()
    starts = [i for i, l in enumerate(lines) if l == name + ":"]
    if len(starts) != 1:
        raise AssertionError(f"job {name}: expected exactly one top-level definition, found {len(starts)}")
    out = []
    for l in lines[starts[0] + 1:]:
        if l and not l.startswith((" ", "#")):
            break
        out.append(l)
    return out


def section(block, key):
    """Lines of the 2-space-indented key `key:` of a job (until the next 2-space key)."""
    idx = [i for i, l in enumerate(block) if l == f"  {key}:"]
    if len(idx) != 1:
        raise AssertionError(f"job has {len(idx)} '{key}:' sections, expected 1")
    out = []
    for l in block[idx[0] + 1:]:
        if l.strip() and not l.startswith("   "):
            break
        out.append(l)
    return out


def script_items(block):
    items = []
    for l in section(block, "script"):
        if l.startswith("    - "):
            items.append([l[6:]])
        elif items and (l.startswith("      ") or not l.strip()):
            items[-1].append(l[6:])
        elif l.strip() and not l.lstrip().startswith("#"):
            raise AssertionError(f"unrecognised script line: {l!r}")
    resolved = []
    for item in items:
        first = item[0].strip()
        m = REF.match(first)
        if m:
            resolved.append(PREPARE if m.group(1).endswith("prepare") else UPLOAD)
        elif first.startswith("!reference"):
            raise AssertionError(f"unknown !reference: {first}")
        else:
            resolved.extend(item if first not in ("|", ">") else item[1:])
    return resolved


def variables(block):
    out = {}
    for l in section(block, "variables"):
        m = re.match(r'^    ([A-Z_]+): "(.*)"\s*$', l)
        if m:
            out[m.group(1)] = m.group(2)
    return out


def code_lines(lines):
    return [(i, l) for i, l in enumerate(lines) if l.strip() and not l.strip().startswith("#")]


def check(text):
    """Returns a list of failed assertion names (empty: all hold)."""
    block = job_block(text, "maven-publish")
    script = script_items(block)
    code = code_lines(script)
    failed = []
    prep = [i for i, l in code if l == PREPARE]
    upl = [i for i, l in code if l == UPLOAD]
    publish = [i for i, l in code if re.search(r"\./gradlew\b.*\bpublish[A-Za-z]*Publication", l) or STAGE_TASK in l]
    if len(prep) != 1:
        failed.append("prepare-section-present-once")
    if len(upl) != 1:
        failed.append("upload-section-present-once")
    if not publish:
        failed.append("gradle-publish-present")
    if prep and publish and not prep[0] < min(publish):
        failed.append("prepare-before-gradle-publish")
    if upl and publish and not upl[0] > max(publish):
        failed.append("upload-after-gradle-publish")
    for _, l in code:
        if re.search(r"gstMavenUrl|gstMavenPassword|gstMavenUser|CI_JOB_TOKEN\}?\"?\s*(\\|$)", l) and "./gradlew" in l:
            failed.append("no-registry-credential-to-gradle")
        if re.search(r"gstMavenUrl|gstMavenPassword", l):
            failed.append("no-registry-credential-to-gradle")
        if re.search(r"(-X\s*PUT|--request\s+PUT|--upload-file|\s-T\s|\bPUT\b)", l):
            failed.append("no-direct-registry-put")
    # CI_JOB_TOKEN may be read (JOB-TOKEN header on GET); it must never sit on a gradle command or in a -P property
    gradle_cmds = [l for _, l in code if "gradlew" in l]
    if any("CI_JOB_TOKEN" in l or "-PgstMaven" in l for l in gradle_cmds):
        failed.append("no-registry-credential-to-gradle")
    staged = [l for _, l in code if "-I " in l and "stage-publish.init.gradle" in l and "-PgstStageDir=" in l]
    if STAGE_TASK not in " ".join(script) or not staged:
        failed.append("gradle-publishes-into-stage")
    if variables(block).get("GST_PUBLISH_REQUIRE_STAGE") != "true":
        failed.append("require-stage-true")
    if variables(block).get("GST_PUBLISH_STAGE", "") == "":
        failed.append("stage-dir-variable-set")
    # the BOM is staged only for BOM_ACTION = publish: the loop that builds the task list must skip the BOM otherwise
    joined = "\n".join(l for _, l in code)
    if not re.search(r'if \[ "\$artifact" = "platform-libs-bom" \] && \[ "\$BOM_ACTION" != "publish" \]; then\n(.*\n)*?\s*echo [^\n]*not staged[^\n]*\n\s*else\n(.*\n)*?\s*tasks="\$tasks ', joined + "\n"):
        failed.append("bom-staged-only-for-publish")
    return sorted(set(failed))


def mutate(text, old, new):
    """Replaces the first `old` inside the maven-publish job only (other jobs may repeat the same lines)."""
    head, sep, tail = text.partition("\nmaven-publish:\n")
    assert sep and old in tail, f"probe target missing in maven-publish: {old!r}"
    return head + sep + tail.replace(old, new, 1)


def probes(text):
    cases = {
        "delete-upload-reference": (
            lambda t: mutate(t, "    - !reference [.gst-publish-upload, script]\n", ""),
            "upload-section-present-once"),
        "direct-gradle-publish": (
            lambda t: mutate(t, "    - !reference [.gst-publish-upload, script]\n",
                             "    - !reference [.gst-publish-upload, script]\n"
                             "    - ./gradlew :x:publishMavenPublicationToGstMavenRepository "
                             '-PgstMavenUrl="$CI_API_V4_URL/x" -PgstMavenPassword="$CI_JOB_TOKEN"\n'),
            "no-registry-credential-to-gradle"),
        "direct-put": (
            lambda t: mutate(t, "    - !reference [.gst-publish-upload, script]\n",
                             "    - !reference [.gst-publish-upload, script]\n"
                             '    - curl --request PUT --upload-file a.jar "$CI_API_V4_URL/x/a.jar"\n'),
            "no-direct-registry-put"),
        "unset-require-stage": (
            lambda t: mutate(t, '    GST_PUBLISH_REQUIRE_STAGE: "true"\n', ""),
            "require-stage-true"),
        "require-stage-false": (
            lambda t: mutate(t, 'GST_PUBLISH_REQUIRE_STAGE: "true"', 'GST_PUBLISH_REQUIRE_STAGE: "false"'),
            "require-stage-true"),
        "upload-before-staging": (
            lambda t: mutate(mutate(t, "    - !reference [.gst-publish-upload, script]\n", ""),
                             "    - !reference [.gst-publish-prepare, script]\n",
                             "    - !reference [.gst-publish-prepare, script]\n    - !reference [.gst-publish-upload, script]\n"),
            "upload-after-gradle-publish"),
        "prepare-after-staging": (
            lambda t: mutate(mutate(t, "    - !reference [.gst-publish-prepare, script]\n", ""),
                             "    - !reference [.gst-publish-upload, script]\n",
                             "    - !reference [.gst-publish-prepare, script]\n    - !reference [.gst-publish-upload, script]\n"),
            "prepare-before-gradle-publish"),
        "bom-always-staged": (
            lambda t: mutate(t, '[ "$BOM_ACTION" != "publish" ]', '[ "$BOM_ACTION" = "never" ]'),
            "bom-staged-only-for-publish"),
        "bom-condition-removed": (
            lambda t: mutate(t, 'if [ "$artifact" = "platform-libs-bom" ] && [ "$BOM_ACTION" != "publish" ]; then',
                             'if false; then'),
            "bom-staged-only-for-publish"),
    }
    bad = []
    for name, (fn, expected) in cases.items():
        got = check(fn(text))
        status = "ok" if expected in got else "NOT DETECTED"
        print(f"probe {name}: expects {expected}; failed assertions: {got or 'none'} -> {status}")
        if expected not in got:
            bad.append(name)
    return bad


def main():
    text = CI.read_text()
    failed = check(text)
    if failed:
        print("publish_job_test: FAILED assertions:", ", ".join(failed))
        return 1
    print("publish_job_test: maven-publish publishes through the guard: all assertions hold")
    if "--probes" in sys.argv:
        bad = probes(text)
        if bad:
            print("publish_job_test: probes not detected:", ", ".join(bad))
            return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
