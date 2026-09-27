#!/usr/bin/env bash
# Rivell M10 — live end-to-end reproduction.
#
# Drives the complete product flow over HTTP against a real server, real PostgreSQL + pgvector,
# a real JobRunr background worker, and real document storage, with real model providers:
#
#   upload DOCX -> enrichment -> knowledge publication -> review -> analysis -> findings
#   -> human decisions -> finalization -> deterministic redline -> round-trip verification
#   -> artifact download
#
# This is the "repeatable happy path" and "demo reset/reproduction" acceptance criterion for M10.
# It is a LOCAL reproduction script, not a test: it needs credentials and it spends tokens.
#
# Usage:
#   scripts/demo-reproduction.sh [--keep]
#
# Credentials are read from the environment, never printed, never committed. The script fails loudly
# and says which step failed rather than leaving a half-built review behind silently.
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
BACKEND="${REPO_ROOT}/backend"
FIXTURES="${BACKEND}/src/test/resources/fixtures"
WORK_DIR="${BACKEND}/target/demo-reproduction"   # under backend/target, which is gitignored
CONTAINER="rivell-demo-pg"
BASE_URL="http://localhost:8080"
LOG="${WORK_DIR}/backend.log"
KEEP=0
[[ "${1:-}" == "--keep" ]] && KEEP=1

fail() { echo "FAILED at: $1" >&2; exit 1; }
step() { echo; echo "== $*"; }

require() {
  local missing=0
  for var in "$@"; do
    if [[ -z "${!var:-}" ]]; then
      echo "missing required environment variable: ${var}" >&2
      missing=1
    fi
  done
  [[ ${missing} -eq 0 ]] || fail "environment"
}

# The background worker is OFF by default. Without it a created review sits in ANALYSIS_QUEUED
# forever, which is the single most confusing behaviour in the product. This script is the
# reproduction of the working configuration, so it turns it on explicitly.
require TYPESAFE_API_KEY OPENAI_API_KEY

cleanup() {
  if [[ ${KEEP} -eq 0 ]]; then
    stop_backend
    docker rm -f "${CONTAINER}" >/dev/null 2>&1 || true
  else
    echo "kept: container=${CONTAINER} log=${LOG}"
  fi
}

# The backend is started inside a backgrounded compound command, so the recorded $! is the
# subshell rather than the JVM, and killing it leaves the JVM holding port 8080 against a
# database this script is about to delete. Stop by recorded pid, then fall back to whatever holds
# the port, and do not return until the port is actually free.
stop_backend() {
  if [[ -n "${APP_PID:-}" ]]; then
    kill "${APP_PID}" 2>/dev/null || true
  fi
  for _ in $(seq 1 15); do
    lsof -ti tcp:8080 >/dev/null 2>&1 || return 0
    sleep 1
  done
  local holders
  holders="$(lsof -ti tcp:8080 2>/dev/null || true)"
  if [[ -n "${holders}" ]]; then
    echo "warning: killing leftover process(es) still holding port 8080: ${holders}" >&2
    kill ${holders} 2>/dev/null || true
    sleep 2
  fi
  lsof -ti tcp:8080 >/dev/null 2>&1 \
    && echo "warning: port 8080 is still held after cleanup; the next run will refuse to start" >&2
  return 0
}
trap cleanup EXIT

step "reset"
docker rm -f "${CONTAINER}" >/dev/null 2>&1 || true
rm -rf "${WORK_DIR}"
mkdir -p "${WORK_DIR}"
docker run -d --name "${CONTAINER}" \
  -e POSTGRES_DB=rivell -e POSTGRES_USER=rivell -e POSTGRES_PASSWORD=rivell \
  -p 5432:5432 pgvector/pgvector:0.8.6-pg17-trixie >/dev/null
for _ in $(seq 1 60); do
  docker exec "${CONTAINER}" pg_isready -U rivell >/dev/null 2>&1 && break
  sleep 1
