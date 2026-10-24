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
    # a list number before a schedule label ("26. Schedule 1 - Fees"): strip both
    if re.match(r"\s*(?:schedule|exhibit|annex|appendix)\b", stripped):
        again = _NUMBERING.sub("", stripped, count=1)
        stripped = again if again.strip() else stripped
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


def _overlap(want, have):
    return len(want & have) / len(want) if want else 1.0


def score_knowledge(answer, candidates):
    """A position counts as found only when ONE candidate carries both its preferred text and its
    walk-away (when the playbook has one): a position split across rows is not usable for review."""
    concepts = [c for c in candidates if c.get("effectiveConceptKey")]
    positions = answer.get("positions", []) if answer else []
    texts = {c["id"]: _content_words(c.get("contentText")) for c in candidates}
    matched_candidates, found, partial = set(), 0, 0
    for pos in positions:
        preferred = _content_words(pos.get("preferred"))
        walk_away = _content_words(pos.get("walkAway"))
        if not preferred:
            continue
        best, best_score, any_part = None, 0.0, False
        for cid, have in texts.items():
            p, w = _overlap(preferred, have), _overlap(walk_away, have)
            any_part = any_part or p >= 0.5 or (bool(walk_away) and w >= 0.5)
            score = min(p, w)
            if score > best_score:
                best, best_score = cid, score
        if best_score >= 0.5:
            found += 1
            matched_candidates.add(best)
        elif any_part:
            partial += 1
    return {
        "expectedPositions": len(positions),
        "candidates": len(candidates),
        "candidatesWithConcept": len(concepts),
        "positionsFound": found,
        "positionsSplit": partial,
        "unmatchedCandidates": len(candidates) - len(matched_candidates) if positions else None,
        "conceptsUsed": sorted({c["effectiveConceptKey"] for c in concepts}),
    }


def _section_locator(structure_nodes, section_to_node):
    """Maps a heading node to the answer-key section that contains it: a sub-clause heading
    (5.2) belongs to the last matched section heading (5) before it in document order."""
    order = {n["id"]: i for i, n in enumerate(structure_nodes)}
    starts = sorted((order[node], sid) for sid, node in section_to_node.items() if node in order)
    exact = {node: sid for sid, node in section_to_node.items()}

    def section_of(node_id):
        if node_id in exact:
            return exact[node_id]
        index = order.get(node_id)
        if index is None:
            return None
        owner = None
        for start, sid in starts:
            if start > index:
                break
            owner = sid
        return owner
    return section_of


def _stems(text):
    return {w[:5] for w in _WORD.findall((text or "").lower().replace("_", " "))
            if w not in _STOP and len(w) > 3}


def _topic_matches(concept_key, playbook_ref):
    ref = re.sub(r"^\s*[A-Z]+\d+\s*", "", playbook_ref or "")
    return bool(_stems(concept_key) & _stems(ref))


# ---------- DW-069: absence credit by position label or reference, whole words only ----------
# Stem overlap credited the wrong provision ("regulat" is not a rule) and missed the right one
# when the extracted concept was odd. A position code ("G-03") or the position's own words decide
# the credit; words must match whole, never by stem.
ABSENCE_CREDIT = "label/reference whole-word (DW-069)"

_CODE = re.compile(r"\b([A-Za-z]{1,3})-(\d{1,3})\b")


def position_code(text):
    """The position code a label or playbook reference carries ("G-03"), canonicalised."""
    match = _CODE.search(text or "")
    return f"{match.group(1).upper()}-{int(match.group(2))}" if match else None


def _match_words(text):
    """Content words for a whole-word comparison: punctuation and underscores separate words."""
    cleaned = re.sub(r"[_/]", " ", text or "")
    return {w for w in _WORD.findall(cleaned.lower()) if w not in _STOP and len(w) > 2}


def _carried_label(finding):
    quality = finding.get("quality") or {}
    return finding.get("positionLabel") or quality.get("positionLabel") or ""


def absence_findings(findings):
    """The absence findings of a contract (v1 and v2 carry subjectKind ABSENCE)."""
    return [finding for finding in findings if finding.get("subjectKind") == "ABSENCE"]


