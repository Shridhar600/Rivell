import contextlib
import hashlib
import http.server
import json
import pathlib
import tempfile
import threading
import unittest
import urllib.error
import urllib.request

import tape

REPO = pathlib.Path(__file__).resolve().parents[2]
SCRATCH = REPO / "backend" / "target" / "demo-test"
SENTINEL = "sk-test-not-a-real-key-000000000000"
JEV_BODY = {"model": "m", "answers": {"q": {"probability": 0.9}},
            "usage": {"inputTokens": 1, "outputTokens": 1}}


@contextlib.contextmanager
def scratch_dir():
    SCRATCH.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(dir=SCRATCH) as tmp:
        yield pathlib.Path(tmp)


class _FakeUpstream:
    """A provider stand-in that records the Authorization header and counts calls."""

    def __init__(self, body):
        self.calls = 0
        self.authorizations = []
        upstream = self

        class Handler(http.server.BaseHTTPRequestHandler):
            protocol_version = "HTTP/1.1"

            def do_POST(self):
                length = int(self.headers.get("Content-Length") or 0)
                self.rfile.read(length)
                upstream.calls += 1
                upstream.authorizations.append(self.headers.get("Authorization"))
                payload = json.dumps(body).encode()
                self.send_response(200)
                self.send_header("Content-Type", "application/json")
                self.send_header("Content-Length", str(len(payload)))
                self.end_headers()
                self.wfile.write(payload)

            def log_message(self, *args):
                pass

        self.httpd = http.server.ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        self.httpd.daemon_threads = True
        self.thread = threading.Thread(target=self.httpd.serve_forever, daemon=True)
        self.thread.start()

    @property
    def url(self):
        return f"http://127.0.0.1:{self.httpd.server_address[1]}"

    def stop(self):
        self.httpd.shutdown()
        self.httpd.server_close()
        self.thread.join(timeout=5)


def http_post(url, body, headers):
    request = urllib.request.Request(url, data=body, method="POST", headers=headers)
    try:
        with urllib.request.urlopen(request, timeout=30) as response:
            return response.status, response.read().decode()
    except urllib.error.HTTPError as error:
        return error.code, error.read().decode()


