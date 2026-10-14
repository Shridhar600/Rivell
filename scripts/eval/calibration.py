"""Engine v2 P0 calibration (spec §6, §11): how sure was the judge when Rivell missed or alarmed?

Joins the policy-comparison probabilities a run saves (diagnostics `judgedPositions` in
raw/<contract>.json) with the corpus answer key. One record per:
  issue      planted issue: detected or not, its sections
  compliant  compliant section: alarmed or not
  judged     judged position, or a comparison check that judged none (positionKey None)
Miss buckets: CONFIDENT_MISS / DOUBTFUL_MISS (a position judged the clause), OFF_TOPIC (positions
matched but none covers it) and NOT_JUDGED (none matched): the triage decided those two,
ABSENCE (the key names no section: a provision missing from the contract), NO_CHECK,
CHECK_FAILED.
Routing thresholds come from dev/dev2 only: a holdout is refused.

Usage (one corpus per build; report takes several files):
  python3 calibration.py build --corpus <corpus dir> --run <run dir> [--run ...] --out rows.jsonl
  python3 calibration.py report rows.jsonl [more.jsonl ...]
"""
import argparse
import json
import pathlib
import sys
from collections import Counter

from scoring import score_contract, section_locator

FLAGGING = ("WALK_AWAY", "BEYOND_FALLBACK", "UNCLEAR", "WITHIN_FALLBACK")
POLICY_CHECK = "CLAUSE:POLICY_COMPARISON:"
FAILED = ("FAILED", "DEGRADED")
CONFIDENT_P, CONFIDENT_MARGIN = 0.8, 0.2  # spec §6 initial ACCEPT band
ADDRESSED = 0.5  # PolicyComparisonCheckExecutor.ADDRESSED: below it no position covers the clause
TRIAGE_BUCKETS = ("NOT_JUDGED", "OFF_TOPIC")  # the uncovered-clause triage decided these
SWEEP = (0.3, 0.4, 0.5, 0.6, 0.7, 0.8)    # product flag threshold today: 0.6


def shape(probabilities):
    """Top option, its probability, the top-2 margin and the summed flagging probability."""
    ranked = sorted(probabilities.values(), reverse=True) + [0.0, 0.0]
    top = max(probabilities, key=probabilities.get) if probabilities else None
    return {"top": top, "pMax": ranked[0], "margin": ranked[0] - ranked[1],
            "flagMass": sum(probabilities.get(o, 0.0) for o in FLAGGING)}


def confident(row):
    return row["pMax"] >= CONFIDENT_P and row["margin"] >= CONFIDENT_MARGIN


def addresses(row):
    """The product's rule: a position covers the clause when 'meets' plus the flagging mass reach 0.5."""
    return row["probabilities"].get("MEETS_PREFERRED", 0.0) + row["flagMass"] >= ADDRESSED


