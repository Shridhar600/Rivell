#!/usr/bin/env python3
"""Capture every GET the demo screens use, so the static demo can answer them.

The capture follows the list endpoints (reviews, knowledge artifacts, publications) and
reads each row's detail endpoints, exactly as FACTS-demo-data.md section 6.2 lists them.
Later states store only the responses that differ from the merged earlier states; the
transport merges the states in manifest order.

Nothing here records a request or response header. Before a state is written, its JSON is
scanned for the shapes of credentials, and the capture fails rather than shipping one.
"""
import argparse
import datetime
import hashlib
import json
import pathlib
import re
import sys
import urllib.error
import urllib.parse
import urllib.request

LIST_LIMIT = 100
DOCX_MEDIA_TYPE = ("application/vnd.openxmlformats-officedocument."
                   "wordprocessingml.document")
MANIFEST = "manifest.json"
SECRET_PATTERNS = (
    # The credential words and prefixes the demo must never publish. The key prefixes only
    # count where a token can start, so "risk-based" in contract text is not a false positive.
    re.compile(r"(?i)\bauthorization\b"),
    re.compile(r"(?i)\bbearer\b\s"),
    # Key prefixes only where a token can start; "risk-based" in contract text is not a key.
    re.compile(r"(?i)(?<![a-z0-9])sk-[a-z0-9][a-z0-9_-]{8,}"),
    re.compile(r"(?i)(?<![a-z0-9])tsk-[a-z0-9][a-z0-9_-]{8,}"),
    re.compile(r"(?i)\bapi[_-]?keys?\b"),
)


class CaptureError(RuntimeError):
    """The capture would be incomplete, inconsistent, or unsafe to publish."""


def utc_now() -> str:
    return (datetime.datetime.now(datetime.timezone.utc)
            .isoformat(timespec="seconds").replace("+00:00", "Z"))


def parse_key(url: str) -> str:
    """Normalise a GET URL to the key the transport looks up.

    limit and cursor only page a list; the transport serves the whole list, so they are
    dropped. Every other parameter stays, sorted, so the key is stable.
    """
    split = urllib.parse.urlsplit(url)
    pairs = urllib.parse.parse_qsl(split.query, keep_blank_values=True)
    kept = sorted((name, value) for name, value in pairs
                  if name not in ("limit", "cursor"))
    key = f"GET {split.path}"
    if kept:
        key += "?" + urllib.parse.urlencode(kept)
    return key


def normalise_url(url: str) -> str:
    split = urllib.parse.urlsplit(url)
    pairs = urllib.parse.parse_qsl(split.query, keep_blank_values=True)
    kept = sorted((name, value) for name, value in pairs
                  if name not in ("limit", "cursor"))
    path = split.path
    if kept:
        path += "?" + urllib.parse.urlencode(kept)
    return path


def filename_from_disposition(header: str | None) -> str | None:
    if not header:
        return None
    encoded = re.search(r"filename\*=UTF-8''([^;]+)", header, re.IGNORECASE)
    if encoded:
        try:
            return urllib.parse.unquote(encoded.group(1))
        except ValueError:
            return None
    plain = re.search(r'filename="?([^";]+)"?', header, re.IGNORECASE)
    return plain.group(1) if plain else None


def assert_no_secrets(payload, where: str) -> None:
    text = json.dumps(payload, ensure_ascii=False)
    for pattern in SECRET_PATTERNS:
        found = pattern.search(text)
        if found:
            # Never print the match: it may be the secret.
            raise CaptureError(f"secret scan failed in {where} near {pattern.pattern!r}; "
                               "the capture was not written")


def _items(body):
    """The list inside an API envelope, or the body itself for a bare list."""
    data = body.get("data", body) if isinstance(body, dict) else body
    if isinstance(data, dict):
        return data.get("items", [])
    return data if isinstance(data, list) else []


