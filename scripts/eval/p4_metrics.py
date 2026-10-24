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
WALK_AWAY = "WALK_AWAY"
BEYOND_FALLBACK = "BEYOND_FALLBACK"
ADDRESSED = 0.5
TRIAGE_FLAGGED = 0.5

QUALITY_KEYS = ("route", "foundBy", "uncertaintyReason", "spottedSeverity",
                "presenceProbability", "searchedClauses", "comparedClauses",
                "positionLabel", "jevCategory", "secondOpinionCategory", "levers",
                "severityWithoutLevers", "secondOpinionQuote", "secondOpinionReason",
                "categoryBeforeLevers", "wordingFitVerdict", "wordingFitReason",
                "wordingFitReasons", "wordingFitQuote", "fitVerdict", "fitReason", "fitReasons")

FIT_VERDICTS = ("FITS", "DOES_NOT_FIT", "UNKNOWN")

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


def fit_metrics(checks):
    """Part 4: Rivell's fit verdicts, per search hit and per wording offer, and what it cost.

    Reads what the run recorded: each judged position's {fitVerdict, fitOutcome,
    wordingFitVerdict} in the check's judged positions, and the fit call/skip counts the check
    keeps. A wording verdict is per judged position, so two offers in one consolidated finding
    stay countable. The fit scorer compares these verdicts with its own labels; a run from
    before the fit check reports zeroes, never missing keys.
    """
    search = {verdict: 0 for verdict in FIT_VERDICTS}
    wording = {verdict: 0 for verdict in FIT_VERDICTS}
    not_covered = calls = questions = skipped = 0
    for check in checks:
        if check.get("fitQuestions") is not None:
            calls += 1
        questions += check.get("fitQuestions") or 0
        skipped += check.get("fitSkipped") or 0
        for position in check.get("judgedPositions") or []:
            searched = (position.get("positionKey") or "").startswith("search_")
            if searched:
                verdict = position.get("fitVerdict")
                if verdict in search:
                    search[verdict] += 1
                if position.get("fitOutcome") == "not_covered":
                    not_covered += 1
            verdict = position.get("wordingFitVerdict")
            if verdict in wording:
                wording[verdict] += 1
    return {"search": search, "wording": wording, "notCovered": not_covered,
            "calls": calls, "questions": questions, "skipped": skipped}


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


def severity_raised(finding):
    """R1: a "needs your look" item the second opinion called a walk-away or beyond-fallback."""
    return (finding.get("severity") == "MEDIUM"
            and quality(finding).get("route") == NEEDS_LOOK
            and quality(finding).get("secondOpinionCategory") in (WALK_AWAY, BEYOND_FALLBACK))


def serious_caught(issues):
    """HIGH/MEDIUM planted issues caught by a MEDIUM+ finding (the totals line's strict count)."""
    return sum(1 for issue in issues
               if issue.get("severity") in ("HIGH", "MEDIUM") and issue.get("detectedSerious"))


def severity_raise_score(score_contract, answer, nodes, clauses, findings):
    """R1: the same run re-scored with the raised items at LOW; the report line's without side."""
    without = score_contract(answer, nodes, clauses,
                             [dict(finding, severity="LOW") if severity_raised(finding) else finding
                              for finding in findings])
    return {"raised": sum(1 for finding in findings if severity_raised(finding)),
            "seriousCaught": serious_caught(without["issues"]),
            "seriousFalseAlarmSections": without["seriousFalseAlarmSections"]}


SEVERITIES = ("LOW", "MEDIUM", "HIGH")
R3_LEVERS = ("P1", "P2", "P3")


def _tagged(finding, levers):
    return any(lever in (quality(finding).get("levers") or []) for lever in levers)


def lever_changed(finding, levers):
    """Severity R3: the finding carries one of the levers and its severity moved because of them."""
    restored = quality(finding).get("severityWithoutLevers")
    return (_tagged(finding, levers) and restored in SEVERITIES
            and restored != finding.get("severity"))


def lever_tagged(findings, levers):
    """Findings the given levers touched, whether or not their severity moved."""
    return sum(1 for finding in findings if _tagged(finding, levers))


def lever_severity_score(score_contract, answer, nodes, clauses, findings, levers):
    """The same run re-scored with the findings the given levers changed back at their unchanged
    severity (P1: agreed worse category, P2: within-split routing, P3: fallback conditions)."""
    without = score_contract(answer, nodes, clauses,
                             [dict(finding, severity=quality(finding)["severityWithoutLevers"])
                              if lever_changed(finding, levers) else finding
                              for finding in findings])
    return {"tagged": lever_tagged(findings, levers),
            "raised": sum(1 for finding in findings if lever_changed(finding, levers)),
            "seriousCaught": serious_caught(without["issues"]),
            "seriousFalseAlarmSections": without["seriousFalseAlarmSections"]}
