#!/usr/bin/env bash
# Time-shift proof of the submission-bootstrap break-glass, inbox and filter tests (INV-T-17258, INV-Q-17250 A).
#
# Runs BreakGlassRestrictionTest, PayloadReadyDbTest and EndUserFilterTest of :submission-bootstrap once for each local
# time 00:30, 01:30, 12:00 and 23:59 on 2026-11-30 and on 2026-12-31 with BOTH clocks shifted to that instant:
#   * the JVM clock of the forked test JVM by libfaketime: an init script gives every Gradle Test task LD_PRELOAD and
#     FAKETIME="+<n>s", the offset from the real clock to the instant (the clock keeps running). Gradle itself keeps the
#     real clock: a client in the future cannot validate the TLS certificates of the dependency repositories;
#   * the database clock of the test Postgres by libfaketime inside the Testcontainers container. The tests start that
#     container themselves (SubmissionDb), so this script puts a small Docker API proxy (embedded below, python3) in
#     front of the Docker socket (DOCKER_HOST). The proxy rewrites only `POST /containers/create` for an image named
#     postgres: it adds LD_PRELOAD, FAKETIME and FAKETIME_DISABLE_SHM to the container environment and mounts the
#     libfaketime of this tool image from a named volume. Every other request is forwarded unchanged.
# For every run it prints `date -u` and the Instant.now() of the JVM, and `select now()` of the test database, and
# compares both with the requested instant (0 to ${TOLERANCE_S} s after it, because the clock keeps running). It fails
# closed: a missing tool, a clock that is not shifted, a database that was never sampled, a test class without test
# cases or with a failed one, and any failed run give a non-zero exit status.
#
# Run it in the tool image named by CI_GRADLE_DOCKER_IMAGE (ci/stages.gitlab-ci.yml of platform-build): it carries
# faketime, gradle, java, python3 and the docker CLI. The Docker socket must be mounted. Working directory: repo root.
# Environment: TS_GRADLE_ARGS (extra Gradle arguments), DOCKER_SOCK (default /var/run/docker.sock).
# Option: --only "YYYY-MM-DD HH:MM" runs one instant; such a run is PARTIAL and exits 3 even when it passes.
set -uo pipefail

readonly TEST_PROJECT=":submission-bootstrap"
readonly TEST_PKG="com.dynokore.gst.submission.bootstrap"
readonly TEST_CLASSES=(BreakGlassRestrictionTest PayloadReadyDbTest EndUserFilterTest)
readonly DATES=(2026-11-30 2026-12-31)
readonly TIMES=(00:30 01:30 12:00 23:59)
readonly TOLERANCE_S=300
readonly DB_POLL_ROUNDS=2400
readonly RESULTS_DIR=submission-bootstrap/build/test-results/test

fail() { echo "time-shift-check: FAIL: $*" >&2; exit 1; }