class TapeRecordTest(unittest.TestCase):

    def test_recording_never_contains_the_authorization_header_or_the_key(self):
        upstream = _FakeUpstream(JEV_BODY)
        with scratch_dir() as tmp:
            logs = []
            store = tape.TapeStore(tmp / "calls.jsonl", "record", log=logs.append)
            server = tape.TapeServer(store,
                                     upstreams={"jev": upstream.url, "openai": upstream.url},
                                     miss_dir=tmp / "misses", log=logs.append)
            server.start()
            try:
                status, _ = http_post(server.url + "/v1/systemone",
                                      b'{"model":"m","state":{},"questions":{}}',
                                      {"Authorization": f"Bearer {SENTINEL}"})
                self.assertEqual(status, 200)
            finally:
                server.stop()
            written = (tmp / "calls.jsonl").read_text()
            self.assertNotIn("Authorization", written)
            self.assertNotIn(SENTINEL, written)
            self.assertNotIn("Authorization", "\n".join(logs))
            self.assertNotIn(SENTINEL, "\n".join(logs))
            self.assertEqual(upstream.authorizations, [f"Bearer {SENTINEL}"])
            self.assertEqual(tape.check_records(tmp / "calls.jsonl")["calls"], 1)

    def test_object_key_order_does_not_change_the_key_but_list_order_does(self):
        a = b'{"model":"m","state":{"b":1,"a":2},"questions":{"q2":{},"q1":{}}}'
        b = b'{"questions":{"q1":{},"q2":{}},"state":{"a":2,"b":1},"model":"m"}'
        self.assertEqual(tape.call_key("/v1/systemone", tape.canonical_request(a)),
                         tape.call_key("/v1/systemone", tape.canonical_request(b)))
        c = b'{"model":"m","input":["x","y"]}'
        d = b'{"model":"m","input":["y","x"]}'
        self.assertNotEqual(tape.call_key("/v1/embeddings", tape.canonical_request(c)),
                            tape.call_key("/v1/embeddings", tape.canonical_request(d)))

    def test_a_replay_miss_fails_loudly_and_writes_the_request(self):
        upstream = _FakeUpstream({"error": "must not be called"})
        with scratch_dir() as tmp:
            (tmp / "calls.jsonl").write_text("")
            server = tape.TapeServer(tape.TapeStore(tmp / "calls.jsonl", "replay"),
                                     upstreams={"jev": upstream.url, "openai": upstream.url},
                                     miss_dir=tmp / "misses", log=lambda _: None)
            server.start()
            try:
                status, body = http_post(server.url + "/v1/systemone",
                                         b'{"model":"m","state":{},"questions":{}}', {})
                unrouted_status, _ = http_post(server.url + "/v1/not-a-provider", b"{}", {})
                with urllib.request.urlopen(server.url + "/__tape__/health") as response:
                    health = json.loads(response.read())
            finally:
                server.stop()
            self.assertEqual(status, 502)
            self.assertEqual(json.loads(body)["error"], "tape replay miss")
            self.assertEqual(json.loads(body)["path"], "/v1/systemone")
            self.assertEqual(len(json.loads(body)["key"]), 16)
            self.assertEqual(upstream.calls, 0)
            files = list((tmp / "misses").glob("miss-*.json"))
            self.assertEqual(len(files), 1)
            self.assertEqual(json.loads(files[0].read_text())["path"], "/v1/systemone")
            self.assertEqual(unrouted_status, 404)
            self.assertEqual([request["path"] for request in health["unrouted"]],
                             ["/v1/not-a-provider"])

    def test_record_reuses_a_recorded_answer_instead_of_calling_again(self):
        upstream = _FakeUpstream(JEV_BODY)
        with scratch_dir() as tmp:
            store = tape.TapeStore(tmp / "calls.jsonl", "record", log=lambda _: None)
            server = tape.TapeServer(store,
                                     upstreams={"jev": upstream.url, "openai": upstream.url},
                                     miss_dir=tmp / "misses", log=lambda _: None)
            server.start()
            try:
                first = http_post(server.url + "/v1/systemone", b'{"model":"m"}', {})
                upstream.stop()
                second = http_post(server.url + "/v1/systemone", b'{"model":"m"}', {})
            finally:
                server.stop()
            self.assertEqual(first[0], 200)
            self.assertEqual(second[0], 200)
            self.assertEqual(json.loads(second[1]), JEV_BODY)
            self.assertEqual(upstream.calls, 1)
            self.assertEqual(len((tmp / "calls.jsonl").read_text().splitlines()), 1)

    def test_pack_dedupes_sorts_and_is_byte_deterministic(self):
        with scratch_dir() as tmp:
            src = tmp / "calls.jsonl"
            records = [{"key": "b" * 64, "path": "/v1/embeddings",
                        "request": {"model": "m", "input": ["x"]}, "response": JEV_BODY},
                       {"key": "a" * 64, "path": "/v1/systemone",
                        "request": {"model": "m"}, "response": JEV_BODY},
                       {"key": "b" * 64, "path": "/v1/embeddings",
                        "request": {"model": "m", "input": ["x"]},
                        "response": {"model": "a later duplicate"}}]
            src.write_text("".join(json.dumps(r) + "\n" for r in records))
            self.assertEqual(tape.pack_records(src, tmp / "one.jsonl.gz"), 2)
            self.assertEqual(tape.pack_records(src, tmp / "two.jsonl.gz"), 2)
            self.assertEqual(hashlib.sha256((tmp / "one.jsonl.gz").read_bytes()).hexdigest(),
                             hashlib.sha256((tmp / "two.jsonl.gz").read_bytes()).hexdigest())
            packed = tape.load_records(tmp / "one.jsonl.gz")
            self.assertEqual([r["key"] for r in packed], ["a" * 64, "b" * 64])
            self.assertEqual(packed[1]["response"], JEV_BODY)

    def test_check_rejects_a_record_with_a_headers_field(self):
        with scratch_dir() as tmp:
            src = tmp / "bad.jsonl"
            request = {"model": "m"}
            src.write_text(json.dumps({"key": tape.call_key("/v1/systemone", request),
                                       "path": "/v1/systemone",
                                       "headers": {"Authorization": "Bearer x"},
                                       "request": request,
                                       "response": JEV_BODY}) + "\n")
            with self.assertRaisesRegex(tape.TapeError, "headers"):
                tape.check_records(src)


if __name__ == "__main__":
    unittest.main()
