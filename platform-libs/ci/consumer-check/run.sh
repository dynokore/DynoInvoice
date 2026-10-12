#!/bin/sh
# ci/consumer-check/run.sh <platform-libs-bom version> <maven registry URL> [gradle command] — INV-T-4356.
#
# Runs the outside-consumer check (settings.gradle.kts) against the BOM version just published, under strict
# dependency verification (STK-72). The check is its own Gradle build, so its gradle/verification-metadata.xml is
# written here, never committed:
#   1. the repository's own gradle/verification-metadata.xml and keyring are copied in;
#   2. a dry run (--write-verification-metadata sha256 --dry-run) lists every artefact this consumer downloads;
#   3. each com.dynokore.platform artefact in it (versions change with every publish, so the repository's metadata
#      cannot pin them) must have the SHA-256 of the .sha256 checksum the registry holds for it, published with it by
#      maven-publish;
#   4. this build's metadata is the repository's own plus exactly those entries, and `check` runs with verification
#      strict, so a third-party artefact the repository's metadata does not verify fails the job.
# Anything else, a download error, a missing checksum or a failing check, fails the job: never skipped (P20/P28).
# Needs CI_JOB_TOKEN (CI) or GST_REGISTRY_TOKEN (local) to read the registry.
set -eu
bom_version="$1"
registry="$2"
gradle="${3:-./gradlew}"
dir=ci/consumer-check
[ -n "$bom_version" ] || { echo "consumer-check: no platform-libs-bom version (P20/P28)"; exit 1; }

mkdir -p "$dir/gradle"
cp gradle/verification-metadata.xml gradle/verification-keyring.keys gradle/verification-keyring.gpg "$dir/gradle/"
mkdir -p "$dir/build"
rm -f "$dir/gradle/verification-metadata.dryrun.xml"

$gradle -p "$dir" --write-verification-metadata sha256 --dry-run help \
  "-PbomVersion=$bom_version" "-PplatformLibsRegistry=$registry" --stacktrace

# One "group:name:version:file sha256" line per com.dynokore.platform artefact entry of the dry-run metadata.
platform_artefacts() {
  awk '
    /<component / { match($0, /group="[^"]*"/); g = substr($0, RSTART + 7, RLENGTH - 8)
                    match($0, /name="[^"]*"/); n = substr($0, RSTART + 6, RLENGTH - 7)
                    match($0, /version="[^"]*"/); v = substr($0, RSTART + 9, RLENGTH - 10) }
    /<artifact / { match($0, /name="[^"]*"/); a = substr($0, RSTART + 6, RLENGTH - 7); s = "-" }
    /<sha256 / && a != "" { match($0, /value="[^"]*"/); s = substr($0, RSTART + 7, RLENGTH - 8) }
    /<\/artifact>/ { if (g == "com.dynokore.platform") print g ":" n ":" v ":" a " " s; a = "" }
  ' "$1" | sort
}
dryrun="$dir/gradle/verification-metadata.dryrun.xml"
platform_artefacts "$dryrun" > "$dir/build/platform-artefacts.txt"

if [ -n "${CI_JOB_TOKEN:-}" ]; then header="JOB-TOKEN: ${CI_JOB_TOKEN}"; else header="PRIVATE-TOKEN: ${GST_REGISTRY_TOKEN:-}"; fi
bad=0; ours=0
while read -r key sha; do
  rest=${key#*:}; name=${rest%%:*}; rest=${rest#*:}; version=${rest%%:*}; file=${rest#*:}
  published=$(curl -sf --header "$header" "${registry}/com/dynokore/platform/${name}/${version}/${file}.sha256" | tr -d ' \r\n' || true)
  if [ -z "$published" ]; then
    echo "consumer-check: the registry has no .sha256 checksum for ${name}/${version}/${file}"
    bad=1
  elif [ "$published" != "$sha" ]; then
    echo "consumer-check: ${name}/${version}/${file} SHA-256 ${sha} differs from the registry's checksum ${published}"
    bad=1
  else
    ours=$((ours + 1))
  fi
done < "$dir/build/platform-artefacts.txt"
[ "$bad" = 0 ] || exit 1
[ "$ours" -gt 0 ] || { echo "consumer-check: no com.dynokore.platform artefact was downloaded (P20/P28)"; exit 1; }
echo "consumer-check: ${ours} com.dynokore.platform artefacts match the registry's SHA-256 checksums"

# The repository's metadata plus the com.dynokore.platform entries checked above, nothing else: every third-party
# artefact must verify against the repository's own entries and trusted keys in the strict run below.
awk -v entries="$dir/build/platform-components.xml" '
  /<component group="com.dynokore.platform"/ { keep = 1 }
  keep { print > entries }
  keep && /<\/component>/ { keep = 0 }
' "$dryrun"
awk -v entries="$dir/build/platform-components.xml" '
  /<\/components>/ { while ((getline line < entries) > 0) print line }
  { print }
' gradle/verification-metadata.xml > "$dir/gradle/verification-metadata.xml"
rm -f "$dryrun"

$gradle -p "$dir" check "-PbomVersion=$bom_version" "-PplatformLibsRegistry=$registry" --stacktrace