def absence_matches(finding, issue):
    """Whether an absence finding is the issue's missing provision (DW-069).

    A finding's position label or reference is authoritative when it carries a position code: the
    codes must match. Without a carried code, every content word of the issue's playbook reference
    must appear in the finding's label, carried reference or absence concept as a whole word.
    """
    reference = issue.get("playbookRef") or ""
    label = _carried_label(finding)
    carried = finding.get("positionReference") or finding.get("playbookRef") or ""
    reference_code = position_code(reference)
    carried_codes = [code for code in (position_code(label), position_code(carried)) if code]
    if reference_code and carried_codes:
        return reference_code in carried_codes
    want = _match_words(reference)
    if not want:
        return False
    return any(want <= _match_words(text)
               for text in (label, carried, finding.get("absenceConcept")))



def _detected_headings(structure_nodes, clauses):
    detected = [(n["id"], n.get("text") or "") for n in structure_nodes
                if n.get("nodeType") == "HEADING"]
    for clause in clauses:  # clause headings cover run-in headings the parser kept inline
        if clause.get("headingNodeId") and clause.get("heading"):
            if all(clause["headingNodeId"] != node_id for node_id, _ in detected):
                detected.append((clause["headingNodeId"], clause["heading"]))
    return detected


def section_locator(answer, structure_nodes, clauses):
    """(section_of, section_to_node): section_of(node_id) is the answer-key section that holds
    a heading node, or None off the key."""
    section_to_node = match_sections(answer.get("sections", []),
                                     _detected_headings(structure_nodes, clauses))
    return _section_locator(structure_nodes, section_to_node), section_to_node


def score_contract(answer, structure_nodes, clauses, findings):
    detected = _detected_headings(structure_nodes, clauses)
    expected = answer.get("sections", [])
    section_of, section_to_node = section_locator(answer, structure_nodes, clauses)
    node_to_section = {v: k for k, v in section_to_node.items()}

    classified = [c for c in clauses if c.get("correctedPrimaryConcept") or c.get("primaryConcept")]
    absence = absence_findings(findings)
    located = [f for f in findings if f.get("subjectHeadingNodeId")]
    finding_sections = {}
    for f in located:
        sid = section_of(f["subjectHeadingNodeId"])
        if sid:
            finding_sections.setdefault(sid, []).append(f)

    expected_issues = answer.get("expectedIssues", [])
    # one absence finding explains one missing provision: claim it for the issue whose reference
    # it matches most specifically, so two similar provisions cannot both take the same finding
    absence_claim, absence_hit = {}, set()
    for index, issue in enumerate(expected_issues):
        if issue["category"] != "MISSING_PROVISION":
            continue
        for finding in absence:
            if not absence_matches(finding, issue):
                continue
            key = finding.get("id") or id(finding)
            specificity = len(_match_words(issue.get("playbookRef") or ""))
            if key not in absence_claim or specificity > absence_claim[key][0]:
                absence_claim[key] = (specificity, index)
    for _, index in absence_claim.values():
        absence_hit.add(index)

    issues = []
    for index, issue in enumerate(expected_issues):
        hit = any(sid in finding_sections for sid in issue.get("sectionIds", []))
        if issue["category"] == "MISSING_PROVISION" and not hit:
            # DW-069: an absence finding counts only when its position label or reference names
            # the provision the key names (whole words, never stems); each finding counts once
            hit = index in absence_hit
        # stricter: a HIGH/MEDIUM planted issue caught by a MEDIUM/HIGH finding in its section
        serious = any(f.get("severity") in ("MEDIUM", "HIGH")
                      for sid in issue.get("sectionIds", []) for f in finding_sections.get(sid, []))
        issues.append({"id": issue["id"], "category": issue["category"],
                       "severity": issue.get("severity"), "detected": hit, "detectedSerious": serious})

    compliant = answer.get("compliantSectionIds", [])
    false_alarms = [sid for sid in compliant if sid in finding_sections]
    serious_alarms = [sid for sid in false_alarms
                      if any(f.get("severity") in ("MEDIUM", "HIGH") for f in finding_sections[sid])]
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
        "seriousFalseAlarmSections": len(serious_alarms),
    }