def _comparable(body):
    """A body with the per-response envelope timestamp removed, for change detection.

    Timestamps are stored as returned (the transport shifts them); they must not make every
    response look different from the previous state.
    """
    if not isinstance(body, dict):
        return body
    if "data" in body and "status" in body and "timestamp" in body:
        copy = dict(body)
        copy.pop("timestamp", None)
        return copy
    return body


class Client:
    def __init__(self, base_url: str, timeout: int = 60):
        self.base_url = base_url.rstrip("/")
        self.timeout = timeout

    def get(self, path: str):
        request = urllib.request.Request(self.base_url + path, method="GET",
                                         headers={"Accept": "application/json"})
        try:
            with urllib.request.urlopen(request, timeout=self.timeout) as response:
                raw = response.read()
                return response.status, self._json(raw), dict(response.headers)
        except urllib.error.HTTPError as error:
            raw = error.read()
            return error.code, self._json(raw), dict(error.headers)

    def download(self, path: str):
        request = urllib.request.Request(self.base_url + path, method="GET",
                                         headers={"Accept": DOCX_MEDIA_TYPE})
        try:
            with urllib.request.urlopen(request, timeout=self.timeout) as response:
                return response.status, response.read(), dict(response.headers)
        except urllib.error.HTTPError as error:
            return error.code, error.read(), dict(error.headers)

    @staticmethod
    def _json(raw: bytes):
        if not raw:
            return {}
        try:
            return json.loads(raw)
        except ValueError:
            return raw.decode("utf-8", errors="replace")


class StateWriter:
    """Collects responses in request order; the driver may then save the state."""

    def __init__(self):
        self.responses: dict[str, dict] = {}

    def add(self, path: str, status: int, body) -> None:
        self.responses[parse_key(path)] = {"status": status, "body": body}

    def add_raw(self, key: str, status: int, body) -> None:
        self.responses[key] = {"status": status, "body": body}


def _collect_pages(client: Client, path: str, writer: StateWriter):
    """Read a bounded list with every cursor page, and store it under the query-free key."""
    cursor = None
    collected = []
    first_body = None
    pages = 0
    while True:
        query = {"limit": LIST_LIMIT}
        if cursor:
            query["cursor"] = cursor
        url = path + "?" + urllib.parse.urlencode(query)
        status, body, _headers = client.get(url)
        if status != 200:
            writer.add(path, status, body)
            return body
        pages += 1
        if first_body is None:
            first_body = body
        data = body.get("data", body) if isinstance(body, dict) else body
        page_items = data.get("items", []) if isinstance(data, dict) else data
        if isinstance(page_items, list):
            collected.extend(page_items)
        if isinstance(data, dict) and data.get("truncated") is True:
            raise CaptureError(f"{path} reports truncated=true; the capture would silently "
                               "miss rows")
        cursor = data.get("nextCursor") if isinstance(data, dict) else None
        if not cursor or not page_items:
            break
        if pages > 200:
            raise CaptureError(f"{path} has more than 200 cursor pages")
    if isinstance(first_body, dict) and "data" in first_body:
        data = first_body["data"]
        if isinstance(data, dict):
            data = dict(data)
            data["items"] = collected
            data["nextCursor"] = None
            if "truncated" in data:
                data["truncated"] = False
        else:
            data = collected
        merged = dict(first_body)
        merged["data"] = data
        writer.add(path, 200, merged)
        return merged
    writer.add(path, 200, collected)
    return collected


def _get(client: Client, writer: StateWriter, path: str):
    status, body, _headers = client.get(path)
    writer.add(path, status, body)
    return status, body


def _data(body):
    return body.get("data", body) if isinstance(body, dict) else body


def _review_id(review: dict) -> str | None:
    value = review.get("id") or review.get("reviewId")
    return str(value) if value else None


