#!/usr/bin/env python3
"""Record/replay HTTP tape for the three Rivell model ports.

Record mode forwards a request to the real provider and appends the request
body and response body to a JSONL file. Headers are never written. Replay mode
answers from the recording and answers 502 on a miss, naming the endpoint and a
short hash. Standard library only.
"""
import argparse
import gzip
import hashlib
import http.server
import json
import os
import pathlib
import re
import sys
import threading
import urllib.error
import urllib.request
from urllib.parse import urlsplit

DEFAULT_JEV = "https://api.typesafe.ai"
DEFAULT_OPENAI = "https://api.openai.com/v1"
MODEL_PATHS = {"/v1/systemone": "jev", "/v1/chat/completions": "openai",
               "/v1/embeddings": "openai"}
SECRET_KEY = re.compile(r"\bsk-[A-Za-z0-9_-]{20,}\b")


class TapeError(RuntimeError):
    """The tape cannot be loaded, checked or served as requested."""


def canonical_request(body: bytes) -> dict:
    try:
        request = json.loads(body.decode("utf-8"))
    except (UnicodeDecodeError, ValueError) as error:
        raise ValueError(f"request body is not valid JSON: {error}") from None
    if not isinstance(request, dict):
        raise ValueError("request body must be a JSON object")
    return request


def call_key(path: str, request: dict) -> str:
    canonical = json.dumps({"path": path, "body": request}, sort_keys=True,
                           separators=(",", ":"), ensure_ascii=False)
    return hashlib.sha256(canonical.encode("utf-8")).hexdigest()


def _record_lines(path: pathlib.Path):
    if path.suffix == ".gz":
        with gzip.open(path, "rt", encoding="utf-8") as source:
            yield from source
    else:
        with open(path, "r", encoding="utf-8") as source:
            yield from source


def load_records(path: pathlib.Path) -> list[dict]:
    path = pathlib.Path(path)
    if not path.is_file():
        raise TapeError(f"recording not found: {path}")
    records = []
    for line_number, line in enumerate(_record_lines(path), 1):
        if not line.strip():
            continue
        try:
            record = json.loads(line)
        except ValueError as error:
            raise TapeError(f"{path}:{line_number}: malformed record: {error}") from None
        if not isinstance(record, dict):
            raise TapeError(f"{path}:{line_number}: malformed record: not an object")
        records.append(record)
    return records


def _check_value(value, record_number: int):
    if isinstance(value, dict):
        for key, item in value.items():
            lowered = str(key).lower()
            if lowered in ("headers", "authorization"):
                raise TapeError(f"record {record_number}: forbidden field {key!r}")
            _check_value(item, record_number)
    elif isinstance(value, list):
        for item in value:
            _check_value(item, record_number)
    elif isinstance(value, str):
        if "Bearer " in value:
            raise TapeError(f"record {record_number}: forbidden text 'Bearer '")
        match = SECRET_KEY.search(value)
        if match:
            raise TapeError(f"record {record_number}: forbidden key-like text {match.group(0)[:7]}...")


def check_records(path: pathlib.Path) -> dict:
    path = pathlib.Path(path)
    records = load_records(path)
    paths = set()
    for number, record in enumerate(records, 1):
        key, request_path, request = (record.get("key"), record.get("path"),
                                      record.get("request"))
        if not isinstance(key, str) or not isinstance(request_path, str) \
                or not isinstance(request, dict) or "response" not in record:
            raise TapeError(f"{path}: record {number}: malformed record")
        paths.add(request_path)
        _check_value(record, number)
        if not re.fullmatch(r"[0-9a-f]{64}", key):
            raise TapeError(f"{path}: record {number}: malformed key")
        if call_key(request_path, request) != key:
            raise TapeError(f"{path}: record {number}: key does not match its path and request")
    return {"calls": len(records), "paths": sorted(paths), "bytes": path.stat().st_size}


def pack_records(src: pathlib.Path, dst: pathlib.Path) -> int:
    src, dst = pathlib.Path(src), pathlib.Path(dst)
    records = load_records(src)
    first = {}
    for record in records:
        first.setdefault(record.get("key"), record)
    ordered = [first[key] for key in sorted(first)]
    blob = "".join(json.dumps(record, sort_keys=True, separators=(",", ":"),
                              ensure_ascii=False) + "\n" for record in ordered)
    dst.parent.mkdir(parents=True, exist_ok=True)
    with open(dst, "wb") as raw:
        with gzip.GzipFile(filename="", mode="wb", fileobj=raw, mtime=0) as packed:
            packed.write(blob.encode("utf-8"))
    return len(ordered)


