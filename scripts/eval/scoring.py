"""Scores one pipeline run against a corpus answer key. Pure functions, no I/O."""
import re

_NUMBERING = re.compile(
    r"^\s*(?:article|section|clause|schedule|exhibit|annex|appendix)?\s*"
    r"(?:[0-9]+(?:\.[0-9]+)*|[ivxlc]+|[a-z])?[.):\-–]*\s+", re.IGNORECASE)
_WORD = re.compile(r"[a-z0-9]+")
_STOP = {"the", "a", "an", "of", "and", "or", "to", "in", "for", "on", "by", "with", "any",
         "be", "is", "will", "shall", "may", "its", "this", "that", "as", "at", "not"}


def tokens(text):
    text = (text or "").lower()
    stripped = _NUMBERING.sub("", text, count=1)
    return _WORD.findall(stripped if stripped.strip() else text)


def _heading_match(expected, detected):
    exp, det = tokens(expected), tokens(detected)
    if not exp or not det:
        return False
    return det[:len(exp)] == exp or exp[:len(det)] == det


def match_sections(expected_sections, detected_headings):
    """Greedy in-order match. detected_headings: [(node_id, text)]. Returns {section_id: node_id}."""
    matched, cursor = {}, 0
    for section in expected_sections:
        for i in range(cursor, len(detected_headings)):
            node_id, text = detected_headings[i]
            if _heading_match(section["heading"], text):
                matched[section["id"]] = node_id
                cursor = i + 1
                break
    return matched


def _content_words(text):
    return {w for w in _WORD.findall((text or "").lower()) if w not in _STOP and len(w) > 2}


def score_knowledge(answer, candidates):
    concepts = [c for c in candidates if c.get("effectiveConceptKey")]
    positions = answer.get("positions", []) if answer else []
    matched_candidates, found = set(), 0
    for pos in positions:
        want = _content_words(" ".join(filter(None, [pos.get("preferred"), pos.get("walkAway")])))
        if not want:
            continue
        best, best_overlap = None, 0.0
        for c in candidates:
            have = _content_words(c.get("contentText"))
            overlap = len(want & have) / len(want)
            if overlap > best_overlap:
                best, best_overlap = c["id"], overlap
        if best_overlap >= 0.5:
            found += 1
            matched_candidates.add(best)
    return {
        "expectedPositions": len(positions),
        "candidates": len(candidates),
        "candidatesWithConcept": len(concepts),
        "positionsFound": found,
        "unmatchedCandidates": len(candidates) - len(matched_candidates) if positions else None,
        "conceptsUsed": sorted({c["effectiveConceptKey"] for c in concepts}),
    }


def score_contract(answer, structure_nodes, clauses, findings):
    detected = [(n["id"], n.get("text") or "") for n in structure_nodes
                if n.get("nodeType") == "HEADING"]
    for clause in clauses:  # clause headings cover run-in headings the parser kept inline
        if clause.get("headingNodeId") and clause.get("heading"):
            if all(clause["headingNodeId"] != node_id for node_id, _ in detected):
                detected.append((clause["headingNodeId"], clause["heading"]))
    expected = answer.get("sections", [])
    section_to_node = match_sections(expected, detected)
    node_to_section = {v: k for k, v in section_to_node.items()}

    classified = [c for c in clauses if c.get("correctedPrimaryConcept") or c.get("primaryConcept")]
    absence = [f for f in findings if f.get("subjectKind") == "ABSENCE"]
    located = [f for f in findings if f.get("subjectHeadingNodeId")]
    finding_sections = {}
    for f in located:
        sid = node_to_section.get(f["subjectHeadingNodeId"])
        if sid:
            finding_sections.setdefault(sid, []).append(f)

    issues = []
    for issue in answer.get("expectedIssues", []):
        if issue["category"] == "MISSING_PROVISION":
            hit = bool(absence)
        else:
            hit = any(sid in finding_sections for sid in issue.get("sectionIds", []))
        issues.append({"id": issue["id"], "category": issue["category"],
                       "severity": issue.get("severity"), "detected": hit})

    compliant = answer.get("compliantSectionIds", [])
    false_alarms = [sid for sid in compliant if sid in finding_sections]
    return {
        "sectionsExpected": len(expected),
        "headingsDetected": len(detected),
        "sectionsMatched": len(section_to_node),
        "clauses": len(clauses),
        "clausesClassified": len(classified),
        "findings": len(findings),
        "absenceFindings": len(absence),
        "findingsOffAnswerKey": len(located) - sum(len(v) for v in finding_sections.values()),
        "issues": issues,
        "issuesDetected": sum(1 for i in issues if i["detected"]),
        "compliantSections": len(compliant),
        "falseAlarmSections": len(false_alarms),
    }
