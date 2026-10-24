"""Shared helpers for the release-4 fidelity scorers (K, A, T). Standard library only.

Paths, text matching, metric shapes and the summary/results writer. A scorer adds only its own
measurement; nothing here reads a model or a backend.
"""
import datetime
import json
import os
import pathlib
import re
import sys

REPO = pathlib.Path(__file__).resolve().parents[2]
DEFAULT_CONTEXT = pathlib.Path("/Users/shridhar600/Dev/Projects/Rivell-context")
WORD = re.compile(r"[a-z0-9]+")
STOP = {"the", "a", "an", "of", "and", "or", "to", "in", "for", "on", "by", "with", "any",
        "be", "is", "will", "shall", "may", "its", "this", "that", "as", "at", "not"}


def context_root(value=None):
    return pathlib.Path(value or os.environ.get("RIVELL_CONTEXT") or DEFAULT_CONTEXT)


def fidelity_set(set_name, context=None):
    """`$CTX/eval/fidelity/<set>`; fails loudly when the set does not exist."""
    path = context_root(context) / "eval" / "fidelity" / set_name
    if not path.is_dir():
        sys.exit(f"no fidelity set {set_name!r} under {path.parent}")
    return path


def load_json(path):
    return json.loads(pathlib.Path(path).read_text())


def pct(n, d):
    return f"{n}/{d} ({100 * n // d}%)" if d else "n/a"


def ratio(n, d):
    return n / d if d else None


def metric(n, d):
    return {"n": n, "d": d, "value": ratio(n, d)}


def content_words(text):
    """Whole content words: no stemming, no substring matching."""
    return {w for w in WORD.findall((text or "").lower()) if w not in STOP and len(w) > 2}


def normalise_phrase(text):
    """Lower-case whole words joined by one space; punctuation and case never decide a match."""
    return " ".join(WORD.findall((text or "").lower()))


def phrase_in(phrase, text):
    """Whether a short verbatim phrase occurs in a text as whole words."""
    wanted = normalise_phrase(phrase)
    return bool(wanted) and f" {wanted} " in f" {normalise_phrase(text)} "


def generated():
    return datetime.datetime.now(datetime.timezone.utc).replace(microsecond=0).isoformat()


def write_outputs(out, results, meta, lines):
    """Write `results.json` and `summary.md` in the style of run_eval.py; return the report path."""
    out = pathlib.Path(out)
    out.mkdir(parents=True, exist_ok=True)
    (out / "results.json").write_text(
        json.dumps({"meta": meta, "results": results}, indent=1) + "\n")
    report = out / "summary.md"
    report.write_text("\n".join(lines) + "\n")
    return report


def table(header, rows):
    return (["| " + " | ".join(header) + " |", "|" + "---|" * len(header)]
            + ["| " + " | ".join(str(cell) for cell in row) + " |" for row in rows])