def build_records(run, contract, answer, raw):
    clauses = raw["semantics"].get("clauses", [])
    section_of, _ = section_locator(answer, raw["nodes"], clauses)
    score = score_contract(answer, raw["nodes"], clauses, raw["findings"])
    common = {"run": run, "contract": contract}
    records = []
    for key, scored in zip(answer.get("expectedIssues", []), score["issues"]):
        records.append(dict(common, kind="issue", issue=scored["id"], category=scored["category"],
                            severity=scored["severity"], detected=scored["detected"],
                            detectedSerious=scored["detectedSerious"], sections=key.get("sectionIds", [])))
    alarmed, serious = set(), set()
    for finding in raw["findings"]:
        sid = section_of(finding.get("subjectHeadingNodeId"))
        if sid:
            alarmed.add(sid)
            if finding.get("severity") in ("MEDIUM", "HIGH"):
                serious.add(sid)
    for sid in answer.get("compliantSectionIds", []):
        records.append(dict(common, kind="compliant", section=sid, alarm=sid in alarmed,
                            seriousAlarm=sid in serious))
    for check in raw.get("checks", []):
        if not check["key"].startswith(POLICY_CHECK):
            continue
        base = dict(common, kind="judged", section=section_of(check.get("subjectHeadingNodeId")),
                    checkStatus=check["status"], probe=check.get("probe"),
                    uncoveredProbability=check.get("uncoveredProbability"), positionKey=None)
        positions = check.get("judgedPositions") or []
        if not positions:
            records.append(base)
        for position in positions:
            probabilities = position.get("probabilities") or {}
            row = dict(base, positionKey=position["positionKey"],
                       positionCandidateId=position.get("positionCandidateId"),
                       category=position.get("category"), flagged=bool(position.get("flagged")),
                       route=position.get("route"), secondOpinionOutcome=position.get("secondOpinionOutcome"),
                       secondOpinionCategory=position.get("secondOpinionCategory"),
                       probabilities=probabilities, **shape(probabilities))
            if position.get("probeProbabilities"):
                probe = shape(position["probeProbabilities"])
                row.update(probeProbabilities=position["probeProbabilities"], probeTop=probe["top"],
                           probeFlagMass=probe["flagMass"], probeFlagged=bool(position.get("probeFlagged")))
            records.append(row)
    return records


def build(corpus, runs):
    if "holdout" in str(corpus).lower():
        sys.exit(f"refusing {corpus}: routing thresholds are never fitted on a holdout")
    records = []
    for run_dir in runs:
        raws = [p for p in sorted((run_dir / "raw").glob("*.json"))
                if (corpus / "answers" / p.name).exists()]
        loaded = [(p, json.loads(p.read_text())) for p in raws]
        if not any("judgedPositions" in c for _, r in loaded for c in r.get("checks", [])):
            sys.exit(f"{run_dir} has no judgedPositions: it ran before P0 (diagnostics change)")
        for path, raw in loaded:
            answer = json.loads((corpus / "answers" / path.name).read_text())
            records += build_records(run_dir.name, path.stem, answer, raw)
    return records


def _judged_by_section(records):
    index = {}
    for r in records:
        if r["kind"] == "judged":
            index.setdefault((r["run"], r["contract"], r["section"]), []).append(r)
    return index


def _rows_for(issue, judged):
    return [r for sid in issue["sections"] for r in judged.get((issue["run"], issue["contract"], sid), [])]


def miss_bucket(issue, judged):
    if not issue["sections"]:
        return "ABSENCE"
    rows = _rows_for(issue, judged)
    if not rows:
        return "NO_CHECK"
    if any(r["checkStatus"] in FAILED for r in rows):
        return "CHECK_FAILED"
    positions = [r for r in rows if r["positionKey"]]
    if not positions:
        return "NOT_JUDGED"
    if not any(addresses(r) for r in positions):
        return "OFF_TOPIC"
    best = max(positions, key=lambda r: r["flagMass"])
    return "CONFIDENT_MISS" if confident(best) else "DOUBTFUL_MISS"


def miss_buckets(records):
    """{bucket: {category: count}} over planted issues no finding caught."""
    judged = _judged_by_section(records)
    buckets = {}
    for issue in (r for r in records if r["kind"] == "issue" and not r["detected"]):
        cats = buckets.setdefault(miss_bucket(issue, judged), {})
        cats[issue["category"]] = cats.get(issue["category"], 0) + 1
    return buckets


def triage_misses(records):
    """Highest triage probability seen for each miss the uncovered-clause triage decided."""
    judged = _judged_by_section(records)
    found = []
    for issue in (r for r in records if r["kind"] == "issue" and not r["detected"]):
        if miss_bucket(issue, judged) in TRIAGE_BUCKETS:
            seen = [r["uncoveredProbability"] for r in _rows_for(issue, judged)
                    if r["uncoveredProbability"] is not None]
            if seen:
                found.append(round(max(seen), 2))
    return sorted(found)


