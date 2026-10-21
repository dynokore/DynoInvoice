#!/bin/sh
# INV-T-4981 (INV-Q-8039): pulls the certification-kit Access Point simulator image by the digest pinned in
# `simulator-image.digest` and tags it certkit/access-point-simulator:dev for the Testcontainers lookup. Fails closed:
# a missing file, a line that is not exactly sha256:<64 lowercase hex>, an empty INV_REGISTRY, or a registry digest
# that differs from the file. Usage: ci/pull-simulator.sh [digest-file]; GST_PULL_DRY_RUN=true stops after validation.
set -eu
file="${1:-simulator-image.digest}"
[ -f "$file" ] || { echo "pull-simulator: $file is missing"; exit 1; }
[ "$(wc -l < "$file")" -eq 1 ] || { echo "pull-simulator: $file must hold exactly one line"; exit 1; }
digest="$(cat "$file")"
printf '%s\n' "$digest" | grep -Eq '^sha256:[0-9a-f]{64}$' || { echo "pull-simulator: $file does not hold sha256:<64 lowercase hex>"; exit 1; }
[ -n "${INV_REGISTRY:-}" ] || { echo "pull-simulator: INV_REGISTRY is empty"; exit 1; }
repo="${INV_REGISTRY}/invoice/certification-kit/access-point-simulator"
if [ "${GST_PULL_DRY_RUN:-}" = "true" ]; then echo "pull-simulator: ${repo}@${digest} (dry run)"; exit 0; fi
if [ "${GST_REGISTRY_INSECURE:-}" = "true" ]; then tls=false; else tls=true; fi
reported="$(skopeo inspect --tls-verify="$tls" --format '{{.Digest}}' "docker://${repo}@${digest}")"
[ "$reported" = "$digest" ] || { echo "pull-simulator: registry reports ${reported}, the file pins ${digest}"; exit 1; }
skopeo copy --src-tls-verify="$tls" "docker://${repo}@${digest}" "docker-daemon:certkit/access-point-simulator:dev"
echo "pull-simulator: ${repo}@${digest} tagged certkit/access-point-simulator:dev"
