#!/usr/bin/env python3
"""CI-FAST plan item A3 (owner ruling 2026-10-18, option b): decide whether a merge-request pipeline is a re-run
after a rebase only.

It is when all of these hold:
  1. an earlier pipeline of this merge request succeeded on commit OLD;
  2. the merge request's own change is the same patch on OLD and on HEAD (git patch-id of base..tip);
  3. the files the target branch changed between the two bases and the files of the merge request are disjoint;
  4. none of those files is an aggregate or generated path (GST_REBASE_AGGREGATE_PATHS, fnmatch, `*` crosses `/`).
Then it writes GST_REBASE_ONLY=true to the dotenv file, and the check jobs named in GST_REBASE_SKIP_JOBS end at
once (.gst-rebase-skip); the integration set (drift checks, breaking gates, codegen, publish guard) still runs.
Anything else, including any error, writes GST_REBASE_ONLY=false and every job runs in full.

Usage: rebase_check.py OUT_DOTENV  (reads the CI_* variables; OLD may be given as GST_REBASE_OLD_SHA for tests)
"""
import fnmatch, json, os, subprocess, sys, urllib.request


def git(*args):
    return subprocess.run(["git", *args], check=True, capture_output=True, text=True).stdout


def patch_id(base, tip):
    diff = subprocess.run(["git", "diff", "--no-color", base, tip], check=True, capture_output=True).stdout
    out = subprocess.run(["git", "patch-id", "--stable"], input=diff, check=True, capture_output=True).stdout
    return out.decode().split()[0] if out.strip() else ""


def files(base, tip):
    return {f for f in git("diff", "--name-only", base, tip).splitlines() if f}


def previous_green_sha(env):
    """Newest successful pipeline of this merge request on another commit, or None."""
    if env.get("GST_REBASE_OLD_SHA"):
        return env["GST_REBASE_OLD_SHA"]
    url = (f'{env["CI_API_V4_URL"]}/projects/{env["CI_PROJECT_ID"]}/pipelines?status=success'
           f'&source=merge_request_event&ref=refs/merge-requests/{env["CI_MERGE_REQUEST_IID"]}/head&per_page=20')
    req = urllib.request.Request(url, headers={"JOB-TOKEN": env["CI_JOB_TOKEN"]})
    with urllib.request.urlopen(req, timeout=30) as r:
        for p in json.load(r):
            if p.get("sha") and p["sha"] != env["CI_COMMIT_SHA"]:
                return p["sha"]
    return None


def decide(env):
    """Returns (rebase_only, reason)."""
    head, new_base, target = env["CI_COMMIT_SHA"], env["CI_MERGE_REQUEST_DIFF_BASE_SHA"], env["CI_MERGE_REQUEST_TARGET_BRANCH_NAME"]
    old = previous_green_sha(env)
    if not old:
        return False, "no earlier successful pipeline of this merge request"
    if not env.get("GST_REBASE_OLD_SHA"):
        subprocess.run(["git", "fetch", "-q", "--depth=500", "origin", old, f"+refs/heads/{target}:refs/gst/target"],
                       check=True, capture_output=True)
    old_base = git("merge-base", old, new_base).strip()
    if old_base == new_base:
        return False, f"earlier pipeline {old[:8]} had the same base: the merge request itself changed"
    if patch_id(old_base, old) != patch_id(new_base, head):
        return False, f"the merge request's change differs from the one {old[:8]} checked"
    mine, theirs = files(new_base, head), files(old_base, new_base)
    both = sorted(mine & theirs)
    if both:
        return False, "the target branch changed files of this merge request: " + " ".join(both[:10])
    pats = env.get("GST_REBASE_AGGREGATE_PATHS", "").split()
    agg = sorted(f for f in mine | theirs if any(fnmatch.fnmatch(f, p) for p in pats))
    if agg:
        return False, "aggregate or generated paths changed: " + " ".join(agg[:10])
    return True, (f"rebase only: same change as green {old[:8]}; target branch changed {len(theirs)} other file(s), "
                  "none shared or aggregate")


def main():
    out = sys.argv[1]
    try:
        only, reason = decide(os.environ)
    except Exception as e:  # any failure means a full run
        only, reason = False, f"check failed ({type(e).__name__}: {e}), full run"
    print(f"gst-ci: rebase check: {'GST_REBASE_ONLY=true' if only else 'full run'}: {reason}")
    with open(out, "w") as f:
        f.write(f"GST_REBASE_ONLY={'true' if only else 'false'}\n")


if __name__ == "__main__":
    main()
