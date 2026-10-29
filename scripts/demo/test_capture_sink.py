import pathlib
import shutil
import unittest
import unittest.mock
import uuid

import capture
import capture_sink

SCRATCH = (pathlib.Path(__file__).resolve().parents[2]
           / ".superpowers" / "sdd" / "demo-part2" / "tmp")


def scratch_dir() -> pathlib.Path:
    SCRATCH.mkdir(parents=True, exist_ok=True)
    directory = SCRATCH / f"test-sink-{uuid.uuid4().hex}"
    directory.mkdir()
    return directory


class StubApi:
    def __init__(self):
        self.calls = []

    def post(self, path, body=None):
        self.calls.append(("post", path, body))
        return {"ok": True}

    def put(self, path, body):
        self.calls.append(("put", path, body))
        return {"ok": True}

    def upload(self, path, file_path, content=None):
        self.calls.append(("upload", path, str(file_path)))
        return {"ok": True}

    def get(self, path):
        return {"ok": True}

    def get_bytes(self, path):
        return b"bytes"


class Failure(Exception):
    def __init__(self, status, body):
        super().__init__(body)
        self.status = status
        self.body = body


class StubFailingApi(StubApi):
    def put(self, path, body):
        raise Failure(409, {"detail": "already decided"})


class MutationRecorderTest(unittest.TestCase):

    def test_post_records_method_path_request_and_response(self):
        recorder = capture_sink.MutationRecorder(StubApi())
        recorder.post("/api/thing", {"a": 1})
        self.assertEqual(recorder.mutations, [{
            "method": "POST", "path": "/api/thing", "request": {"a": 1},
            "status": 200, "body": {"data": {"ok": True}, "status": 200},
        }])

    def test_post_without_a_body_records_null(self):
        recorder = capture_sink.MutationRecorder(StubApi())
        recorder.post("/api/finalize")
        self.assertIsNone(recorder.mutations[0]["request"])

    def test_upload_records_only_the_filename(self):
        recorder = capture_sink.MutationRecorder(StubApi())
        recorder.upload("/api/knowledge/artifacts?x=1", pathlib.Path("file.xlsx"),
                        content=b"bytes")
        self.assertEqual(recorder.mutations[0]["request"], {"filename": "file.xlsx"})

    def test_a_failed_write_is_recorded_and_reraised(self):
        recorder = capture_sink.MutationRecorder(StubFailingApi())
        with self.assertRaises(Failure):
            recorder.put("/api/decision", {"action": "ACCEPT"})
        self.assertEqual(recorder.mutations[0]["status"], 409)
        self.assertEqual(recorder.mutations[0]["body"], {"detail": "already decided"})

    def test_an_untyped_error_is_not_recorded(self):
        recorder = capture_sink.MutationRecorder(StubApi())
        with unittest.mock.patch.object(recorder.api, "post", side_effect=OSError("down")):
            with self.assertRaises(OSError):
                recorder.post("/api/thing", {})
        self.assertEqual(recorder.mutations, [])

    def test_take_clears_the_log(self):
        recorder = capture_sink.MutationRecorder(StubApi())
        recorder.post("/api/one", {})
        self.assertEqual(len(recorder.take()), 1)
        self.assertEqual(recorder.take(), [])

    def test_reads_pass_through(self):
        api = StubApi()
        recorder = capture_sink.MutationRecorder(api)
        self.assertEqual(recorder.get("/api/reviews"), {"ok": True})
        self.assertEqual(recorder.get_bytes("/api/x"), b"bytes")
        self.assertEqual(recorder.mutations, [])


class IdsTest(unittest.TestCase):

    def test_state_ids_are_keyed_by_the_corpus_filename(self):
        state = {
            "reviews": {
                "contracts/Sablepoint Analytics - Mutual NDA.docx": {
                    "reviewId": "r5", "contractId": "c5",
                },
                "contracts/Kestrel Assurance - Order Form KAG-2026-014.docx": {
                    "reviewId": "r2", "contractId": "c2",
                },
            },
            "knowledge": {
                "knowledge/NDA Playbook.xlsx": {
                    "artifactId": "a2", "publicationId": "p4", "publicationNumber": 4,
                },
            },
        }
        ids = capture_sink.capture_ids(state)
        self.assertEqual(ids["reviews"]["Sablepoint Analytics - Mutual NDA.docx"], "r5")
        self.assertEqual(ids["contracts"]["Kestrel Assurance - Order Form KAG-2026-014.docx"],
                         "c2")
        self.assertEqual(ids["artifacts"]["NDA Playbook.xlsx"], "a2")
        self.assertEqual(ids["publications"]["NDA Playbook.xlsx"], "p4")
        self.assertEqual(ids["publicationNumbers"]["NDA Playbook.xlsx"], 4)
        self.assertEqual(ids["k2ArtifactId"], "a2")
        self.assertEqual(ids["k2PublicationId"], "p4")
        self.assertEqual(ids["c5ReviewId"], "r5")
        self.assertEqual(ids["c5ContractId"], "c5")

    def test_missing_named_ids_are_omitted(self):
        ids = capture_sink.capture_ids({})
        self.assertNotIn("k2ArtifactId", ids)
        self.assertNotIn("c5ReviewId", ids)


class SinkTest(unittest.TestCase):

    def test_each_capture_takes_the_mutations_and_merges_named_ids(self):
        directory = scratch_dir()
        try:
            source = directory / "NDA Playbook.xlsx"
            source.write_bytes(b"playbook")
            api = capture_sink.MutationRecorder(StubApi())
            sink = capture_sink.CaptureSink(directory / "capture", "http://example",
                                            api, source)
            self.assertTrue((directory / "capture" / "files" / "NDA Playbook.xlsx").is_file())
            api.post("/api/thing", {"a": 1})
            with unittest.mock.patch.object(
                    capture, "capture_state",
                    return_value={"stored": 2, "total": 3, "files": {}}) as state_capture:
                with unittest.mock.patch.object(capture, "update_manifest") as update:
                    sink.capture({"reviews": {"contracts/Sablepoint Analytics - Mutual NDA.docx": {
                        "reviewId": "r5", "contractId": "c5"}}}, "base")
            arguments = state_capture.call_args
            self.assertEqual(arguments.args[:3], ("http://example", "base",
                                                  directory / "capture"))
            self.assertEqual(arguments.args[3], [{
                "method": "POST", "path": "/api/thing", "request": {"a": 1},
                "status": 200, "body": {"data": {"ok": True}, "status": 200},
            }])
            self.assertEqual(update.call_args.kwargs["ids"]["c5ReviewId"], "r5")
            self.assertEqual(api.mutations, [])
        finally:
            shutil.rmtree(directory, ignore_errors=True)


if __name__ == "__main__":
    unittest.main()
