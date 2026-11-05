#!/bin/sh
# INV-T-4946 (INV-Q-9655): pulls a certification-kit simulator image by the digest pinned in its digest file and tags it
# certkit/<image>:dev for the Testcontainers lookup (svc-transport's script, INV-T-4981, generalised to an image name).
# Fails closed: a missing file, a line that is not exactly sha256:<64 lowercase hex>, an empty INV_REGISTRY, or a
# registry digest that differs from the file. Usage: ci/pull-simulator.sh <image> <digest-file>;
# GST_PULL_DRY_RUN=true stops after validation.
set -eu
[ "$#" -eq 2 ] || { echo "pull-simulator: usage: pull-simulator.sh <image> <digest-file>"; exit 1; }
image="$1"
file="$2"
printf '%s\n' "$image" | grep -Eq '^[a-z][a-z0-9-]*$' || { echo "pull-simulator: image name '$image' is not a simple name"; exit 1; }
[ -f "$file" ] || { echo "pull-simulator: $file is missing"; exit 1; }
[ "$(wc -l < "$file")" -eq 1 ] || { echo "pull-simulator: $file must hold exactly one line"; exit 1; }
digest="$(cat "$file")"
printf '%s\n' "$digest" | grep -Eq '^sha256:[0-9a-f]{64}$' || { echo "pull-simulator: $file does not hold sha256:<64 lowercase hex>"; exit 1; }
[ -n "${INV_REGISTRY:-}" ] || { echo "pull-simulator: INV_REGISTRY is empty"; exit 1; }
repo="${INV_REGISTRY}/invoice/certification-kit/${image}"
if [ "${GST_PULL_DRY_RUN:-}" = "true" ]; then echo "pull-simulator: ${repo}@${digest} (dry run)"; exit 0; fi
if [ "${GST_REGISTRY_INSECURE:-}" = "true" ]; then tls=false; else tls=true; fi
reported="$(skopeo inspect --tls-verify="$tls" --format '{{.Digest}}' "docker://${repo}@${digest}")"
[ "$reported" = "$digest" ] || { echo "pull-simulator: registry reports ${reported}, the file pins ${digest}"; exit 1; }
skopeo copy --src-tls-verify="$tls" "docker://${repo}@${digest}" "docker-daemon:certkit/${image}:dev"
echo "pull-simulator: ${repo}@${digest} tagged certkit/${image}:dev"
