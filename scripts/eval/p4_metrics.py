"""Engine v2 P4 metrics for one scored contract. Pure functions, no I/O.

The eval's checks are the diagnostics CheckViews and the findings are the list summaries
with the quality subset run_contract attaches (one detail read per finding). Every function
takes what it needs and returns plain values, so the report and its tests can rely on them.
"""
from scoring import _content_words, _overlap, _topic_matches, section_locator

NEEDS_LOOK = "NEEDS_YOUR_LOOK"
SEARCH = "SEARCH"
SPOTTED = "SPOTTED_IN_WHOLE_CONTRACT_READ"
MISSING_POSITION = "MISSING_POSITION"
MEETS_PREFERRED = "MEETS_PREFERRED"
ADDRESSED = 0.5
TRIAGE_FLAGGED = 0.5

QUALITY_KEYS = ("route", "foundBy", "uncertaintyReason", "spottedSeverity",
                "presenceProbability", "searchedClauses", "comparedClauses",
                "positionLabel", "jevCategory")

LEVER_KEYS = ("issues", "issuesDetected", "falseAlarmSections",
              "seriousFalseAlarmSections", "findings")


def quality(finding):
    return finding.get("quality") or {}


def _addresses(position):
    probabilities = position.get("probabilities") or {}
    return (probabilities.get(MEETS_PREFERRED, 0.0)
            + (position.get("flagProbability") or 0.0)) >= ADDRESSED


def _search_positions(check):
    return [position for position in (check.get("judgedPositions") or [])
            if (position.get("positionKey") or "").startswith("search_")]


def search_metrics(checks):
    """The SEARCH route: how often it ran, what it found and whether its top hit addressed."""
    searched = [check for check in checks if check.get("route") == SEARCH]
    found = sum(check.get("searchFound") or 0 for check in searched)
    addressing = top1_confirmed = top1_checks = addressed_only = 0
    for check in searched:
        positions = _search_positions(check)
        if (check.get("searchFound") or 0) >= 1:
            top1_checks += 1
        addressing += sum(1 for position in positions if _addresses(position))
        if positions and _addresses(positions[0]):
            top1_confirmed += 1
        if check.get("addressed") is True:
            addressing_positions = [position for position in (check.get("judgedPositions") or [])
                                    if _addresses(position)]
            # the judge sets a category only on a flagged position: a meets is an unflagged one
            if addressing_positions and all(
                    (position.get("positionKey") or "").startswith("search_")
                    and position.get("flagged") is not True
                    and position.get("category") in (None, "", MEETS_PREFERRED)
                    for position in addressing_positions):
                addressed_only += 1
    return {"route": len(searched), "found": found, "addressing": addressing,
            "top1Confirmed": top1_confirmed, "top1Checks": top1_checks,
            "addressedOnlyBySearchMeets": addressed_only}


def advisory_metrics(answer, nodes, clauses, findings):
    """Whole-contract-read picks the user is asked to look at, and how many sit on an issue section."""
    section_of, _ = section_locator(answer, nodes, clauses)
    issue_sections = {section for issue in answer.get("expectedIssues", [])
                      for section in issue.get("sectionIds", [])}

    def hits(entries):
        return sum(1 for finding in entries
                   if section_of(finding.get("subjectHeadingNodeId")) in issue_sections)

    picks = [finding for finding in findings
             if finding.get("observationKind") == SPOTTED
             and finding.get("decidedBy") == NEEDS_LOOK]
    high = [finding for finding in picks if quality(finding).get("spottedSeverity") == "HIGH"]
    return {"picks": len(picks), "high": len(high), "hits": hits(picks), "highHits": hits(high)}


def _matches_issue(finding, issue):
    if _topic_matches(finding.get("absenceConcept") or "", issue.get("playbookRef")):
        return True
    words = _content_words(quality(finding).get("positionLabel"))
    return bool(words) and _overlap(words, _content_words(issue.get("playbookRef"))) >= 0.5


