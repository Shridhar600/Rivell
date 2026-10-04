package com.rivell.workbench.platform.demo;

import java.util.List;

/**
 * The canonical demo scenario map. Every entry is a state the domain reaches on its own
 * through the real services the seeder calls; none is written directly. Tests assert the
 * seeded database against these keys and {@code SCENARIOS.md} documents them for the
 * Product Owner.
 */
public final class DemoScenarioCatalog {

    public static final String R01_READY = "R01";
    public static final String R02_IN_REVIEW = "R02";
    public static final String R03_ESCALATION = "R03";
    public static final String R04_READY_TO_FINALIZE = "R04";
    public static final String R05_FINALIZED_CHANGES = "R05";
    public static final String R06_FINALIZED_NO_CHANGES = "R06";
    public static final String R07_VERIFIED_REDLINE = "R07";
    public static final String R08_REDLINE_FAILED = "R08";
    public static final String R09_RETRY_SUCCEEDED = "R09";
    public static final String R10_ANALYSIS_FAILED = "R10";
    public static final String R11_LIMITED_ANALYSIS = "R11";
    public static final String R12_QUEUED = "R12";
    public static final String C01_CLEAN_UPLOAD = "C01";
    public static final String C02_PARSE_FAILURE = "C02";
    public static final String C03_SPARSE_STRUCTURE = "C03";
    public static final String K01_PUBLISHED_PLAYBOOK = "K01";
    public static final String K02_PLAYBOOK_VERSIONS = "K02";
    public static final String K03_CLAUSE_LIBRARY = "K03";
    public static final String K04_SOURCE_LINEAGE = "K04";
    public static final String K05_COMPILATION_IN_REVIEW = "K05";
    public static final String K06_INGESTION_FAILURE = "K06";