done
echo "postgres up"

step "build"
(cd "${BACKEND}" && JAVA_HOME="${JAVA_HOME:-/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home}" \
  ./mvnw -q -DskipTests package) || fail "build"

step "start backend with the background worker enabled"
# Run the packaged jar, not `mvn spring-boot:run`, and exec it so the recorded pid is the JVM rather
# than a wrapper. A leaked process is the failure this guards against: it holds port 8080 against a
# database this script deletes, so the next run either refuses to start or, worse, is talked to by a
# stale process running whatever configuration it was launched with - which is exactly how a "the
# worker is on" run ends up reporting a review that never analyses.
JAR="$(ls "${BACKEND}"/target/rivell-backend-*.jar 2>/dev/null | grep -v original | head -1)"
[[ -n "${JAR}" ]] || fail "packaged jar not found"
if lsof -ti tcp:8080 >/dev/null 2>&1; then
  echo "port 8080 is already in use; stop that process first" >&2
  fail "port 8080 busy"
fi
(
  cd "${BACKEND}" || exit 1
  export RIVELL_JOB_WORKER_ENABLED=true
  export RIVELL_DOCUMENT_STORAGE_ROOT="${WORK_DIR}/documents"
  export RIVELL_EMBEDDINGS_PROVIDER=openai-compatible
  export RIVELL_EMBEDDINGS_MODEL="${RIVELL_EMBEDDINGS_MODEL:-text-embedding-3-small}"
  export RIVELL_RETRIEVAL_INDEXING_ENABLED=true
  # The property is jobrunr.background-job-server.poll-interval-in-seconds, so the bound variable
  # is JOBRUNR_BACKGROUNDJOBSERVER_POLLINTERVALINSECONDS. The previous JOBRUNJ_ spelling never
  # bound, so the interval silently stayed at the 15s default; and JobRunr rejects anything under
  # 5s at startup, so the stale value of 3 would have stopped the application booting.
  export JOBRUNR_BACKGROUNDJOBSERVER_POLLINTERVALINSECONDS=5
  export JAVA_HOME="${JAVA_HOME:-/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home}"
  # exec so this subshell is replaced by the JVM: $! below is then the JVM's own pid.
  exec "${JAVA_HOME}/bin/java" -jar "${JAR}" > "${LOG}" 2>&1
) &
echo $! > "${WORK_DIR}/app.pid"
APP_PID="$(cat "${WORK_DIR}/app.pid")"

for _ in $(seq 1 120); do
  if curl -fsS "${BASE_URL}/api/system/status" >/dev/null 2>&1; then break; fi
  sleep 2
done
curl -fsS "${BASE_URL}/api/system/status" >/dev/null 2>&1 || fail "backend did not become ready (see ${LOG})"
echo "backend ready"

jqq() { python3 -c "import json,sys;d=json.load(sys.stdin);print(eval('d'+sys.argv[1]))" "$1"; }

step "1. upload the contract"
CONTRACT_JSON="$(curl -fsS -X POST "${BASE_URL}/api/contracts" \
  -F "file=@${FIXTURES}/m5-msa-services.docx;type=application/vnd.openxmlformats-officedocument.wordprocessingml.document")"
CONTRACT_ID="$(echo "${CONTRACT_JSON}" | jqq "['data']['contractId']")"
[[ -n "${CONTRACT_ID}" ]] || fail "contract upload"
echo "contract ${CONTRACT_ID}"

step "2. enrich"
curl -fsS -X POST "${BASE_URL}/api/contracts/${CONTRACT_ID}/versions/1/enrichment" \
  -H 'Content-Type: application/json' \
  -d '{"contractType":"MSA","ourSideRole":"CUSTOMER","paperSource":"THIRD_PARTY","jurisdiction":"US","contractPackKey":"MSA_PACK"}' \
  > "${WORK_DIR}/enrichment.json" || fail "enrichment"