def _capture_review(client, writer, review: dict) -> None:
    review_id = _review_id(review)
    if not review_id:
        return
    _get(client, writer, f"/api/reviews/{review_id}")
    _get(client, writer, f"/api/reviews/{review_id}/progress")

    findings_body = _collect_pages(client, f"/api/reviews/{review_id}/findings", writer)
    findings = _items(findings_body)
    for finding in findings:
        finding_id = finding.get("id")
        if not finding_id:
            continue
        _get(client, writer, f"/api/reviews/{review_id}/findings/{finding_id}")
        _collect_pages(client, f"/api/reviews/{review_id}/findings/{finding_id}/decisions",
                       writer)
    _get(client, writer, f"/api/reviews/{review_id}/clause-marks")
    _collect_pages(client, f"/api/reviews/{review_id}/decisions", writer)
    _get(client, writer, f"/api/reviews/{review_id}/human-review")
    _get(client, writer, f"/api/reviews/{review_id}/approved-change-set")
    _get(client, writer, f"/api/reviews/{review_id}/redline")


def _capture_contract(client, writer, contract_id: str, version: int) -> None:
    _get(client, writer, f"/api/contracts/{contract_id}")
    _get(client, writer, f"/api/contracts/{contract_id}/versions/{version}/structure")
    _get(client, writer, f"/api/contracts/{contract_id}/versions/{version}/semantics")


def _capture_knowledge(client, writer) -> None:
    artifacts_body = _collect_pages(client, "/api/knowledge/artifacts", writer)
    artifacts = _items(artifacts_body)
    for artifact in artifacts:
        artifact_id = artifact.get("id")
        if not artifact_id:
            continue
        _get(client, writer, f"/api/knowledge/artifacts/{artifact_id}")
        _get(client, writer, f"/api/knowledge/artifacts/{artifact_id}/structure")
        _get(client, writer, f"/api/knowledge/artifacts/{artifact_id}/units")
        status, candidates_body = _get(
            client, writer, f"/api/knowledge/artifacts/{artifact_id}/candidates")
        if status != 200:
            continue
        for candidate in _items(candidates_body):
            candidate_id = candidate.get("id")
            if not candidate_id:
                continue
            _get(client, writer, f"/api/knowledge/candidates/{candidate_id}")
    publications_body = _collect_pages(client, "/api/knowledge/publications", writer)
    for publication in _items(publications_body):
        publication_id = publication.get("id")
        if publication_id:
            _get(client, writer, f"/api/knowledge/publications/{publication_id}")


def _capture_ids(writer: StateWriter) -> dict:
    ids = {"reviews": {}, "contracts": {}, "artifacts": {}, "publications": {},
           "publicationNumbers": {}}
    review_list = writer.responses.get("GET /api/reviews", {}).get("body")
    for review in _items(review_list):
        filename = review.get("contractFilename")
        if filename:
            ids["reviews"][filename] = review.get("id")
            ids["contracts"][filename] = review.get("contractId")
    artifacts_body = writer.responses.get("GET /api/knowledge/artifacts", {}).get("body")
    candidate_artifact = {}
    for artifact in _items(artifacts_body):
        filename = artifact.get("filename")
        if filename:
            ids["artifacts"][filename] = artifact.get("id")
        candidates = writer.responses.get(
            f"GET /api/knowledge/artifacts/{artifact.get('id')}/candidates", {}).get("body")
        for candidate in _items(candidates):
            if candidate.get("id"):
                candidate_artifact[candidate["id"]] = filename
    publications_body = writer.responses.get("GET /api/knowledge/publications", {}).get("body")
    for publication in _items(publications_body):
        publication_id = publication.get("id")
        filename = None
        for item in publication.get("items") or []:
            filename = candidate_artifact.get(item.get("candidateId"))
            if filename:
                break
        if filename:
            ids["publications"][filename] = publication_id
            ids["publicationNumbers"][filename] = publication.get("publicationNumber")
    return ids


def _load_json(path: pathlib.Path):
    if not path.is_file():
        return None
    try:
        return json.loads(path.read_text())
    except ValueError as error:
        raise CaptureError(f"{path} is not valid JSON: {error}") from None


