"""Capture bookkeeping for the demo driver: record writes, then save a named state.

The driver talks to the real API through `MutationRecorder`, so every write's request and
response can be replayed by the static demo. Reads pass straight through.
"""
import pathlib

import capture


class MutationRecorder:
    """Wraps the API client, recording every write and delegating every read."""

    def __init__(self, api):
        self.api = api
        self.mutations = []

    def get(self, path):
        return self.api.get(path)

    def get_bytes(self, path):
        return self.api.get_bytes(path)

    def _record(self, method: str, path: str, request, call):
        try:
            body = call()
        except Exception as error:  # noqa: BLE001 - only typed API errors carry a status
            status = getattr(error, "status", None)
            if status is None:
                raise
            self.mutations.append({"method": method, "path": path, "request": request,
                                   "status": status, "body": getattr(error, "body", None)})
            raise
        # The API client unwraps the response envelope; the static demo needs the envelope the
        # real frontend parses. Errors keep their problem body and status, which the UI reads by
        # status alone.
        self.mutations.append({"method": method, "path": path, "request": request,
                               "status": 200,
                               "body": {"data": body, "status": 200}})
        return body

    def post(self, path, body=None):
        return self._record("POST", path, body, lambda: self.api.post(path, body))

    def put(self, path, body):
        return self._record("PUT", path, body, lambda: self.api.put(path, body))

    def upload(self, path, file_path, content=None):
        request = {"filename": pathlib.Path(file_path).name}
        return self._record("POST", path, request,
                            lambda: self.api.upload(path, file_path, content))

    def take(self) -> list[dict]:
        taken = self.mutations
        self.mutations = []
        return taken


def capture_ids(state: dict) -> dict:
    """The named ids the frontend needs, keyed by the corpus filename the visitor sees."""
    ids = {"reviews": {}, "contracts": {}, "artifacts": {}, "publications": {},
           "publicationNumbers": {}}
    for path, entry in (state.get("reviews") or {}).items():
        name = pathlib.Path(path).name
        if entry.get("reviewId"):
            ids["reviews"][name] = entry["reviewId"]
        if entry.get("contractId"):
            ids["contracts"][name] = entry["contractId"]
    for path, entry in (state.get("knowledge") or {}).items():
        name = pathlib.Path(path).name
        if entry.get("artifactId"):
            ids["artifacts"][name] = entry["artifactId"]
        if entry.get("publicationId"):
            ids["publications"][name] = entry["publicationId"]
        if entry.get("publicationNumber") is not None:
            ids["publicationNumbers"][name] = entry["publicationNumber"]
    k2 = (state.get("knowledge") or {}).get("knowledge/NDA Playbook.xlsx") or {}
    c5 = (state.get("reviews") or {}).get(
        "contracts/Sablepoint Analytics - Mutual NDA.docx") or {}
    named = {
        "k2ArtifactId": k2.get("artifactId"),
        "k2PublicationId": k2.get("publicationId"),
        "c5ReviewId": c5.get("reviewId"),
        "c5ContractId": c5.get("contractId"),
    }
    ids.update({name: value for name, value in named.items() if value})
    return ids


class CaptureSink:
    """Saves each checkpoint's capture and keeps the manifest's useful ids current."""

    def __init__(self, directory, base_url: str, recorder: MutationRecorder,
                 prepared_file):
        self.directory = pathlib.Path(directory)
        self.base_url = base_url
        self.recorder = recorder
        self.directory.mkdir(parents=True, exist_ok=True)
        capture.record_prepared_knowledge(self.directory, prepared_file)

    def capture(self, state: dict, name: str) -> None:
        mutations = self.recorder.take()
        result = capture.capture_state(self.base_url, name, self.directory, mutations)
        capture.update_manifest(self.directory, ids=capture_ids(state))
        print(f"capture {name}: {result['stored']} stored of {result['total']} responses, "
              f"{len(result['files'])} redline file(s)")