python3 - "${WORK_DIR}/enrichment.json" <<'PY'
import json,sys
d=json.load(open(sys.argv[1]))["data"]
assert d["enrichmentStatus"]=="READY", d["enrichmentStatus"]
print("enrichment READY, clauses:", len(d["clauses"]))
PY

step "3. publish the knowledge corpus (playbook + clause library)"
for spec in "PLAYBOOK:m6-policy-playbook.docx" "CLAUSE_LIBRARY:m6-clause-library.docx"; do
  TYPE="${spec%%:*}"; FILE="${spec##*:}"
  ARTIFACT_JSON="$(curl -fsS -X POST "${BASE_URL}/api/knowledge/artifacts" \
    -F "file=@${FIXTURES}/${FILE};type=application/vnd.openxmlformats-officedocument.wordprocessingml.document" \
    -F "artifactType=${TYPE}" -F "contractPackKey=MSA_PACK" -F "partyPerspective=Customer" \
    -F "paperSource=Third-Party" -F "jurisdiction=US" -F "language=en" \
    -F "declaredContractTypes=MSA" -F "effectiveFrom=2026-01-01" -F "effectiveTo=2026-12-31")"
  ARTIFACT_ID="$(echo "${ARTIFACT_JSON}" | jqq "['data']['id']")"
  # Publication is a human gate: candidates must be approved before they can be published. That
  # gate is the point of the milestone, so the script performs it explicitly rather than bypassing it.
  CANDIDATES="$(curl -fsS "${BASE_URL}/api/knowledge/artifacts/${ARTIFACT_ID}/candidates")"
  PENDING="$(echo "${CANDIDATES}" | python3 -c "
import json,sys
items=json.load(sys.stdin)['data']
print(json.dumps([i['id'] for i in items if i['reviewStatus']=='PENDING']))")"
  if [[ "${PENDING}" != "[]" ]]; then
    curl -fsS -X POST "${BASE_URL}/api/knowledge/candidates/bulk-approve" \
      -H 'Content-Type: application/json' -d "{\"candidateIds\": ${PENDING}}" \
      > "${WORK_DIR}/approve-${TYPE}.json" || fail "candidate approval ${TYPE}"
  fi
  IDS="$(curl -fsS "${BASE_URL}/api/knowledge/artifacts/${ARTIFACT_ID}/candidates" | python3 -c "
import json,sys
items=json.load(sys.stdin)['data']
print(json.dumps({'candidateIds':[i['id'] for i in items if i['reviewStatus'] in ('APPROVED','EDITED')],'note':'demo reproduction'}))")"
  curl -fsS -X POST "${BASE_URL}/api/knowledge/publications" \
    -H 'Content-Type: application/json' -d "${IDS}" > "${WORK_DIR}/publication-${TYPE}.json" \
    || fail "publication ${TYPE}"
  echo "published ${TYPE}"
done

step "4. create the review"
REVIEW_JSON="$(curl -fsS -X POST "${BASE_URL}/api/contracts/${CONTRACT_ID}/versions/1/reviews" \
  -H 'Content-Type: application/json' -d '{"contractPackKey":"MSA_PACK"}')"
REVIEW_ID="$(echo "${REVIEW_JSON}" | jqq "['data']['id']")"
echo "review ${REVIEW_ID}"

step "5. wait for the background worker to analyse it"
for _ in $(seq 1 90); do
  STATUS="$(curl -fsS "${BASE_URL}/api/reviews/${REVIEW_ID}" | jqq "['data']['status']")"
  [[ "${STATUS}" == "COMPLETED" || "${STATUS}" == "FAILED" ]] && break
  sleep 4
done
[[ "${STATUS}" == "COMPLETED" ]] || fail "analysis did not complete (status=${STATUS})"
echo "analysis COMPLETED"

