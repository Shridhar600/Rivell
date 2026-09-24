package com.rivell.workbench.retrieval;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.rivell.workbench.contracts.ContractIngestionResult;
import com.rivell.workbench.contracts.ContractIngestionService;
import com.rivell.workbench.contracts.ContractSemantics;
import com.rivell.workbench.contracts.ContractType;
import com.rivell.workbench.contracts.EnrichmentStatus;
import com.rivell.workbench.contracts.Jurisdiction;
import com.rivell.workbench.contracts.PaperSource;
import com.rivell.workbench.contracts.PartyRole;
import com.rivell.workbench.contracts.ReviewProfile;
import com.rivell.workbench.knowledge.ArtifactManifest;
import com.rivell.workbench.knowledge.ArtifactType;
import com.rivell.workbench.knowledge.CandidateReviewService;
import com.rivell.workbench.knowledge.CandidateType;
import com.rivell.workbench.knowledge.KnowledgeArtifact;
import com.rivell.workbench.knowledge.KnowledgeCandidate;
import com.rivell.workbench.knowledge.KnowledgeIngestionService;
import com.rivell.workbench.knowledge.KnowledgePublication;
import com.rivell.workbench.knowledge.KnowledgePublicationService;
import com.rivell.workbench.knowledge.KnowledgeQueryService;
import com.rivell.workbench.knowledge.ReviewStatus;

/**
 * The M6 labeled evidence corpus. Authoritative knowledge is published through the
 * real ingestion → review → publication flows (no direct table seeding); the current
 * contract is a real M5 enrichment. Publication numbers are recorded per fixture so
 * snapshot-pinned requests are reproducible.
 */
public final class M6EvidenceCorpus {

    public static final String SUBJECT_CLAUSE_TEXT =
            "6.1 Subject to Section 6.3, Supplier's total aggregate liability arising out "
                    + "of or related to this Agreement shall not exceed an amount equal to three (3) "
                    + "times the Fees paid or payable in the twelve (12) months immediately preceding "
                    + "the event giving rise to the claim.";

    /** Labels of the standard MSA liability corpus fixtures. */
    public enum Fixture {
        POLICY_MAIN("m6-policy-playbook.docx"),
        POLICY_UK("m6-policy-uk-playbook.docx"),
        POLICY_NDA("m6-policy-nda-playbook.docx"),
        POLICY_SUPPLIER("m6-policy-supplier-playbook.docx"),
        POLICY_FUTURE("m6-policy-future-playbook.docx"),
        POLICY_SUPERSEDED("m6-policy-superseded-playbook.docx"),
        POLICY_AMBIGUOUS("m6-policy-ambiguity-playbook.docx"),
        LIBRARY_MAIN("m6-clause-library.docx"),
        LIBRARY_UK("m6-clause-library-uk.docx"),
        PRECEDENT_RELEVANT("m6-precedent-relevant.docx"),
        PRECEDENT_LEXICAL("m6-precedent-lexical.docx"),
        PRECEDENT_PARAPHRASE("m6-precedent-paraphrase.docx"),
        PRECEDENT_INCOMPATIBLE("m6-precedent-incompatible.docx"),
        PRECEDENT_GOVERNING_LAW("m6-precedent-governing-law.docx");

        private final String filename;

        Fixture(String filename) {
            this.filename = filename;
        }

        public String filename() {
            return filename;
        }
    }