def load_manifest(out_dir) -> dict:
    return _load_json(pathlib.Path(out_dir) / MANIFEST) or {
        "version": 1, "states": [], "capturedAt": None, "ids": {}, "files": {},
    }


def save_manifest(out_dir, manifest: dict) -> None:
    path = pathlib.Path(out_dir) / MANIFEST
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_name(path.name + ".tmp")
    temporary.write_text(json.dumps(manifest, indent=2, ensure_ascii=False) + "\n")
    temporary.replace(path)


def update_manifest(out_dir, *, ids: dict | None = None, files: dict | None = None,
                    prepared_knowledge: dict | None = None) -> dict:
    manifest = load_manifest(out_dir)
    if ids:
        manifest["ids"] = {**manifest.get("ids", {}), **ids}
    if files:
        manifest["files"] = {**manifest.get("files", {}), **files}
    if prepared_knowledge is not None:
        manifest["preparedKnowledge"] = prepared_knowledge
    save_manifest(out_dir, manifest)
    return manifest


def record_prepared_knowledge(out_dir, source) -> dict:
    source = pathlib.Path(source)
    if not source.is_file():
        raise CaptureError(f"prepared knowledge file not found: {source}")
    data = source.read_bytes()
    files_dir = pathlib.Path(out_dir) / "files"
    files_dir.mkdir(parents=True, exist_ok=True)
    target = files_dir / source.name
    target.write_bytes(data)
    entry = {"filename": source.name,
             "path": f"files/{source.name}",
             "size": len(data),
             "sha256": hashlib.sha256(data).hexdigest()}
    update_manifest(out_dir, prepared_knowledge=entry)
    return entry


def _merged_floor(out_dir, manifest: dict, state: str) -> dict:
    merged = {}
    for name in manifest.get("states", []):
        if name == state:
            continue
        document = _load_json(pathlib.Path(out_dir) / f"{name}.json")
        if not document:
            continue
        merged.update(document.get("responses") or {})
    return merged


def diff_responses(responses: dict, floor: dict) -> dict:
    """Responses that are new, or whose status/body differ from the merged earlier states."""
    changed = {}
    for key, response in responses.items():
        previous = floor.get(key)
        if (previous is None
                or previous.get("status") != response.get("status")
                or _comparable(previous.get("body")) != _comparable(response.get("body"))):
            changed[key] = response
    return changed


def _write_state(out_dir, state, captured_at, responses, mutations) -> None:
    payload = {
        "state": state,
        "capturedAt": captured_at,
        "responses": responses,
        "mutations": mutations,
    }
    assert_no_secrets(payload, f"state {state}")
    path = pathlib.Path(out_dir) / f"{state}.json"
    temporary = path.with_name(path.name + ".tmp")
    temporary.write_text(json.dumps(payload, indent=2, ensure_ascii=False) + "\n")
    temporary.replace(path)