step "6. inspect diagnostics"
curl -fsS "${BASE_URL}/api/reviews/${REVIEW_ID}/diagnostics" > "${WORK_DIR}/diagnostics.json"
python3 - "${WORK_DIR}/diagnostics.json" <<'PY'
import json,sys
d=json.load(open(sys.argv[1]))["data"]
print("worker enabled:", d["backgroundWorkerEnabled"])
print("run:", d["analysis"]["status"], "checks:", len(d["analysis"]["checks"]))
print("findings:", d["findings"])
print("stalled:", d["stalled"])
# Assertions, not just prints. Without these a run whose analysis produced nothing at all still
# "succeeds" further down the script, because zero findings means zero decisions means a
# zero-change set means NO_CHANGES - which the redline step treats as a valid outcome.
assert d["backgroundWorkerEnabled"] is True, "the background worker is not running"
assert d["analysis"]["status"] == "COMPLETED", d["analysis"]["status"]
assert d["stalled"] == [], d["stalled"]
assert d["analysis"]["environmentMatchesSnapshot"] is True, \
    "the frozen semantics digest no longer matches the live contract semantics"
assert d["findings"]["total"] > 0, "the analysis produced no findings at all"
PY

step "7. read findings"
curl -fsS "${BASE_URL}/api/reviews/${REVIEW_ID}/findings" > "${WORK_DIR}/findings.json"
python3 - "${WORK_DIR}/findings.json" <<'PY'
import json,sys
d=json.load(open(sys.argv[1]))["data"]
print("findings:", len(d["items"]))
for i in d["items"]:
    print(" -", i["issueFamily"], i["reviewClassification"], i["proposedAction"])
actionable=[i for i in d["items"] if i["reviewClassification"]!="INFORMATIONAL"]
assert actionable, "no actionable finding: the run cannot demonstrate the human step"
PY

step "8. decide on every actionable finding, then finalize"
python3 - "${BASE_URL}" "${REVIEW_ID}" "${WORK_DIR}" <<'PY'
import json,sys,urllib.request
base, review, work = sys.argv[1], sys.argv[2], sys.argv[3]
def call(method, path, body=None):
    data = json.dumps(body).encode() if body is not None else None
    req = urllib.request.Request(base+path, data=data, method=method,
                                 headers={"Content-Type": "application/json"})
    try:
        with urllib.request.urlopen(req) as r:
            return json.load(r)
    except urllib.error.HTTPError as e:
        # Surface the product's own error body. A bare "HTTP Error 409" says nothing about which
        # readiness rule refused the step, which is the whole point of running this live.
        raw = e.read().decode(errors="replace")
        raise SystemExit(f"{method} {path} failed with {e.code}: {raw[:500]}")
findings = call("GET", f"/api/reviews/{review}/findings")["data"]["items"]
REVISED = False

def decide(f):
    # A recommendation the machine marks ESCALATE_TO_HUMAN is deliberately not acceptable: M8
    # refuses it, because accepting would silently convert "a human must look at this" into an
    # approved change. The demo therefore resolves each one as a human actually would.
    global REVISED
    if f["proposedAction"] == "ESCALATE_TO_HUMAN" and f["subjectKind"] == "CLAUSE" and not REVISED:
        # An escalation is revised by an explicit replacement, which is the M8 path for turning
        # "needs a human" into an actual approved change.
        REVISED = True
        return {"expectedVersion": 0, "action": "EDIT", "approvedAction": "REPLACE",
                "approvedLanguage": "Total liability shall not exceed the fees paid in the "
                                    "preceding 12 months."}
    if f["proposedAction"] == "ESCALATE_TO_HUMAN":
        return {"expectedVersion": 0, "action": "REJECT",
                "comment": "Reviewed: counterparty position accepted for this pass."}
    if f["subjectKind"] == "ABSENCE":
        return {"expectedVersion": 0, "action": "EDIT",
                "approvedLanguage": "Liability is capped at the fees paid in the preceding 12 months."}
    return {"expectedVersion": 0, "action": "ACCEPT"}