def omission_metrics(answer, findings):
    """Omissions the lane raised, and the MEDIUM ones matching no MISSING_PROVISION issue (alarms)."""
    issues = [issue for issue in answer.get("expectedIssues", [])
              if issue.get("category") == "MISSING_PROVISION"]
    listed, medium, low, alarms = [], 0, 0, 0
    for finding in findings:
        if finding.get("observationKind") != MISSING_POSITION:
            continue
        matched = any(_matches_issue(finding, issue) for issue in issues)
        severity = finding.get("severity")
        if severity == "MEDIUM":
            medium += 1
            if not matched:
                alarms += 1
        elif severity == "LOW":
            low += 1
        listed.append({"label": quality(finding).get("positionLabel") or "",
                       "severity": severity,
                       "presence": quality(finding).get("presenceProbability"),
                       "matched": matched})
    return {"medium": medium, "low": low, "alarms": alarms, "list": listed}


def look_reasons(findings):
    """NEEDS_YOUR_LOOK findings by the recorded reason; anything unrecognised is other."""
    reasons = {"second_opinion_disagrees": 0, "second_opinion_unavailable": 0,
               "playbook_silent": 0, "omission_unsure": 0, "other": 0}
    for finding in findings:
        if finding.get("decidedBy") != NEEDS_LOOK:
            continue
        kind = finding.get("observationKind") or ""
        reason = quality(finding).get("uncertaintyReason")
        if kind == "UNCERTAINTY:second_opinion_disagrees":
            reasons["second_opinion_disagrees"] += 1
        elif kind == "UNCERTAINTY:second_opinion_unavailable":
            reasons["second_opinion_unavailable"] += 1
        elif reason == "playbook_silent":
            reasons["playbook_silent"] += 1
        elif reason == "omission_unsure":
            reasons["omission_unsure"] += 1
        else:
            reasons["other"] += 1
    return reasons


def triage_vs_read(answer, nodes, clauses, checks):
    """D12b: over labelled sections, whether the triage flagged and the read picked, as a 2x2.

    A section with no mapped check counts as neither flagged nor picked.
    """
    section_of, _ = section_locator(answer, nodes, clauses)
    by_section = {}
    for check in checks:
        node = check.get("subjectHeadingNodeId")
        if node is None:
            continue
        section = section_of(node)
        if section:
            by_section.setdefault(section, []).append(check)

    def cells(sections):
        table = {"flaggedPicked": 0, "flaggedUnpicked": 0,
                 "clearPicked": 0, "clearUnpicked": 0}
        for section in sections:
            section_checks = by_section.get(section, [])
            flagged = any((check.get("uncoveredProbability") or 0.0) >= TRIAGE_FLAGGED
                          for check in section_checks)
            picked = any(check.get("spottedSeverity") is not None for check in section_checks)
            table[("flagged" if flagged else "clear")
                  + ("Picked" if picked else "Unpicked")] += 1
        return table

    unaddressed = {section for issue in answer.get("expectedIssues", [])
                   if issue.get("category") == "UNADDRESSED_BY_PLAYBOOK"
                   for section in issue.get("sectionIds", [])}
    return {"unaddressed": cells(sorted(unaddressed)),
            "compliant": cells(sorted(set(answer.get("compliantSectionIds", []))))}


def drop_search_found(finding):
    return quality(finding).get("foundBy") == SEARCH


def drop_advisory(finding):
    return (finding.get("observationKind") == SPOTTED
            and finding.get("decidedBy") == NEEDS_LOOK)


def drop_omissions(finding):
    return finding.get("observationKind") == MISSING_POSITION


def lever_score(score_contract, answer, nodes, clauses, findings, drop):
    """One 'without this lever' re-score, the same shape as the report's withoutSpotted."""
    scored = score_contract(answer, nodes, clauses,
                            [finding for finding in findings if not drop(finding)])
    return {key: scored[key] for key in LEVER_KEYS}