def capture_state(base_url: str, state: str, out_dir, mutations=None,
                  download_redlines: bool = True) -> dict:
    """Capture one named state and return a summary for the caller/manifest.

    A non-200 answer is stored as-is: the UI has states for a missing redline and a missing
    change set. A complete redline's DOCX is saved under ``files/`` when it is downloadable.
    """
    if not state or "/" in state or state in (".", ".."):
        raise CaptureError(f"invalid state name {state!r}")
    out_dir = pathlib.Path(out_dir)
    out_dir.mkdir(parents=True, exist_ok=True)
    files_dir = out_dir / "files"
    files_dir.mkdir(parents=True, exist_ok=True)
    client = Client(base_url)
    writer = StateWriter()

    _collect_pages(client, "/api/reviews", writer)
    _get(client, writer, "/api/reviews/packs")
    review_list = writer.responses.get("GET /api/reviews", {}).get("body")
    contracts = {}
    for review in _items(review_list):
        _capture_review(client, writer, review)
        contract_id = review.get("contractId")
        version = review.get("contractVersionNumber")
        if contract_id and isinstance(version, int):
            contracts[(str(contract_id), version)] = True
    for contract_id, version in sorted(contracts):
        _capture_contract(client, writer, contract_id, version)

    _get(client, writer, "/api/taxonomy/concepts")
    _capture_knowledge(client, writer)

    # Redline downloads happen after the GET walk so the state JSON is complete first.
    file_entries = {}
    if download_redlines:
        for review in _items(review_list):
            review_id = _review_id(review)
            if not review_id:
                continue
            response = writer.responses.get(f"GET /api/reviews/{review_id}/redline")
            if not response or response.get("status") != 200:
                continue
            execution = _data(response.get("body"))
            if not (isinstance(execution, dict)
                    and execution.get("status") == "COMPLETED"
                    and execution.get("downloadAvailable") is True):
                continue
            status, data, headers = client.download(
                f"/api/reviews/{review_id}/redline/download")
            if status != 200:
                raise CaptureError(f"redline {review_id} is downloadable but the download "
                                   f"answered {status}")
            target = files_dir / f"{review_id}.docx"
            target.write_bytes(data)
            content_type = next((value for name, value in headers.items()
                                 if name.lower() == "content-type"), DOCX_MEDIA_TYPE)
            disposition = next((value for name, value in headers.items()
                                if name.lower() == "content-disposition"), None)
            file_entries[review_id] = {
                "filename": filename_from_disposition(disposition)
                or f"{review_id}-redline.docx",
                "contentType": content_type,
                "sizeBytes": len(data),
                "sha256": hashlib.sha256(data).hexdigest(),
            }

    captured_at = utc_now()
    manifest = load_manifest(out_dir)
    floor = _merged_floor(out_dir, manifest, state)
    responses = diff_responses(writer.responses, floor)
    mutation_list = list(mutations or [])
    assert_no_secrets(mutation_list, f"mutations for {state}")
    _write_state(out_dir, state, captured_at, responses, mutation_list)

    states = list(manifest.get("states", []))
    if state not in states:
        states.append(state)
    prepared_knowledge = manifest.get("preparedKnowledge")
    manifest = {
        "version": 1,
        "states": states,
        "capturedAt": manifest.get("capturedAt") or captured_at,
        "ids": {**manifest.get("ids", {}), **_capture_ids(writer)},
        "files": {**manifest.get("files", {}), **file_entries},
    }
    if prepared_knowledge:
        manifest["preparedKnowledge"] = prepared_knowledge
    save_manifest(out_dir, manifest)
    return {
        "state": state,
        "capturedAt": captured_at,
        "stored": len(responses),
        "total": len(writer.responses),
        "files": file_entries,
        "ids": _capture_ids(writer),
        "manifest": manifest,
    }


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(description="Capture the demo API states")
    parser.add_argument("--base-url", required=True)
    parser.add_argument("--state", required=True)
    parser.add_argument("--out-dir", required=True)
    parser.add_argument("--mutations", help="JSON file with mutations since the last state")
    parser.add_argument("--skip-downloads", action="store_true",
                        help="developer dry runs only; leaves redline files missing")
    args = parser.parse_args(argv)
    mutations = None
    if args.mutations:
        mutations = _load_json(pathlib.Path(args.mutations))
        if mutations is None:
            print(f"mutations file not found: {args.mutations}", file=sys.stderr)
            return 2
    try:
        result = capture_state(args.base_url, args.state, args.out_dir, mutations,
                               download_redlines=not args.skip_downloads)
    except (CaptureError, OSError, urllib.error.URLError) as error:
        print(f"capture failed: {error}", file=sys.stderr)
        return 1
    print(f"captured {result['state']}: {result['stored']} stored of {result['total']} "
          f"responses, {len(result['files'])} redline file(s)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