decided = 0
for f in findings:
    if f["reviewClassification"] == "INFORMATIONAL":
        continue
    body = decide(f)
    call("PUT", f"/api/reviews/{review}/findings/{f['id']}/decision", body)
    print("  decided", f["issueFamily"], body["action"])
    decided += 1
assert decided > 0, "no decision was recorded, so nothing downstream is meaningful"
set_ = call("POST", f"/api/reviews/{review}/finalize")["data"]
print("finalized with", len(set_["changes"]), "approved change(s)")
json.dump(set_, open(work+"/change-set.json","w"), indent=2)
PY

step "9. execute the deterministic redline"
EXEC_JSON="$(curl -fsS -X POST "${BASE_URL}/api/reviews/${REVIEW_ID}/redline")"
python3 - "${EXEC_JSON}" <<'PY'
import json,sys
d=json.loads(sys.argv[1])["data"]
print("status:", d["status"], "outcome:", d["outcome"], "changes:", d["changeCount"])
assert d["status"]=="COMPLETED", d
# Every executed change must have passed independent round-trip verification. A change that did not
# is a failure of the run, not something to download and inspect by eye.
for c in d["changes"]:
    print(" -", c["actionType"], c["status"], c["verification"])
    assert c["status"]=="APPLIED", c
    assert c["verification"]=="VERIFIED", c
if d["outcome"] == "NO_CHANGES":
    assert d["downloadAvailable"] is False, "a zero-change run must offer no artifact"
    print("no approved changes: no artifact, and download is refused")
else:
    assert d["downloadAvailable"] is True, d
PY

step "10. download and verify the artifact"
if [[ "$(echo "${EXEC_JSON}" | jqq "['data']['downloadAvailable']")" != "True" ]]; then
  echo "no artifact to download (zero-change run); verifying the refusal instead"
  CODE="$(curl -s -o /dev/null -w '%{http_code}' \
    "${BASE_URL}/api/reviews/${REVIEW_ID}/redline/download")"
  [[ "${CODE}" == "409" ]] || fail "download of an absent artifact returned ${CODE}, expected 409"
  echo "download correctly refused with 409"
  echo; echo "REPRODUCTION OK (zero-change outcome)"; echo "review=${REVIEW_ID}"
  exit 0
fi
curl -fsS -D "${WORK_DIR}/download-headers.txt" \
  -o "${WORK_DIR}/redline.docx" "${BASE_URL}/api/reviews/${REVIEW_ID}/redline/download"
BYTES="$(wc -c < "${WORK_DIR}/redline.docx" | tr -d ' ')"
SHA="$(shasum -a 256 "${WORK_DIR}/redline.docx" | awk '{print $1}')"
echo "downloaded ${BYTES} bytes, sha256 ${SHA}"
EXPECTED_SHA="$(curl -fsS "${BASE_URL}/api/reviews/${REVIEW_ID}/redline" | jqq "['data']['artifactSha256']")"
[[ "${SHA}" == "${EXPECTED_SHA}" ]] || fail "artifact digest mismatch"
echo "artifact digest matches the reported artifactSha256"

step "11. the original is untouched"
curl -fsS -o "${WORK_DIR}/original.docx" \
  "${BASE_URL}/api/contracts/${CONTRACT_ID}/versions/1/original"
ORIGINAL_SHA="$(shasum -a 256 "${WORK_DIR}/original.docx" | awk '{print $1}')"
UPLOADED_SHA="$(shasum -a 256 "${FIXTURES}/m5-msa-services.docx" | awk '{print $1}')"
[[ "${ORIGINAL_SHA}" == "${UPLOADED_SHA}" ]] || fail "original document was modified"
echo "original is byte-identical to the upload"

echo
echo "REPRODUCTION OK"
echo "review=${REVIEW_ID} artifact=${WORK_DIR}/redline.docx"
