"""Unit tests for the eval's fail-fast guard and list prices. Run: python3 -m unittest scripts/eval/test_provider_guard.py"""
import http.server
import json
import os
import tempfile
import threading
import unittest
from unittest import mock

import prices
import provider_guard
from provider_guard import LogWatch, ProviderFatal


def event(outcome, reason=None, provider="jev"):
    return json.dumps({"message": "model_execution", "kind": "decision", "provider": provider,
                       "outcome": outcome, "error": "ProviderRejectedException", "reason": reason}) + "\n"


class LogWatchTest(unittest.TestCase):

    def setUp(self):
        self.log = tempfile.NamedTemporaryFile("w", suffix=".log", delete=False)
        self.addCleanup(os.unlink, self.log.name)

    def write(self, text):
        self.log.write(text)
        self.log.flush()

    def test_an_account_with_no_credits_aborts_at_the_first_failed_call(self):
        watch = LogWatch(self.log.name)
        self.write("Started backend\n" + event("COMPLETED"))
        watch.check()
        self.write(event("FAILED", "Provider account has no credits (HTTP 402)"))
        with self.assertRaisesRegex(ProviderFatal, "jev account has no credits"):
            watch.check()

    def test_a_provider_that_keeps_failing_aborts_but_a_success_resets_the_count(self):
        watch = LogWatch(self.log.name)
        self.write(event("FAILED", "Provider unavailable (HTTP 503)") * 24 + event("COMPLETED")
                   + event("RETRIED") + event("FAILED", "Provider unavailable (HTTP 503)") * 24)
        watch.check()
        self.write(event("FAILED", "Provider unavailable (HTTP 503)"))
        with self.assertRaisesRegex(ProviderFatal, "25 model calls failed in a row"):
            watch.check()

    def test_a_half_written_line_is_read_once_it_is_complete(self):
        watch = LogWatch(self.log.name)
        line = event("FAILED", "Provider account has no credits (HTTP 429 insufficient_quota)", "openai-compatible")
        self.write(line[:40])
        watch.check()
        self.write(line[40:])
        with self.assertRaisesRegex(ProviderFatal, "openai-compatible account has no credits"):
            watch.check()


class FakeProvider(http.server.BaseHTTPRequestHandler):
    status, body = 200, b"{}"

    def do_POST(self):
        self.rfile.read(int(self.headers["Content-Length"]))
        self.send_response(self.status)
        self.end_headers()
        self.wfile.write(self.body)

    def log_message(self, *args):
        pass


class PreflightTest(unittest.TestCase):

    def serve(self, status, body):
        handler = type("Handler", (FakeProvider,), {"status": status, "body": body})
        server = http.server.HTTPServer(("127.0.0.1", 0), handler)
        threading.Thread(target=server.serve_forever, daemon=True).start()
        self.addCleanup(server.shutdown)
        return f"http://127.0.0.1:{server.server_port}"

    def test_names_each_provider_that_cannot_serve_and_never_its_response_text(self):
        jev = self.serve(402, b'{"detail":{"error_type":"billing_error","message":"secret detail"}}')
        openai = self.serve(429, b'{"error":{"code":"insufficient_quota","message":"secret detail"}}')
        embeddings = self.serve(200, b"{}")
        env = {"TYPESAFE_BASE_URL": jev, "TYPESAFE_API_KEY": "k", "OPENAI_BASE_URL": openai,
               "OPENAI_API_KEY": "k", "RIVELL_EMBEDDINGS_BASE_URL": embeddings}
        with mock.patch.dict(os.environ, env):
            problems = provider_guard.preflight(jev=True, openai_model="gpt-6-luna")
        self.assertEqual(problems, ["TypeSafe Jev: account has no credits (HTTP 402)",
                                    "OpenAI gpt-6-luna: account has no credits (HTTP 429)"])

    def test_a_rate_limit_is_not_mistaken_for_no_credits(self):
        openai = self.serve(429, b'{"error":{"code":"rate_limit_exceeded"}}')
        with mock.patch.dict(os.environ, {"OPENAI_BASE_URL": openai, "OPENAI_API_KEY": "k"}):
            self.assertEqual(provider_guard.check_openai("gpt-6-luna"), "HTTP 429")


class PricesTest(unittest.TestCase):

    def test_dated_model_ids_take_their_family_price_and_jev_output_is_free(self):
        self.assertAlmostEqual(prices.cost("gpt-6-luna-2026-08-01", 1_000_000, 1_000_000), 0.60)
        self.assertAlmostEqual(prices.cost("gpt-6.1-sol", 1_000_000, 0), 2.0)
        self.assertAlmostEqual(prices.cost("jev-1.13.0", 4_000_000, 900_000), 0.168)
        self.assertIsNone(prices.cost("unknown-model", 10, 10))


if __name__ == "__main__":
    unittest.main()
