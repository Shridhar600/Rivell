import json
import pathlib
import shutil
import unittest
import unittest.mock
import uuid

import capture

SCRATCH = (pathlib.Path(__file__).resolve().parents[2]
           / ".superpowers" / "sdd" / "demo-part2" / "tmp")


def scratch_dir() -> pathlib.Path:
    SCRATCH.mkdir(parents=True, exist_ok=True)
    directory = SCRATCH / f"test-capture-{uuid.uuid4().hex}"
    directory.mkdir()
    return directory


class KeyTest(unittest.TestCase):

    def test_a_plain_path_has_no_query(self):
        self.assertEqual(capture.parse_key("/api/reviews"), "GET /api/reviews")

    def test_limit_and_cursor_are_dropped(self):
        self.assertEqual(
            capture.parse_key("/api/reviews?limit=25&cursor=abc"),
            "GET /api/reviews")

    def test_remaining_parameters_are_sorted(self):
        self.assertEqual(
            capture.parse_key("/api/reviews?limit=100&contractId=c1&status=COMPLETED"),
            "GET /api/reviews?contractId=c1&status=COMPLETED")

    def test_a_query_of_only_limit_is_the_bare_key(self):
        self.assertEqual(capture.parse_key("/api/knowledge/artifacts?limit=100"),
                         "GET /api/knowledge/artifacts")


class DedupeTest(unittest.TestCase):

    def state(self, body, status=200):
        return {"status": status, "body": body}

    def test_an_identical_response_is_omitted(self):
        earlier = {"GET /api/reviews": self.state({"data": {"items": [{"id": "r1"}]},
                                                   "status": 200, "timestamp": "t1"})}
        current = {"GET /api/reviews": self.state({"data": {"items": [{"id": "r1"}]},
                                                    "status": 200, "timestamp": "t2"})}
        self.assertEqual(capture.diff_responses(current, earlier), {})

    def test_a_changed_body_is_stored(self):
        earlier = {"GET /api/reviews": self.state({"data": {"items": [{"id": "r1"}]}})}
        current = {"GET /api/reviews": self.state({"data": {"items": [{"id": "r2"}]}})}
        self.assertIn("GET /api/reviews", capture.diff_responses(current, earlier))

    def test_a_changed_status_is_stored(self):
        earlier = {"GET /api/reviews/x/redline": self.state({}, 404)}
        current = {"GET /api/reviews/x/redline": self.state({}, 200)}
        self.assertIn("GET /api/reviews/x/redline",
                      capture.diff_responses(current, earlier))

    def test_a_new_key_is_stored(self):
        current = {"GET /api/reviews/x": self.state({"data": {}})}
        self.assertIn("GET /api/reviews/x", capture.diff_responses(current, {}))


class SecretScanTest(unittest.TestCase):

    def test_a_bearer_header_is_refused(self):
        with self.assertRaisesRegex(capture.CaptureError, "secret scan failed"):
            capture.assert_no_secrets({"Authorization": "Bearer sk-live-1234567890"}, "test")

    def test_a_json_key_is_refused(self):
        with self.assertRaisesRegex(capture.CaptureError, "secret scan failed"):
            capture.assert_no_secrets({"api_key": "not-a-secret"}, "test")

    def test_a_key_prefix_is_refused(self):
        with self.assertRaisesRegex(capture.CaptureError, "secret scan failed"):
            capture.assert_no_secrets({"note": "sk-abcdefghijklmnop"}, "test")

    def test_an_authorization_word_is_refused(self):
        with self.assertRaisesRegex(capture.CaptureError, "secret scan failed"):
            capture.assert_no_secrets({"note": "Authorization"}, "test")

    def test_a_contract_word_containing_sk_is_not_a_secret(self):
        capture.assert_no_secrets(
            {"text": "A risk-based approach under the governing law."}, "test")

    def test_ordinary_words_are_fine(self):
        capture.assert_no_secrets({"text": "Bramber Software Limited"}, "test")


class StubClient:

    def __init__(self, pages):
        self.pages = list(pages)
        self.paths = []

    def get(self, path):
        self.paths.append(path)
        return self.pages.pop(0)


