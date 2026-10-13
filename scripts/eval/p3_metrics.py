"""Engine v2 P3 metrics for one scored contract. Pure functions, no I/O.

definition_metrics reads the semantics body's definedTerms and the diagnostics CheckViews the
run scored (a check carries definitionTerms when the judge read definitions for that clause).
reference_metrics reads the semantics body's references. Every function takes what it needs
and returns plain values, so the report and its tests can rely on them.
"""

REFERENCE_STATUSES = ("RESOLVED", "BROKEN", "AMBIGUOUS", "EXTERNAL")


def definition_metrics(semantics, checks):
    """Definitions extracted, judged clauses shown them, and how many definitions were shown."""
    checks = checks or []
    judged = [check for check in checks if check.get("judgedPositions")]
    return {
        "terms": len((semantics or {}).get("definedTerms") or []),
        "judgedClauses": len(judged),
        "clausesShown": sum(1 for check in judged if check.get("definitionTerms")),
        "termsShown": sum(len(check.get("definitionTerms") or []) for check in checks),
    }


def reference_metrics(semantics):
    """References by resolution status; a reference with any other status is ignored."""
    counts = {status.lower(): 0 for status in REFERENCE_STATUSES}
    for reference in (semantics or {}).get("references") or []:
        status = reference.get("status")
        if status in REFERENCE_STATUSES:
            counts[status.lower()] += 1
    return counts