class TapeStore:
    def __init__(self, path, mode, log=print):
        if mode not in ("record", "replay"):
            raise TapeError(f"unknown tape mode {mode!r}")
        self.path = pathlib.Path(path)
        if mode == "record" and self.path.suffix == ".gz":
            raise TapeError("record mode writes plain JSONL; pack the file with --pack first")
        self.mode = mode
        self.log = log
        self.hits = 0
        self.misses = []
        self.unrouted = []
        self._lock = threading.Lock()
        self._records = {}
        if mode == "replay":
            loaded = load_records(self.path)
        else:
            self.path.parent.mkdir(parents=True, exist_ok=True)
            loaded = load_records(self.path) if self.path.exists() else []
        for record in loaded:
            self._records.setdefault(record.get("key"), record)

    @property
    def records(self) -> list[dict]:
        with self._lock:
            return list(self._records.values())

    def lookup(self, key: str) -> dict | None:
        with self._lock:
            record = self._records.get(key)
            if record is not None:
                self.hits += 1
            return record

    def add(self, record: dict) -> None:
        with self._lock:
            if record["key"] in self._records:
                return
            self._records[record["key"]] = record
            line = json.dumps(record, separators=(",", ":"), ensure_ascii=False) + "\n"
            with open(self.path, "a", encoding="utf-8") as sink:
                sink.write(line)
                sink.flush()
                os.fsync(sink.fileno())

    def register_miss(self, miss: dict) -> None:
        with self._lock:
            self.misses.append(miss)

    def register_unrouted(self, request: dict) -> None:
        """A request that no tape route could serve, so it was never recordable."""
        with self._lock:
            self.unrouted.append(request)