def alarm_buckets(records):
    """{bucket: count} over compliant sections with a MEDIUM+ finding."""
    judged = _judged_by_section(records)
    counts = Counter()
    for section in (r for r in records if r["kind"] == "compliant" and r["seriousAlarm"]):
        flagged = [r for r in judged.get((section["run"], section["contract"], section["section"]), [])
                   if r.get("flagged")]
        if not flagged:
            counts["OTHER_CHECK"] += 1
        else:
            counts["CONFIDENT_ALARM" if confident(max(flagged, key=lambda r: r["flagMass"]))
                   else "DOUBTFUL_ALARM"] += 1
    return dict(counts)


def probe_summary(records):
    rows = [r for r in records if r["kind"] == "judged" and r.get("probeTop")]
    if not rows:
        return None
    judged = _judged_by_section(records)
    missed = [r for r in records if r["kind"] == "issue" and not r["detected"]]
    alarms = [r for r in records if r["kind"] == "compliant" and r["seriousAlarm"]]

    def clears(section):
        flagged = [r for r in judged.get((section["run"], section["contract"], section["section"]), [])
                   if r.get("flagged")]
        return bool(flagged) and all(r.get("probeFlagged") is False for r in flagged)
    return {"modes": sorted({r["probe"] for r in rows if r["probe"]}), "positions": len(rows),
            "topFlips": sum(r["top"] != r["probeTop"] for r in rows),
            "flagFlips": sum(r["flagged"] != r["probeFlagged"] for r in rows),
            "missesProbeFlags": sum(any(r.get("probeFlagged") for r in _rows_for(i, judged)) for i in missed),
            "misses": len(missed), "alarmsProbeClears": sum(clears(a) for a in alarms), "alarms": len(alarms)}


def adjudication_load(records):
    """(judged positions, positions outside the confident band, those per contract)."""
    rows = [r for r in records if r["kind"] == "judged" and r["positionKey"]]
    contracts = {(r["run"], r["contract"]) for r in records}
    doubtful = sum(not confident(r) for r in rows)
    return len(rows), doubtful, round(doubtful / max(len(contracts), 1), 1)


def second_opinion_summary(records):
    """Engine v2: positions sent to a second opinion, by outcome, and whom the answer key backs where
    Jev and the second opinion disagreed (section level: a planted issue in the section means
    flagging was right; a compliant section means not flagging was right)."""
    rows = [r for r in records if r["kind"] == "judged" and r.get("route") == "ADJUDICATE"]
    if not rows:
        return None
    issue_sections = {(r["run"], r["contract"], s) for r in records if r["kind"] == "issue" for s in r["sections"]}
    compliant = {(r["run"], r["contract"], r["section"]) for r in records if r["kind"] == "compliant"}
    contracts = {(r["run"], r["contract"]) for r in records}
    backs = Counter()
    for r in rows:
        if r.get("secondOpinionOutcome") != "DISAGREED":
            continue
        key = (r["run"], r["contract"], r["section"])
        if key in issue_sections or key in compliant:
            backs["JEV" if r["flagged"] == (key in issue_sections) else "SECOND_OPINION"] += 1
        else:
            backs["UNLABELLED"] += 1
    return {"adjudicated": len(rows), "perContract": round(len(rows) / max(len(contracts), 1), 1),
            "outcomes": dict(Counter(r.get("secondOpinionOutcome") for r in rows)),
            "disagreementsBack": dict(backs)}


def sweep(records):
    """[(threshold, issue sections caught, judged issue sections, compliant alarmed, judged compliant)]."""
    mass = {}
    for r in records:
        if r["kind"] == "judged" and r["positionKey"]:
            key = (r["run"], r["contract"], r["section"])
            mass[key] = max(mass.get(key, 0.0), r["flagMass"])
    issue_sections = {(r["run"], r["contract"], s) for r in records if r["kind"] == "issue"
                      for s in r["sections"]} & mass.keys()
    compliant = {(r["run"], r["contract"], r["section"]) for r in records
                 if r["kind"] == "compliant"} & mass.keys()
    return [(t, sum(mass[k] >= t for k in issue_sections), len(issue_sections),
             sum(mass[k] >= t for k in compliant), len(compliant)) for t in SWEEP]