class ListTest(unittest.TestCase):

    def test_every_cursor_page_is_merged_under_the_plain_key(self):
        first = {"data": {"items": [{"id": "a"}], "nextCursor": "c1", "truncated": False},
                 "status": 200, "timestamp": "t1"}
        second = {"data": {"items": [{"id": "b"}], "nextCursor": None, "truncated": False},
                  "status": 200, "timestamp": "t2"}
        client = StubClient([(200, first, {}), (200, second, {})])
        writer = capture.StateWriter()
        returned = capture._collect_pages(client, "/api/reviews", writer)
        self.assertEqual([item["id"] for item in capture._items(returned)], ["a", "b"])
        self.assertEqual(client.paths[0], "/api/reviews?limit=100")
        self.assertEqual(client.paths[1], "/api/reviews?limit=100&cursor=c1")
        stored = writer.responses["GET /api/reviews"]
        self.assertEqual([item["id"] for item in stored["body"]["data"]["items"]], ["a", "b"])
        self.assertIsNone(stored["body"]["data"]["nextCursor"])

    def test_a_truncated_list_fails_rather_than_shipping_a_partial_capture(self):
        body = {"data": {"items": [{"id": "a"}], "nextCursor": None, "truncated": True}}
        client = StubClient([(200, body, {})])
        writer = capture.StateWriter()
        with self.assertRaisesRegex(capture.CaptureError, "truncated"):
            capture._collect_pages(client, "/api/reviews", writer)

    def test_a_non_200_list_is_stored_with_its_status(self):
        client = StubClient([(403, {"detail": "forbidden"}, {})])
        writer = capture.StateWriter()
        capture._collect_pages(client, "/api/reviews", writer)
        self.assertEqual(writer.responses["GET /api/reviews"]["status"], 403)
        self.assertEqual(writer.responses["GET /api/reviews"]["body"],
                         {"detail": "forbidden"})


class ManifestTest(unittest.TestCase):

    def test_manifest_keeps_the_first_capture_time_and_state_order(self):
        directory = scratch_dir()
        try:
            manifest = capture.load_manifest(directory)
            manifest.update({"states": ["base"], "capturedAt": "2026-01-01T00:00:00Z",
                             "ids": {"reviews": {"a.docx": "r1"}}})
            capture.save_manifest(directory, manifest)
            updated = capture.update_manifest(
                directory, ids={"artifacts": {"k2.xlsx": "a1"}})
            self.assertEqual(updated["capturedAt"], "2026-01-01T00:00:00Z")
            self.assertEqual(updated["states"], ["base"])
            self.assertEqual(updated["ids"]["reviews"], {"a.docx": "r1"})
            self.assertEqual(updated["ids"]["artifacts"], {"k2.xlsx": "a1"})
        finally:
            shutil.rmtree(directory, ignore_errors=True)

    def test_prepared_knowledge_is_copied_and_recorded(self):
        directory = scratch_dir()
        try:
            source = directory / "NDA Playbook.xlsx"
            source.write_bytes(b"xlsx-bytes")
            out = directory / "capture"
            entry = capture.record_prepared_knowledge(out, source)
            self.assertEqual((out / "files" / source.name).read_bytes(), b"xlsx-bytes")
            self.assertEqual(entry["size"], len(b"xlsx-bytes"))
            self.assertEqual(capture.load_manifest(out)["preparedKnowledge"], entry)
        finally:
            shutil.rmtree(directory, ignore_errors=True)

    def test_an_invalid_state_name_is_refused_before_any_request(self):
        with self.assertRaisesRegex(capture.CaptureError, "invalid state name"):
            capture.capture_state("http://127.0.0.1:1", "../base", "/nonexistent")


class DispositionTest(unittest.TestCase):

    def test_a_plain_filename_is_read(self):
        self.assertEqual(
            capture.filename_from_disposition('attachment; filename="redline.docx"'),
            "redline.docx")

    def test_a_utf8_filename_is_read(self):
        self.assertEqual(
            capture.filename_from_disposition(
                "attachment; filename*=UTF-8''nda%20redline.docx"),
            "nda redline.docx")

    def test_no_header_is_null(self):
        self.assertIsNone(capture.filename_from_disposition(None))


