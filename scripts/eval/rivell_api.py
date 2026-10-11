"""Minimal HTTP client for the Rivell API (stdlib only)."""
import json
import mimetypes
import time
import urllib.error
import urllib.request
import uuid


class ApiError(Exception):
    def __init__(self, method, path, status, body):
        super().__init__(f"{method} {path} -> {status}: {str(body)[:300]}")
        self.status = status
        self.body = body


class RivellApi:
    def __init__(self, base_url):
        self.base_url = base_url.rstrip("/")

    def _send(self, method, path, data=None, headers=None, raw_body=False):
        req = urllib.request.Request(self.base_url + path, data=data, method=method,
                                     headers=headers or {})
        try:
            with urllib.request.urlopen(req, timeout=600) as resp:
                raw = resp.read()
                if raw_body:
                    return raw
        except urllib.error.HTTPError as e:
            raw = e.read()
            try:
                body = json.loads(raw)
            except ValueError:
                body = raw.decode(errors="replace")
            raise ApiError(method, path, e.code, body) from None
        payload = json.loads(raw) if raw else {}
        return payload.get("data", payload) if isinstance(payload, dict) else payload

    def get(self, path):
        return self._send("GET", path)

    def post(self, path, body=None):
        data = json.dumps(body if body is not None else {}).encode()
        return self._send("POST", path, data, {"content-type": "application/json"})

    def put(self, path, body):
        data = json.dumps(body).encode()
        return self._send("PUT", path, data, {"content-type": "application/json"})

    def get_bytes(self, path):
        return self._send("GET", path, raw_body=True)

    def upload(self, path, file_path, content=None):
        boundary = uuid.uuid4().hex
        name = file_path.name
        ctype = mimetypes.guess_type(name)[0] or "application/octet-stream"
        body = b"".join([
            f"--{boundary}\r\n".encode(),
            f'Content-Disposition: form-data; name="file"; filename="{name}"\r\n'.encode(),
            f"Content-Type: {ctype}\r\n\r\n".encode(),
            file_path.read_bytes() if content is None else content,
            f"\r\n--{boundary}--\r\n".encode(),
        ])
        return self._send("POST", path, body,
                          {"content-type": f"multipart/form-data; boundary={boundary}"})

    def wait_ready(self, timeout_s=240):
        deadline = time.time() + timeout_s
        while time.time() < deadline:
            try:
                self.get("/api/system/status")
                return
            except (ApiError, urllib.error.URLError, ConnectionError):
                time.sleep(2)
        raise TimeoutError("backend did not become ready")


def items(payload):
    """List endpoints return either a list or {items: [...]}."""
    if isinstance(payload, list):
        return payload
    return payload.get("items", [])
