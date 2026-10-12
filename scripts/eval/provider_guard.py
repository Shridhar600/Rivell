"""Fail fast when a model provider cannot serve an eval run.

preflight(): one tiny call per provider before the run starts, so an account with no credits or
a dead key stops the run in seconds, not after twenty minutes of failed reviews.
LogWatch: reads the backend's model_execution log while the run goes on and raises
ProviderFatal when an account runs out of credits or a provider keeps failing.

Never prints keys or provider response bodies; only HTTP status and a code-written reason.
"""
import json
import os
import urllib.error
import urllib.request

NO_CREDITS = "has no credits"
CONSECUTIVE_FAILURES = 25


class ProviderFatal(Exception):
    pass


def _post(url, body, key, timeout=60):
    request = urllib.request.Request(url, data=json.dumps(body).encode(), method="POST", headers={
        "Authorization": "Bearer " + key, "Content-Type": "application/json"})
    try:
        with urllib.request.urlopen(request, timeout=timeout) as response:
            response.read()
            return None
    except urllib.error.HTTPError as e:
        text = e.read().decode(errors="replace")
        if e.code == 402 or "insufficient_quota" in text:
            return f"account {NO_CREDITS} (HTTP {e.code})"
        if e.code in (401, 403):
            return f"key rejected (HTTP {e.code})"
        return f"HTTP {e.code}"
    except (urllib.error.URLError, TimeoutError, ConnectionError) as e:
        return f"unreachable ({type(e).__name__})"


def check_jev():
    return _post(os.environ.get("TYPESAFE_BASE_URL", "https://api.typesafe.ai") + "/v1/systemone",
                 {"model": os.environ.get("TYPESAFE_MODEL", "jev-latest"),
                  "state": {"clause": "Either party may terminate on 30 days' notice."},
                  "questions": {"q": {"type": "noul", "instructions": "Can a party terminate on notice?",
                                      "criteria": {"true": "It can.", "false": "It cannot."}}}},
                 os.environ.get("TYPESAFE_API_KEY", ""))


def check_openai(model):
    return _post(os.environ.get("OPENAI_BASE_URL", "https://api.openai.com/v1") + "/chat/completions",
                 {"model": model, "messages": [{"role": "user", "content": "Reply with ok."}],
                  "max_completion_tokens": 16},
                 os.environ.get("OPENAI_API_KEY", ""))


def check_embeddings():
    return _post(os.environ.get("RIVELL_EMBEDDINGS_BASE_URL", "https://api.openai.com/v1") + "/embeddings",
                 {"model": os.environ.get("RIVELL_EMBEDDINGS_MODEL", "text-embedding-3-small"),
                  "input": ["preflight"]},
                 os.environ.get("RIVELL_EMBEDDINGS_API_KEY") or os.environ.get("OPENAI_API_KEY", ""))


def preflight(jev=True, openai_model=None, embeddings=True):
    """Problems as 'provider: reason' lines; empty when every needed provider answers."""
    checks = []
    if jev:
        checks.append(("TypeSafe Jev", check_jev))
    if openai_model:
        checks.append((f"OpenAI {openai_model}", lambda: check_openai(openai_model)))
    if embeddings:
        checks.append(("embeddings", check_embeddings))
    return [f"{name}: {problem}" for name, fn in checks if (problem := fn())]


class LogWatch:
    """Incremental reader of one backend log; check() raises ProviderFatal on a fatal pattern."""

    def __init__(self, path):
        self.path = path
        self.offset = 0
        self.streak = 0

    def check(self):
        try:
            with open(self.path, "rb") as f:
                f.seek(self.offset)
                chunk = f.read()
        except FileNotFoundError:
            return
        complete = chunk[:chunk.rfind(b"\n") + 1]
        self.offset += len(complete)
        for line in complete.decode(errors="replace").splitlines():
            if "model_execution" not in line:
                continue
            try:
                event = json.loads(line)
            except ValueError:
                continue
            if event.get("message") != "model_execution":
                continue
            self.observe(event)

    def observe(self, event):
        outcome = event.get("outcome")
        if outcome == "COMPLETED":
            self.streak = 0
        elif outcome == "FAILED":
            provider = event.get("provider") or "provider"
            if NO_CREDITS in (event.get("reason") or ""):
                raise ProviderFatal(f"{provider} account {NO_CREDITS}; run aborted")
            self.streak += 1
            if self.streak >= CONSECUTIVE_FAILURES:
                raise ProviderFatal(f"{self.streak} model calls failed in a row "
                                    f"({provider}: {event.get('error')}); run aborted")