    private static final List<DemoScenario> ALL = List.of(
            review(R01_READY, "Vendor NDA - Ready for review",
                    "Vendor NDA - Ready for review.docx",
                    "analysis COMPLETED, real Findings and evidence, no human decisions",
                    "workspace yes; finalize yes (server readiness); redline no",
                    "none"),
            review(R02_IN_REVIEW, "Acme MSA - Review in progress",
                    "Acme MSA - Review in progress.docx",
                    "analysis COMPLETED, one finding accepted, others unresolved",
                    "workspace yes; finalize no (unresolved review-required findings)",
                    "none"),
            review(R03_ESCALATION, "Hooli MSA - Escalation blocking",
                    "Hooli MSA - Escalation blocking.docx",
                    "analysis COMPLETED, one finding ESCALATEd with a rationale",
                    "workspace yes; finalize no (ACTIVE_ESCALATION blocker)",
                    "none"),
            review(R04_READY_TO_FINALIZE, "Procurement NDA - Ready to finalize",
                    "Procurement NDA - Ready to finalize.docx",
                    "all non-informational findings resolved, not finalized",
                    "workspace yes; finalize yes; redline no",
                    "none"),
            review(R05_FINALIZED_CHANGES, "Atlas MSA - Finalized",
                    "Atlas MSA - Finalized.docx",
                    "real finalization with a frozen change set containing a REPLACE",
                    "workspace read-only; redline generated only in R07",
                    "redline deliberately not generated for this row"),
            review(R06_FINALIZED_NO_CHANGES, "Employee NDA - Finalized, no changes",
                    "Employee NDA - Finalized, no changes.docx",
                    "all findings rejected, real finalization with an empty change set",
                    "workspace read-only; redline not required",
                    "none"),
            review(R07_VERIFIED_REDLINE, "Procurement MSA - Verified redline",
                    "Procurement MSA - Verified redline.docx",
                    "finalized with changes, deterministic redline COMPLETED and verified",
                    "workspace read-only; redline download yes",
                    "none"),
            review(R08_REDLINE_FAILED, "Globex NDA - Redline failed",
                    "Globex NDA - Redline failed.docx",
                    "finalized with INSERTs, then a human semantics correction drifts the digest; "
                            + "redline FAILED APPROVED_SEMANTICS_DRIFT with no artifact",
                    "workspace read-only; redline failed; download no",
                    "deterministic failure by design, not a retryable-transient claim (DW-048)"),
            review(R09_RETRY_SUCCEEDED, "Initech NDA - Retry succeeded",
                    "Initech NDA - Retry succeeded.docx",
                    "R08 path, cause repaired by reverting the correction, retry attempt 2 "
                            + "COMPLETED; attempt 1 remains FAILED",
                    "workspace read-only; redline download yes",
                    "the only recoverable redline failure; no generic retry promise"),
            review(R10_ANALYSIS_FAILED, "Umbrella MSA - Analysis failed",
                    "Umbrella MSA - Analysis failed.docx",
                    "analysis genuinely FAILED at an injected demo-only service boundary",
                    "workspace unavailable; redline no",
                    "the failure seam exists only while demo seeding is enabled"),
            review(R11_LIMITED_ANALYSIS, "Soylent MSA - Limited analysis",
                    "Soylent MSA - Limited analysis.docx",
                    "review COMPLETED with every check DEGRADED after a semantics correction; "
                            + "server reports ANALYSIS_DEGRADED",
                    "workspace yes; finalize blocked",
                    "partial degradation is not modelled; this fixture degrades every check"),
            review(R12_QUEUED, "Vandelay MSA - Analysis queued",
                    "Vandelay MSA - Analysis queued.docx",
                    "review created and ANALYSIS_QUEUED; worker disabled so it stays queued",
                    "workspace no; finalize no",
                    "ANALYZING is omitted: it is lease-fenced and too transient to seed honestly"),
            contract(C01_CLEAN_UPLOAD, "Clean upload",
                    "Vendor NDA - Ready for review.docx",
                    "parsed immutable contract version v1", "contract read APIs"),
            contract(C02_PARSE_FAILURE, "Ingestion parse failure",
                    "Corrupt Upload - Parse failed.docx",
                    "contract version parse_status FAILED through the real parser",
                    "contract list row with failed parse state"),
            contract(C03_SPARSE_STRUCTURE, "Minimal sparse structure",
                    "Minimal Sparse - Empty structure.docx",
                    "structurally valid sparse document with empty and table-only units",
                    "contract reads; sparse ingestion must never crash"),
            knowledge(K01_PUBLISHED_PLAYBOOK, "Published playbook",
                    "Rivell Demo Playbook.docx",
                    "ingested, approved, published #1 with positions, escalation trigger and "
                            + "immutable publication number",
                    "knowledge reads; used as analysis evidence"),
            knowledge(K02_PLAYBOOK_VERSIONS, "Playbook version history",
                    "Rivell Demo Playbook v2.docx",
                    "publication #2 with explicit supersede items; publication #1 immutable",
                    "knowledge reads; version history"),
            knowledge(K03_CLAUSE_LIBRARY, "Published clause library",
                    "Rivell Demo Clause Library.docx",
                    "ingested, approved, published with variants and provenance",
                    "approved drafting evidence"),
            knowledge(K04_SOURCE_LINEAGE, "Source lineage",
                    "Rivell Demo Playbook.docx",
                    "source node to extraction unit to candidate to publication item resolves",
                    "knowledge reads; evidence provenance"),
            knowledge(K05_COMPILATION_IN_REVIEW, "Compilation in review",
                    "Rivell Demo Draft Playbook.docx",
                    "ingested with candidates PENDING, never published",
                    "knowledge reads; review queue"),
            knowledge(K06_INGESTION_FAILURE, "Knowledge ingestion failure",
                    "Unreadable Playbook.docx",
                    "artifact ingest_status FAILED with no candidates",
                    "knowledge reads; failure state"));

    private DemoScenarioCatalog() {
    }

    public static List<DemoScenario> all() {
        return ALL;
    }

    private static DemoScenario review(String key, String displayName, String fixture,
            String intendedState, String capabilities, String caveats) {
        return new DemoScenario(key, displayName, DemoScenario.Kind.REVIEW, fixture,
                intendedState, capabilities, caveats);
    }

    private static DemoScenario contract(String key, String displayName, String fixture,
            String intendedState, String capabilities) {
        return new DemoScenario(key, displayName, DemoScenario.Kind.CONTRACT, fixture,
                intendedState, capabilities, "none");
    }

    private static DemoScenario knowledge(String key, String displayName, String fixture,
            String intendedState, String capabilities) {
        return new DemoScenario(key, displayName, DemoScenario.Kind.KNOWLEDGE, fixture,
                intendedState, capabilities, "none");
    }
}