class CaptureStubClient:
    """A tiny in-memory API so capture_state can be exercised without a server."""

    review = {"id": "r1", "contractId": "c1", "contractVersionNumber": 1,
              "contractFilename": "C1.docx"}
    human_review = {"data": {"reviewId": "r1", "resolvedFindings": 0}}

    def __init__(self, base_url, timeout=60):
        self.base_url = base_url

    def get(self, path):
        route = path.split("?")[0]
        if route == "/api/reviews":
            return 200, {"data": {"items": [self.review], "nextCursor": None}}, {}
        if route == "/api/reviews/packs":
            return 200, {"data": {"packs": []}}, {}
        if route == f"/api/reviews/{self.review['id']}":
            return 200, {"data": {"id": "r1"}}, {}
        if route.endswith("/progress"):
            return 200, {"data": {"reviewStatus": "COMPLETED"}}, {}
        if route.endswith("/findings"):
            return 200, {"data": {"items": [{"id": "f1"}], "truncated": False}}, {}
        if route.endswith("/findings/f1"):
            return 200, {"data": {"id": "f1"}}, {}
        if route.endswith("/findings/f1/decisions"):
            return 200, {"data": {"items": [], "truncated": False}}, {}
        if route.endswith("/clause-marks"):
            return 200, {"data": {"clauses": []}}, {}
        if route.endswith("/decisions"):
            return 200, {"data": {"items": [], "truncated": False}}, {}
        if route.endswith("/human-review"):
            return 200, self.human_review, {}
        if route.endswith("/approved-change-set"):
            return 404, {"detail": "no change set"}, {}
        if route.endswith("/redline"):
            return 200, {"data": {"status": "COMPLETED", "downloadAvailable": True}}, {}
        if route == "/api/contracts/c1":
            return 200, {"data": {"id": "c1"}}, {}
        if route.endswith("/structure") and "/contracts/" in route:
            return 200, {"data": {"nodes": []}}, {}
        if route.endswith("/semantics"):
            return 200, {"data": {}}, {}
        if route == "/api/taxonomy/concepts":
            return 200, {"version": 1, "parents": []}, {}
        if route == "/api/knowledge/artifacts":
            return 200, {"data": [{"id": "a1", "filename": "K1.docx"}]}, {}
        if route == "/api/knowledge/artifacts/a1":
            return 200, {"data": {"id": "a1"}}, {}
        if route == "/api/knowledge/artifacts/a1/structure":
            return 200, {"data": []}, {}
        if route == "/api/knowledge/artifacts/a1/units":
            return 200, {"data": []}, {}
        if route == "/api/knowledge/artifacts/a1/candidates":
            return 200, {"data": [{"id": "k1"}]}, {}
        if route == "/api/knowledge/candidates/k1":
            return 200, {"data": {"id": "k1"}}, {}
        if route == "/api/knowledge/publications":
            return 200, {"data": [{"id": "p1", "publicationNumber": 1,
                                   "items": [{"candidateId": "k1"}]}]}, {}
        if route == "/api/knowledge/publications/p1":
            return 200, {"data": {"id": "p1"}}, {}
        raise AssertionError(f"unexpected path {path}")

    def download(self, path):
        return 200, b"PK\x03\x04docx", {
            "Content-Type": capture.DOCX_MEDIA_TYPE,
            "Content-Disposition": 'attachment; filename="C1-redline.docx"',
        }


class CaptureStateTest(unittest.TestCase):

    def test_capture_state_writes_a_state_manifest_and_redline_file(self):
        directory = scratch_dir()
        try:
            with unittest.mock.patch.object(capture, "Client", CaptureStubClient):
                result = capture.capture_state("http://example", "base", directory)
            self.assertEqual(result["stored"], result["total"])
            state = json.loads((directory / "base.json").read_text())
            self.assertEqual(state["state"], "base")
            self.assertIn("GET /api/reviews", state["responses"])
            self.assertEqual(state["responses"]["GET /api/reviews/r1/approved-change-set"]
                             ["status"], 404)
            self.assertEqual((directory / "files" / "r1.docx").read_bytes(),
                             b"PK\x03\x04docx")
            manifest = capture.load_manifest(directory)
            self.assertEqual(manifest["states"], ["base"])
            self.assertEqual(manifest["files"]["r1"]["filename"], "C1-redline.docx")
            self.assertEqual(manifest["ids"]["reviews"]["C1.docx"], "r1")
            self.assertEqual(manifest["ids"]["artifacts"]["K1.docx"], "a1")
            self.assertEqual(manifest["ids"]["publications"]["K1.docx"], "p1")
        finally:
            shutil.rmtree(directory, ignore_errors=True)

    def test_a_later_state_stores_only_responses_that_changed(self):
        directory = scratch_dir()
        try:
            with unittest.mock.patch.object(capture, "Client", CaptureStubClient):
                capture.capture_state("http://example", "base", directory)
                original = capture.load_manifest(directory)["capturedAt"]
                CaptureStubClient.human_review = {"data": {"reviewId": "r1",
                                                    "resolvedFindings": 1}}
                capture.capture_state("http://example", "later", directory)
            later = json.loads((directory / "later.json").read_text())
            self.assertEqual(set(later["responses"]), {
                "GET /api/reviews/r1/human-review",
            })
            manifest = capture.load_manifest(directory)
            self.assertEqual(manifest["states"], ["base", "later"])
            self.assertEqual(manifest["capturedAt"], original)
        finally:
            CaptureStubClient.human_review = {"data": {"reviewId": "r1", "resolvedFindings": 0}}
            shutil.rmtree(directory, ignore_errors=True)


if __name__ == "__main__":
    unittest.main()