class TapeServer:
    def __init__(self, store, upstreams, miss_dir, log=None, port=0):
        self.store = store
        self.upstreams = dict(upstreams)
        self.miss_dir = pathlib.Path(miss_dir)
        self.log = log if log is not None else store.log
        self.port = port
        self.httpd = None
        self.thread = None

    @property
    def url(self) -> str:
        if self.httpd is None:
            raise TapeError("tape server is not running")
        return f"http://127.0.0.1:{self.httpd.server_address[1]}"

    def start(self) -> None:
        self.miss_dir.mkdir(parents=True, exist_ok=True)
        server = self

        class Handler(http.server.BaseHTTPRequestHandler):
            protocol_version = "HTTP/1.1"

            def do_GET(self):
                server._handle("GET", self)

            def do_POST(self):
                server._handle("POST", self)

            def log_message(self, *args):
                pass

        self.httpd = http.server.ThreadingHTTPServer(("127.0.0.1", self.port), Handler)
        self.httpd.daemon_threads = True
        self.thread = threading.Thread(target=self.httpd.serve_forever, daemon=True)
        self.thread.start()

    def stop(self) -> None:
        if self.httpd is None:
            return
        self.httpd.shutdown()
        self.httpd.server_close()
        if self.thread is not None:
            self.thread.join(timeout=5)
        self.httpd = None
        self.thread = None

    def _handle(self, method: str, handler) -> None:
        path = urlsplit(handler.path).path
        length = int(handler.headers.get("Content-Length") or 0)
        body = handler.rfile.read(length) if length else b""
        if method == "GET" and path == "/__tape__/health":
            self._respond(handler, 200, {
                "mode": self.store.mode,
                "records": len(self.store.records),
                "hits": self.store.hits,
                "misses": list(self.store.misses),
                "unrouted": list(self.store.unrouted),
            })
            return
        if method == "GET" and path == "/__tape__/misses":
            self._respond(handler, 200, {"misses": list(self.store.misses),
                                         "unrouted": list(self.store.unrouted)})
            return
        if method != "POST" or path not in MODEL_PATHS:
            if method == "POST":
                self.store.register_unrouted({"method": method, "path": path})
            self._respond(handler, 404, {"error": "unknown tape path", "path": path})
            return
        try:
            request = canonical_request(body)
        except ValueError as error:
            self.store.register_unrouted({"method": method, "path": path,
                                          "error": "request body is not a JSON object"})
            self._respond(handler, 400, {"error": str(error), "path": path})
            return
        key = call_key(path, request)
        record = self.store.lookup(key)
        if record is not None:
            self.log(f"POST {path} {key[:8]} 200")
            self._respond(handler, 200, record["response"])
            return
        if self.store.mode == "replay":
            self._miss(handler, path, key, request, body)
            return
        self._forward(handler, path, key, request, body)

    def _forward(self, handler, path: str, key: str, request: dict, body: bytes) -> None:
        kind = MODEL_PATHS[path]
        base = self.upstreams.get(kind)
        if kind == "jev":
            url = (base or DEFAULT_JEV).rstrip("/") + path
        else:
            url = (base or DEFAULT_OPENAI).rstrip("/") + path.removeprefix("/v1")
        headers = {"Content-Type": "application/json"}
        authorization = handler.headers.get("Authorization")
        if authorization is not None:
            headers["Authorization"] = authorization
        try:
            upstream_request = urllib.request.Request(url, data=body, method="POST",
                                                      headers=headers)
            with urllib.request.urlopen(upstream_request, timeout=300) as response:
                status, response_body = response.status, response.read()
        except urllib.error.HTTPError as error:
            status, response_body = error.code, error.read()
        except (urllib.error.URLError, TimeoutError, OSError):
            self.log(f"POST {path} {key[:8]} 502")
            self._respond(handler, 502, {"error": "tape upstream failed",
                                         "path": path, "key": key[:16]})
            return
        if status == 200:
            try:
                parsed = json.loads(response_body.decode("utf-8"))
            except (UnicodeDecodeError, ValueError):
                self.log(f"POST {path} {key[:8]} 502")
                self._respond(handler, 502, {"error": "tape upstream returned invalid JSON",
                                             "path": path, "key": key[:16]})
                return
            self.store.add({"key": key, "path": path, "request": request,
                            "response": parsed})
        self.log(f"POST {path} {key[:8]} {status}")
        self._respond_bytes(handler, status, response_body)

    def _miss(self, handler, path: str, key: str, request: dict, body: bytes) -> None:
        miss = {"path": path, "key": key, "request": request}
        self.store.register_miss(miss)
        target = self.miss_dir / f"miss-{key[:16]}.json"
        target.write_text(json.dumps(miss, indent=1, ensure_ascii=False) + "\n")
        self.log(f"POST {path} {key[:8]} 502")
        self._respond(handler, 502, {"error": "tape replay miss", "path": path,
                                     "key": key[:16], "requestBytes": len(body)})

    def _respond(self, handler, status: int, payload) -> None:
        self._respond_bytes(handler, status,
                            json.dumps(payload, ensure_ascii=False).encode("utf-8"))

    @staticmethod
    def _respond_bytes(handler, status: int, payload: bytes) -> None:
        handler.send_response(status)
        handler.send_header("Content-Type", "application/json")
        handler.send_header("Content-Length", str(len(payload)))
        handler.end_headers()
        try:
            handler.wfile.write(payload)
        except BrokenPipeError:
            pass


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(description="Record or replay Rivell model calls")
    parser.add_argument("--mode", choices=("record", "replay"))
    parser.add_argument("--file", type=pathlib.Path)
    parser.add_argument("--port", type=int, default=18090)
    parser.add_argument("--miss-dir", type=pathlib.Path)
    parser.add_argument("--upstream-jev")
    parser.add_argument("--upstream-openai")
    parser.add_argument("--pack", nargs=2, metavar=("SRC", "DST"))
    parser.add_argument("--check")
    args = parser.parse_args(argv)
    if args.check:
        try:
            result = check_records(pathlib.Path(args.check))
        except TapeError as error:
            print(error, file=sys.stderr)
            return 2
        print(f"calls={result['calls']} paths={len(result['paths'])} bytes={result['bytes']}")
        return 0
    if args.pack:
        try:
            count = pack_records(pathlib.Path(args.pack[0]), pathlib.Path(args.pack[1]))
            total = len(load_records(pathlib.Path(args.pack[0])))
        except TapeError as error:
            print(error, file=sys.stderr)
            return 2
        print(f"packed {count} of {total} calls")
        return 0
    if not args.mode or not args.file or not args.miss_dir:
        parser.error("--mode, --file and --miss-dir are required to serve the tape")
    try:
        store = TapeStore(args.file, args.mode)
    except TapeError as error:
        print(error, file=sys.stderr)
        return 2
    server = TapeServer(store,
                        upstreams={"jev": args.upstream_jev or DEFAULT_JEV,
                                   "openai": args.upstream_openai or DEFAULT_OPENAI},
                        miss_dir=args.miss_dir, port=args.port)
    try:
        server.start()
    except OSError as error:
        print(f"tape failed to start: {error}", file=sys.stderr)
        return 2
    print(f"tape {args.mode} on {server.url} ({len(store.records)} records)")
    try:
        server.thread.join()
    except KeyboardInterrupt:
        pass
    finally:
        server.stop()
    return 0


if __name__ == "__main__":
    sys.exit(main())