ONLY=""
if [ "${1:-}" = "--only" ]; then ONLY="${2:-}"; [ -n "$ONLY" ] || fail "--only needs a value"; shift 2; fi
[ $# -eq 0 ] || fail "unknown argument: $1"

# ---- tools (fail closed) ---------------------------------------------------------------------------------------------
for tool in faketime docker python3 gradle java date; do
  command -v "$tool" >/dev/null 2>&1 || fail "tool not found: $tool"
done
FT_LIB=$(ls /usr/lib/*/faketime/libfaketime.so.1 2>/dev/null | head -1)
[ -n "$FT_LIB" ] && [ -r "$FT_LIB" ] || fail "libfaketime.so.1 not found under /usr/lib/*/faketime"
DOCKER_SOCK="${DOCKER_SOCK:-/var/run/docker.sock}"
[ -S "$DOCKER_SOCK" ] || fail "Docker socket not found: $DOCKER_SOCK"
[ -f gradlew ] && [ -d submission-bootstrap ] || fail "run from the repository root of svc-submission"

LABEL="inv.agent=${INV_AGENT_NAME:-time-shift-check}"
WORK=$(mktemp -d) || fail "mktemp"
VOL="inv-ts-ft-$$"
PROXY_PID=""
POLL_PID=""
cleanup() {
  [ -n "$POLL_PID" ] && kill "$POLL_PID" 2>/dev/null
  [ -n "$PROXY_PID" ] && kill "$PROXY_PID" 2>/dev/null
  docker volume rm -f "$VOL" >/dev/null 2>&1
  rm -rf "$WORK"
}
trap cleanup EXIT
trap 'exit 130' INT TERM

# ---- libfaketime volume for the database container ---------------------------------------------------------------------
TOOL_IMAGE="${CI_GRADLE_DOCKER_IMAGE:-}"
[ -n "$TOOL_IMAGE" ] || TOOL_IMAGE=$(docker inspect --format '{{.Image}}' "$(hostname)" 2>/dev/null)
[ -n "$TOOL_IMAGE" ] || fail "cannot name the tool image (set CI_GRADLE_DOCKER_IMAGE)"
docker volume create --label "$LABEL" "$VOL" >/dev/null || fail "docker volume create"
docker run --rm --label "$LABEL" --entrypoint cp -v "$VOL":/ft "$TOOL_IMAGE" "$FT_LIB" /ft/libfaketime.so.1 \
  || fail "cannot copy libfaketime into the volume"

# ---- Docker API proxy --------------------------------------------------------------------------------------------------
cat >"$WORK/proxy.py" <<'PY'
import json, os, re, socket, sys, threading

sock_path, upstream, volume, counter = sys.argv[1:5]
CREATE = re.compile(r"^(/v[0-9.]+)?/containers/create(\?|$)")


def pump(src, dst):
    try:
        while True:
            data = src.recv(65536)
            if not data:
                break
            dst.sendall(data)
    except OSError:
        pass
    finally:
        for s in (src, dst):
            try:
                s.shutdown(socket.SHUT_RDWR)
            except OSError:
                pass


def read_until(conn, buf, marker):
    while marker not in buf:
        data = conn.recv(65536)
        if not data:
            return None, buf
        buf += data
    i = buf.index(marker) + len(marker)
    return buf[:i], buf[i:]


def read_n(conn, buf, n):
    while len(buf) < n:
        data = conn.recv(65536)
        if not data:
            raise OSError("short body")
        buf += data
    return buf[:n], buf[n:]


def read_chunked(conn, buf):
    body = b""
    while True:
        line, buf = read_until(conn, buf, b"\r\n")
        if line is None:
            raise OSError("short chunked body")
        size = int(line.strip().split(b";")[0], 16)
        if size == 0:
            _, buf = read_until(conn, buf, b"\r\n")  # trailer end
            return body, buf
        chunk, buf = read_n(conn, buf, size + 2)
        body += chunk[:-2]


def rewrite(body):
    doc = json.loads(body)
    image = str(doc.get("Image", ""))
    if not image.startswith("postgres:"):
        return body
    env = doc.get("Env") or []
    env += [
        "LD_PRELOAD=/inv-faketime/libfaketime.so.1",
        "FAKETIME=@" + os.environ["TS_FAKETIME"],
        "FAKETIME_DISABLE_SHM=1",
        "FAKETIME_NO_CACHE=1",
    ]
    doc["Env"] = env
    labels = doc.get("Labels") or {}
    labels["inv.time-shift-run"] = os.environ["TS_RUN_ID"]
    doc["Labels"] = labels
    host = doc.setdefault("HostConfig", {}) or {}
    doc["HostConfig"] = host
    host["Binds"] = (host.get("Binds") or []) + [volume + ":/inv-faketime:ro"]
    with open(counter, "a") as f:
        f.write(image + "\n")
    return json.dumps(doc).encode()


def handle(client):
    up = socket.socket(socket.AF_UNIX)
    up.connect(upstream)
    threading.Thread(target=pump, args=(up, client), daemon=True).start()
    buf = b""
    try:
        while True:
            head, buf = read_until(client, buf, b"\r\n\r\n")
            if head is None:
                break
            lines = head.decode("latin-1").split("\r\n")
            method, path = lines[0].split(" ")[:2]
            headers = {}
            for ln in lines[1:]:
                if ":" in ln:
                    k, v = ln.split(":", 1)
                    headers[k.strip().lower()] = v.strip()
            if "upgrade" in headers or headers.get("connection", "").lower() == "upgrade":
                up.sendall(head + buf)
                pump(client, up)
                return
            if headers.get("transfer-encoding", "").lower() == "chunked":
                body, buf = read_chunked(client, buf)
            else:
                body, buf = read_n(client, buf, int(headers.get("content-length", "0")))
            if method == "POST" and CREATE.match(path):
                body = rewrite(body)
            out = [lines[0]]
            for ln in lines[1:]:
                low = ln.lower()
                if low.startswith("content-length:") or low.startswith("transfer-encoding:") or not ln:
                    continue
                out.append(ln)
            if body or method in ("POST", "PUT"):
                out.append("Content-Length: %d" % len(body))
            up.sendall(("\r\n".join(out) + "\r\n\r\n").encode("latin-1") + body)
    except OSError:
        pass
    finally:
        for s in (client, up):
            try:
                s.close()
            except OSError:
                pass


srv = socket.socket(socket.AF_UNIX)
srv.bind(sock_path)
srv.listen(64)
while True:
    c, _ = srv.accept()
    threading.Thread(target=handle, args=(c,), daemon=True).start()
PY

# ---- init script: the shifted environment of the Test tasks only ------------------------------------------------------------
cat >"$WORK/shift.init.gradle" <<'GRADLE'
allprojects {
    tasks.withType(Test).configureEach {
        environment 'TZ', 'UTC'
        environment 'LD_PRELOAD', System.getenv('TS_LD_PRELOAD')
        environment 'FAKETIME', System.getenv('TS_FAKETIME_OFFSET')
        environment 'FAKETIME_DISABLE_SHM', '1'
        environment 'FAKETIME_DONT_FAKE_MONOTONIC', '1'
        environment 'DOCKER_HOST', System.getenv('TS_DOCKER_HOST')
        environment 'TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE', System.getenv('TS_DOCKER_SOCK')
    }
}
GRADLE

# ---- JVM probe: the clock of a JVM started under the same environment as the tests -----------------------------------------
cat >"$WORK/Now.java" <<'JAVA'
public class Now { public static void main(String[] a) { System.out.println(java.time.Instant.now()); } }
JAVA

epoch() { date -u -d "$1" +%s 2>/dev/null; }

within_tolerance() { # <requested epoch> <observed epoch>
  [ -n "$2" ] && [ "$2" -ge "$1" ] && [ $(($2 - $1)) -le "$TOLERANCE_S" ]
}

# Checks that every class ran, none failed and none was skipped wholesale. Output: problem text, empty when fine.
check_results() {
  python3 -I - "$RESULTS_DIR" "$TEST_PKG" "${TEST_CLASSES[@]}" <<'PY'
import sys, xml.etree.ElementTree as ET
d, pkg, classes = sys.argv[1], sys.argv[2], sys.argv[3:]
for c in classes:
    p = "%s/TEST-%s.%s.xml" % (d, pkg, c)
    try:
        r = ET.parse(p).getroot()
    except (OSError, ET.ParseError):
        print("no result file for " + c)
        continue
    t, f, e, s = (int(r.get(k, "0")) for k in ("tests", "failures", "errors", "skipped"))
    print("%s: tests=%d failures=%d errors=%d skipped=%d" % (c, t, f, e, s), file=sys.stderr)
    if t == 0 or f or e or s == t:
        print("%s: tests=%d failures=%d errors=%d skipped=%d" % (c, t, f, e, s))
PY
}

run_one() { # <date> <time>
  local day="$1" hm="$2" instant="$1 $2:00" want sock counter sample
  want=$(epoch "$instant UTC") || { echo "bad instant $instant"; return 1; }
  sock="$WORK/docker.sock"; counter="$WORK/injected"; sample="$WORK/dbnow"
  rm -f "$sock" "$counter" "$sample"
  : >"$counter"
  echo "=== run $day $hm (requested $instant UTC) ==="

  # JVM clock: the date command and a fresh JVM, both under the environment the tests get.
  # A relative offset (+Ns, computed now from the real clock) is much cheaper for a JVM than an absolute start-at time.
  local off jvm_date jvm_now
  off=$((want - $(date +%s)))
  [ "$off" -ge 0 ] && off="+${off}s" || off="${off}s"
  jvm_date=$(env TZ=UTC LD_PRELOAD="$FT_LIB" FAKETIME="$off" FAKETIME_DISABLE_SHM=1 \
    FAKETIME_DONT_FAKE_MONOTONIC=1 date -u '+%Y-%m-%d %H:%M:%S')
  jvm_now=$(env TZ=UTC LD_PRELOAD="$FT_LIB" FAKETIME="$off" FAKETIME_DISABLE_SHM=1 \
    FAKETIME_DONT_FAKE_MONOTONIC=1 java "$WORK/Now.java" 2>/dev/null | tail -1)
  echo "JVM  date -u        : $jvm_date"
  echo "JVM  Instant.now()  : $jvm_now"
  within_tolerance "$want" "$(epoch "$jvm_date UTC")" || { echo "JVM date -u is not at $instant"; return 1; }
  within_tolerance "$want" "$(epoch "$(printf '%s' "$jvm_now" | sed 's/\.[0-9]*Z$/Z/')")" \
    || { echo "JVM Instant.now() is not at $instant"; return 1; }

  # Docker proxy for this run.
  RUN_ID="$$-$day-$hm"
  env -u LD_PRELOAD TS_FAKETIME="$instant" TS_RUN_ID="$RUN_ID" python3 -I "$WORK/proxy.py" "$sock" "$DOCKER_SOCK" "$VOL" "$counter" &
  PROXY_PID=$!
  local i=0
  while [ ! -S "$sock" ] && [ $i -lt 50 ]; do sleep 0.1; i=$((i + 1)); done
  [ -S "$sock" ] || { echo "proxy did not start"; return 1; }

  # Database clock: sample `select now()` of the Postgres container the tests start.
  (
    for _ in $(seq 1 "$DB_POLL_ROUNDS"); do
      for id in $(env -u LD_PRELOAD docker ps -q --filter "label=inv.time-shift-run=$RUN_ID" 2>/dev/null); do
        img=$(env -u LD_PRELOAD docker inspect --format '{{.Config.Image}}' "$id" 2>/dev/null)
        case "$img" in postgres:*) ;; *) continue ;; esac
        v=$(env -u LD_PRELOAD docker exec "$id" psql -U test -d test -tA \
          -c "select to_char(now() at time zone 'UTC','YYYY-MM-DD HH24:MI:SS')" 2>/dev/null)
        if [ -n "$v" ]; then echo "$v" >"$sample"; exit 0; fi
      done
      sleep 0.5
    done
  ) &
  POLL_PID=$!

  rm -rf "$RESULTS_DIR"
  local args=() c rc
  for c in "${TEST_CLASSES[@]}"; do args+=(--tests "$TEST_PKG.$c"); done
  env TS_LD_PRELOAD="$FT_LIB" TS_FAKETIME_OFFSET="$off" TS_DOCKER_HOST="unix://$sock" TS_DOCKER_SOCK="$DOCKER_SOCK" \
    gradle --console=plain --no-daemon -I "$WORK/shift.init.gradle" ${TS_GRADLE_ARGS:-} "$TEST_PROJECT:cleanTest" "$TEST_PROJECT:test" "${args[@]}"
  rc=$?
  kill "$POLL_PID" 2>/dev/null; wait "$POLL_PID" 2>/dev/null; POLL_PID=""
  kill "$PROXY_PID" 2>/dev/null; wait "$PROXY_PID" 2>/dev/null; PROXY_PID=""

  local db
  db=$(cat "$sample" 2>/dev/null)
  echo "DB   select now()   : ${db:-<never sampled>}"
  [ -n "$db" ] || { echo "database clock was never sampled"; return 1; }
  within_tolerance "$want" "$(epoch "$db UTC")" || { echo "database clock is not at $instant"; return 1; }
  [ -s "$counter" ] || { echo "no Postgres container was created through the clock proxy"; return 1; }
  [ "$rc" -eq 0 ] || { echo "gradle exit status $rc"; return 1; }
  local problems
  problems=$(check_results) || true
  [ -z "$problems" ] || { echo "$problems"; return 1; }
  echo "RUN OK $day $hm"
}

# ---- matrix ----------------------------------------------------------------------------------------------------------------
runs=()
if [ -n "$ONLY" ]; then
  runs+=("${ONLY% *}|${ONLY##* }")
else
  for d in "${DATES[@]}"; do for t in "${TIMES[@]}"; do runs+=("$d|$t"); done; done
fi
failed=0 total=${#runs[@]}
for r in "${runs[@]}"; do
  if ! run_one "${r%%|*}" "${r##*|}"; then echo "RUN FAILED ${r%%|*} ${r##*|}"; failed=$((failed + 1)); fi
done
echo "=== ${total} runs, ${failed} failed ==="
[ "$failed" -eq 0 ] || exit 1
if [ -n "$ONLY" ]; then echo "PARTIAL run (--only): not a proof"; exit 3; fi
echo "TIME-SHIFT OK: all ${total} runs passed"
