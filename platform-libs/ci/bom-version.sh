#!/bin/sh
# ci/bom-version.sh — platform-libs-bom has no hand-edited version or changelog file.
#
# Reads the latest platform-libs-bom version in this project's GitLab Maven registry (read
# with CI_JOB_TOKEN), compares the constraint set the current build resolves with that
# published POM, and decides:
#   - same constraint set      -> BOM_VERSION=<latest published>, BOM_ACTION=skip
#   - different constraint set -> BOM_VERSION=<latest minor + 1>.0, BOM_ACTION=publish, and
#                                 build/bom-changelog.txt holds the entry (it is published
#                                 inside the artefact as the `changelog` txt file, and printed
#                                 to the job log; nothing is committed or pushed)
# Writes BOM_VERSION / BOM_ACTION to build/bom.env for the caller to source. Fails (never
# guesses) if the registry cannot be read or holds no BOM version.
# Needs: CI_API_V4_URL, CI_PROJECT_ID, CI_JOB_TOKEN; the gradle wrapper in the current dir.
set -eu

group_path="com/dynokore/platform/platform-libs-bom"
registry="${CI_API_V4_URL}/projects/${CI_PROJECT_ID}/packages/maven/${group_path}"
mkdir -p build
get() { curl -sf --header "JOB-TOKEN: ${CI_JOB_TOKEN}" "$1" -o "$2"; }

# One "group:artifact:version" line per managed dependency, sorted.
constraints() {
  tr -d '\n\r \t' < "$1" \
    | sed 's#.*<dependencyManagement>##; s#</dependencyManagement>.*##' \
    | sed 's#</dependency>#\n#g' \
    | sed -n 's#.*<groupId>\([^<]*\)</groupId><artifactId>\([^<]*\)</artifactId><version>\([^<]*\)</version>.*#\1:\2:\3#p' \
    | sort
}

# The resolved constraint set of the current checkout (the version is irrelevant to the compare).
./gradlew :platform-libs-bom:generatePomFileForMavenPublication -PbomVersion=0.0.0-compare --stacktrace
constraints platform-libs-bom/build/publications/maven/pom-default.xml > build/bom-new.txt

# A version that did not resolve ("unspecified") must never be published.
if grep -q ':unspecified$' build/bom-new.txt; then
  echo "gst-ci: platform-libs-bom constraint set has an unspecified version:"; grep ':unspecified$' build/bom-new.txt
  exit 1
fi

get "${registry}/maven-metadata.xml" build/bom-metadata.xml \
  || { echo "gst-ci: cannot read platform-libs-bom maven-metadata.xml from the registry"; exit 1; }
latest="$(sed -n 's#.*<version>\([0-9][0-9.]*\)</version>.*#\1#p' build/bom-metadata.xml | sort -V | tail -1)"
[ -n "$latest" ] || { echo "gst-ci: no platform-libs-bom version in the registry"; exit 1; }
get "${registry}/${latest}/platform-libs-bom-${latest}.pom" build/bom-published.pom \
  || { echo "gst-ci: cannot read platform-libs-bom ${latest} pom"; exit 1; }
constraints build/bom-published.pom > build/bom-old.txt

# Only the tip of origin/main publishes: an older main pipeline (rerun, or queued behind a newer
# merge) must not publish a BOM built from superseded constraints.
if [ -n "${CI_COMMIT_BRANCH:-}" ] && [ "${CI_COMMIT_BRANCH}" = "${CI_DEFAULT_BRANCH:-}" ]; then
  git fetch -q origin "${CI_DEFAULT_BRANCH}" || { echo "gst-ci: cannot fetch origin/${CI_DEFAULT_BRANCH}"; exit 1; }
  if [ "$(git rev-parse FETCH_HEAD)" != "$(git rev-parse HEAD)" ]; then
    echo "gst-ci: this commit is not the tip of origin/${CI_DEFAULT_BRANCH}: platform-libs-bom not published by this pipeline"
    printf 'BOM_VERSION=%s\nBOM_ACTION=skip\n' "$latest" > build/bom.env
    exit 0
  fi
fi

if cmp -s build/bom-old.txt build/bom-new.txt; then
  echo "gst-ci: platform-libs-bom constraint set equals ${latest}: nothing to publish"
  printf 'BOM_VERSION=%s\nBOM_ACTION=skip\n' "$latest" > build/bom.env
  exit 0
fi

major="${latest%%.*}"; rest="${latest#*.}"; minor="${rest%%.*}"
next="${major}.$((minor + 1)).0"
# The next version must not exist yet (a stale metadata listing must never lead to an overwrite).
code="$(curl -s -o /dev/null -w '%{http_code}' --header "JOB-TOKEN: ${CI_JOB_TOKEN}" "${registry}/${next}/platform-libs-bom-${next}.pom")"
[ "$code" = "404" ] || { echo "gst-ci: platform-libs-bom ${next} pom answered HTTP ${code}, expected 404: refusing to publish"; exit 1; }
{
  echo "platform-libs-bom ${next} ($(date -u +%Y-%m-%d), pipeline ${CI_PIPELINE_ID:-local}, commit ${CI_COMMIT_SHORT_SHA:-local}); previous ${latest}"
  diff build/bom-old.txt build/bom-new.txt | sed -n 's/^< /removed or changed from: /p; s/^> /added or changed to:    /p'
} > build/bom-changelog.txt
echo "gst-ci: platform-libs-bom changelog entry:"
sed 's/^/  /' build/bom-changelog.txt
printf 'BOM_VERSION=%s\nBOM_ACTION=publish\n' "$next" > build/bom.env