def _table(header, rows):
    return (["| " + " | ".join(header) + " |", "|" + "---|" * len(header)]
            + ["| " + " | ".join(str(c) for c in row) + " |" for row in rows])


def report(records):
    lines = ["# P0 calibration", "",
             f"Confident band (spec §6 initial): pMax ≥ {CONFIDENT_P} and top-2 margin ≥ {CONFIDENT_MARGIN}.", ""]
    for run in sorted({r["run"] for r in records}):
        rs = [r for r in records if r["run"] == run]
        n, doubtful, per_contract = adjudication_load(rs)
        lines += [f"## Run {run}", "",
                  f"- Judged positions {n}; outside the confident band {doubtful} "
                  f"(≈ {per_contract} adjudications per contract at the initial band)", "",
                  "### Missed planted issues, by what the judge saw", ""]
        misses = miss_buckets(rs)
        lines += _table(["Bucket", "Issues", "Categories"],
                        [(b, sum(c.values()), ", ".join(f"{k} {v}" for k, v in sorted(c.items())))
                         for b, c in sorted(misses.items())]) + [""]
        triage = triage_misses(rs)
        if triage:
            lines += [f"- Triage probability of the misses it decided: {', '.join(map(str, triage))} "
                      "(product threshold 0.5)", ""]
        lines += ["### MEDIUM+ false alarms, by source", ""]
        lines += _table(["Bucket", "Sections"], sorted(alarm_buckets(rs).items())) + [""]
        probe = probe_summary(rs)
        if probe:
            lines += [f"### Probe ({', '.join(probe['modes'])})", "",
                      f"- Positions {probe['positions']}; top option changed {probe['topFlips']} "
                      f"({100 * probe['topFlips'] / probe['positions']:.1f}%); flag decision changed "
                      f"{probe['flagFlips']} ({100 * probe['flagFlips'] / probe['positions']:.1f}%)",
                      f"- Missed issues the probe flags: {probe['missesProbeFlags']}/{probe['misses']}; "
                      f"MEDIUM+ alarms the probe clears: {probe['alarmsProbeClears']}/{probe['alarms']}", ""]
        second = second_opinion_summary(rs)
        if second:
            lines += ["### Second opinions (engine v2)", "",
                      f"- Positions sent: {second['adjudicated']} (≈ {second['perContract']} per contract); outcomes: "
                      + ", ".join(f"{k} {v}" for k, v in sorted(second["outcomes"].items())),
                      "- Disagreements the answer key backs: "
                      + (", ".join(f"{k} {v}" for k, v in sorted(second["disagreementsBack"].items())) or "none"), ""]
        lines += ["### Flag-threshold sweep (judged sections only)", ""]
        lines += _table(["Threshold", "Issue sections caught", "Compliant sections alarmed"],
                        [(t, f"{c}/{ci}", f"{a}/{ca}") for t, c, ci, a, ca in sweep(rs)]) + [""]
    return "\n".join(lines)


def main():
    ap = argparse.ArgumentParser()
    sub = ap.add_subparsers(dest="cmd", required=True)
    b = sub.add_parser("build")
    b.add_argument("--corpus", required=True, type=pathlib.Path)
    b.add_argument("--run", required=True, action="append", type=pathlib.Path)
    b.add_argument("--out", required=True, type=pathlib.Path)
    r = sub.add_parser("report")
    r.add_argument("files", nargs="+", type=pathlib.Path)
    args = ap.parse_args()
    if args.cmd == "build":
        records = build(args.corpus, args.run)
        args.out.write_text("".join(json.dumps(x) + "\n" for x in records))
        print(f"{len(records)} records -> {args.out}")
    else:
        records = [json.loads(line) for f in args.files for line in f.read_text().splitlines() if line]
        print(report(records))


if __name__ == "__main__":
    main()