    public static final ArtifactManifest MSA_US_CUSTOMER = new ArtifactManifest(
            Set.of("MSA"), "MSA_PACK", "Customer", "Third-Party", "US", "en",
            LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31), null);

    public record Corpus(
            UUID organizationId,
            Map<Fixture, List<KnowledgeCandidate>> published,
            Map<Fixture, Long> publishedAt,
            List<KnowledgeCandidate> approvedNotPublished,
            List<KnowledgeCandidate> rejected,
            ContractIngestionResult contract,
            ContractSemantics semantics,
            long latestPublicationNumber) {

        public KnowledgeCandidate candidate(Fixture fixture) {
            return published.get(fixture).stream()
                    .filter(candidate -> "Limitation of Liability".equals(candidate.rawLabel()))
                    .findFirst()
                    .orElseThrow();
        }

        public List<KnowledgeCandidate> allCandidatesOf(Fixture fixture) {
            return published.get(fixture);
        }
    }

    private final KnowledgeIngestionService knowledgeIngestion;
    private final CandidateReviewService review;
    private final KnowledgePublicationService publication;
    private final KnowledgeQueryService knowledgeQueries;
    private final ContractIngestionService contractIngestion;
    private final com.rivell.workbench.contracts.ContractEnrichmentService enrichment;

    public M6EvidenceCorpus(
            KnowledgeIngestionService knowledgeIngestion,
            CandidateReviewService review,
            KnowledgePublicationService publication,
            KnowledgeQueryService knowledgeQueries,
            ContractIngestionService contractIngestion,
            com.rivell.workbench.contracts.ContractEnrichmentService enrichment) {
        this.knowledgeIngestion = knowledgeIngestion;
        this.review = review;
        this.publication = publication;
        this.knowledgeQueries = knowledgeQueries;
        this.contractIngestion = contractIngestion;
        this.enrichment = enrichment;
    }

    /**
     * Builds the standard MSA liability corpus in the current organization:
     * publication 1 pins the superseded 2025 policy; publication 2 adds the
     * strongly relevant precedent; publication 3 supersedes the 2025 policy with
     * the current one; later publications add the remaining corpus.
     */
    public Corpus buildMainCorpus(UUID organizationId) {
        var published = new LinkedHashMap<Fixture, List<KnowledgeCandidate>>();
        var publishedAt = new LinkedHashMap<Fixture, Long>();

        publish(published, publishedAt, Fixture.POLICY_SUPERSEDED,
                policyManifest(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31)), null);
        publish(published, publishedAt, Fixture.PRECEDENT_RELEVANT, MSA_US_CUSTOMER, null);

        var supersededId = conceptCandidate(published.get(Fixture.POLICY_SUPERSEDED)).id();
        publish(published, publishedAt, Fixture.POLICY_MAIN,
                policyManifest(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31)),
                supersededId);
        publish(published, publishedAt, Fixture.POLICY_NDA, ndaManifest(), null);
        publish(published, publishedAt, Fixture.POLICY_SUPPLIER, supplierManifest(), null);
        publish(published, publishedAt, Fixture.POLICY_FUTURE, futureManifest(), null);
        publish(published, publishedAt, Fixture.LIBRARY_MAIN, MSA_US_CUSTOMER, null);
        publish(published, publishedAt, Fixture.PRECEDENT_LEXICAL, MSA_US_CUSTOMER, null);
        publish(published, publishedAt, Fixture.PRECEDENT_PARAPHRASE, MSA_US_CUSTOMER, null);
        publish(published, publishedAt, Fixture.PRECEDENT_INCOMPATIBLE, MSA_US_CUSTOMER, null);
        publish(published, publishedAt, Fixture.PRECEDENT_GOVERNING_LAW, MSA_US_CUSTOMER, null);

        ContractIngestionResult contract;
        try {
            contract = contractIngestion.ingest("m5-msa-services.docx",
                    ContractIngestionService.DOCX_CONTENT_TYPE,
                    new ByteArrayInputStream(bytes("m5-msa-services.docx")));
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
        var profile = new ReviewProfile(ContractType.MSA, PartyRole.CUSTOMER,
                PaperSource.THIRD_PARTY, Jurisdiction.US);
        var semantics = enrichment.enrich(contract.contractId(), 1, profile);
        if (semantics.enrichmentStatus() != EnrichmentStatus.READY) {
            throw new IllegalStateException("MSA corpus fixture did not enrich READY");
        }

        // never-visible fixtures: one approved-but-unpublished candidate and one rejected
        var notPublished = ingestAndApprove(Fixture.POLICY_FUTURE, futureManifest());
        var rejectedSource = ingest(Fixture.PRECEDENT_GOVERNING_LAW, MSA_US_CUSTOMER);
        var rejectedCandidate = candidates(rejectedSource, true).get(0);
        review.reject(rejectedCandidate.id(), "not acceptable as corpus evidence");

        var latest = publishedAt.get(Fixture.PRECEDENT_GOVERNING_LAW);
        return new Corpus(organizationId, java.util.Collections.unmodifiableMap(published),
                java.util.Collections.unmodifiableMap(publishedAt), notPublished,
                List.of(rejectedCandidate), contract, semantics, latest);
    }

    /**
     * Builds the exclusion corpus: one approved-but-never-published candidate and one
     * rejected candidate — neither may ever surface in evidence.
     */
    public Exclusions buildExclusionCorpus(UUID organizationId) {
        var notPublished = ingestAndApprove(Fixture.PRECEDENT_LEXICAL, MSA_US_CUSTOMER);
        var rejected = ingest(Fixture.PRECEDENT_RELEVANT, MSA_US_CUSTOMER);
        var toReject = candidates(rejected, true).get(0);
        review.reject(toReject.id(), "not acceptable as corpus evidence");
        return new Exclusions(organizationId, notPublished.get(0), toReject);
    }

    public record Exclusions(
            UUID organizationId,
            KnowledgeCandidate approvedNotPublished,
            KnowledgeCandidate rejected) {
    }

    private void publish(
            Map<Fixture, List<KnowledgeCandidate>> published,
            Map<Fixture, Long> publishedAt,
            Fixture fixture, ArtifactManifest manifest, UUID supersededId) {
        var artifact = ingest(fixture, manifest);
        var candidates = candidates(artifact, false);
        candidates.forEach(candidate -> review.approve(candidate.id(), "corpus fixture"));
        var publishing = supersededId == null
                ? candidates.stream().map(KnowledgeCandidate::id).toList()
                : List.of(conceptCandidate(candidates).id());
        var supersessionMap = supersededId == null
                ? null
                : Map.of(conceptCandidate(candidates).id(), supersededId);
        var publicationResult = publication.publish(publishing, supersessionMap, fixture.name());
        published.put(fixture, candidates);
        publishedAt.put(fixture, publicationResult.publicationNumber());
    }

    private List<KnowledgeCandidate> ingestAndApprove(Fixture fixture, ArtifactManifest manifest) {
        var candidates = candidates(ingest(fixture, manifest), false);
        candidates.forEach(candidate -> review.approve(candidate.id(), "never published"));
        return candidates;
    }

    public List<KnowledgeCandidate> candidates(KnowledgeArtifact artifact, boolean includeRejected) {
        var all = new ArrayList<KnowledgeCandidate>();
        for (KnowledgeCandidate candidate : knowledgeQueries.getCandidates(artifact.id())) {
            if (includeRejected || candidate.reviewStatus() != ReviewStatus.REJECTED) {
                all.add(candidate);
            }
        }
        return List.copyOf(all);
    }

    private static KnowledgeCandidate conceptCandidate(List<KnowledgeCandidate> candidates) {
        return candidates.stream()
                .filter(candidate -> "LIMITATION_OF_LIABILITY".equals(candidate.legalConceptKey()))
                .findFirst()
                .orElseThrow();
    }

    private KnowledgeArtifact ingest(Fixture fixture, ArtifactManifest manifest) {
        try (InputStream stream = fixtureStream(fixture.filename())) {
            return knowledgeIngestion.ingest(fixture.filename(), "application/octet-stream",
                    artifactTypeOf(fixture), manifest, stream);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static ArtifactType artifactTypeOf(Fixture fixture) {
        return switch (fixture) {
            case POLICY_MAIN, POLICY_UK, POLICY_NDA, POLICY_SUPPLIER, POLICY_FUTURE,
                 POLICY_SUPERSEDED, POLICY_AMBIGUOUS -> ArtifactType.PLAYBOOK;
            case LIBRARY_MAIN, LIBRARY_UK -> ArtifactType.CLAUSE_LIBRARY;
            case PRECEDENT_RELEVANT, PRECEDENT_LEXICAL, PRECEDENT_PARAPHRASE,
                 PRECEDENT_INCOMPATIBLE, PRECEDENT_GOVERNING_LAW ->
                    ArtifactType.EXECUTED_PRECEDENT;
        };
    }

    private InputStream fixtureStream(String name) {
        var stream = getClass().getClassLoader().getResourceAsStream("fixtures/" + name);
        if (stream == null) {
            throw new IllegalStateException("Missing fixture " + name);
        }
        return stream;
    }

    private byte[] bytes(String name) {
        try (InputStream stream = fixtureStream(name)) {
            return stream.readAllBytes();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public static ArtifactManifest policyManifest(LocalDate from, LocalDate to) {
        return new ArtifactManifest(Set.of("MSA"), "MSA_PACK", "Customer",
                "Third-Party", "US", "en", from, to, null);
    }

    public static ArtifactManifest msaManifest2026() {
        return policyManifest(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31));
    }

    public static ArtifactManifest ndaManifest() {
        return new ArtifactManifest(Set.of("NDA"), "NDA_PACK", "Customer",
                "Third-Party", "US", "en", null, null, null);
    }

    public static ArtifactManifest supplierManifest() {
        return new ArtifactManifest(Set.of("MSA"), "MSA_PACK", "Supplier",
                "Third-Party", "US", "en", null, null, null);
    }

    public static ArtifactManifest futureManifest() {
        return new ArtifactManifest(Set.of("MSA"), "MSA_PACK", "Customer",
                "Third-Party", "US", "en", LocalDate.of(2027, 1, 1),
                LocalDate.of(2027, 12, 31), null);
    }

    public static ArtifactManifest ukManifest() {
        return new ArtifactManifest(Set.of("MSA"), "MSA_PACK", "Customer",
                "Third-Party", "UK", "en", LocalDate.of(2026, 1, 1),
                LocalDate.of(2026, 12, 31), null);
    }

    public static ReviewProfile msaProfile() {
        return new ReviewProfile(ContractType.MSA, PartyRole.CUSTOMER,
                PaperSource.THIRD_PARTY, Jurisdiction.US);
    }
}
