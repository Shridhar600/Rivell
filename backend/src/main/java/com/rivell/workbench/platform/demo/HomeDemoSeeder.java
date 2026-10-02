package com.rivell.workbench.platform.demo;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import com.rivell.workbench.contracts.ContractEnrichmentService;
import com.rivell.workbench.contracts.ContractIngestionService;
import com.rivell.workbench.contracts.ContractType;
import com.rivell.workbench.contracts.Jurisdiction;
import com.rivell.workbench.contracts.PaperSource;
import com.rivell.workbench.contracts.PartyRole;
import com.rivell.workbench.contracts.ReviewProfile;
import com.rivell.workbench.knowledge.ArtifactManifest;
import com.rivell.workbench.knowledge.ArtifactType;
import com.rivell.workbench.knowledge.CandidateReviewService;
import com.rivell.workbench.knowledge.KnowledgeIngestionService;
import com.rivell.workbench.knowledge.KnowledgePublicationService;
import com.rivell.workbench.knowledge.KnowledgeQueryService;
import com.rivell.workbench.review.ReviewAnalysisService;
import com.rivell.workbench.review.ReviewPackRegistry;
import com.rivell.workbench.review.ReviewService;

/**
 * Populates local demo data so Home has something truthful to show.
 *
 * <p>Everything is produced by the real services: the documents are really parsed, really
 * enriched, and the completed review really runs through the analysis pipeline. Nothing
 * writes a status directly, so nothing here can present a state the domain would not
 * otherwise reach on its own.
 *
 * <p>The runner only exists when {@code rivell.demo.seed-enabled=true}, so an ordinary
 * startup is unaffected and the scripted provider is unreachable outside an explicitly
 * enabled local demo.
 *
 * <p>Seeding is idempotent. The presence of the demo playbook is the marker, so restarting
 * the application seeds nothing further and cannot duplicate rows. Resetting means
 * recreating the local database, which is documented in the module README rather than
 * implemented here: a bespoke cascading delete across contracts, snapshots, runs,
 * findings and publications would be a far larger risk than the demo warrants.
 */
@Component
@Order(0)
@ConditionalOnProperty(prefix = "rivell.demo", name = "seed-enabled", havingValue = "true")
public class HomeDemoSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(HomeDemoSeeder.class);
    private static final String DOCUMENTS = "demo/documents/";
    private static final String DOCX = "application/vnd.openxmlformats-officedocument"
            + ".wordprocessingml.document";

    private static final String NDA = "Atlas Mutual NDA.docx";
    private static final String MSA = "Northstar Vendor MSA.docx";
    private static final String DPA = "Beacon Data Processing Addendum.docx";
    private static final String PLAYBOOK = "Rivell Demo Playbook.docx";

    private final ContractIngestionService contractIngestion;
    private final ContractEnrichmentService enrichment;
    private final ReviewService reviewService;
    private final ReviewAnalysisService analysis;
    private final KnowledgeIngestionService knowledgeIngestion;
    private final KnowledgeQueryService knowledgeQuery;
    private final CandidateReviewService candidateReview;
    private final KnowledgePublicationService publication;

    public HomeDemoSeeder(
            ContractIngestionService contractIngestion,
            ContractEnrichmentService enrichment,
            ReviewService reviewService,
            ReviewAnalysisService analysis,
            KnowledgeIngestionService knowledgeIngestion,
            KnowledgeQueryService knowledgeQuery,
            CandidateReviewService candidateReview,
            KnowledgePublicationService publication) {
        this.contractIngestion = contractIngestion;
        this.enrichment = enrichment;
        this.reviewService = reviewService;
        this.analysis = analysis;
        this.knowledgeIngestion = knowledgeIngestion;
        this.knowledgeQuery = knowledgeQuery;
        this.candidateReview = candidateReview;
        this.publication = publication;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (alreadySeeded()) {
            log.info("Rivell demo: demo data already present, nothing seeded");
            return;
        }
        var contracts = seedContracts();
        var artifactsAndPublications = seedKnowledge();

        // A genuinely completed review: the analysis pipeline really runs.
        var completed = reviewService.create(contracts.get(MSA), 1, ReviewPackRegistry.MSA_PACK);
        analysis.analyze(completed.organizationId(), completed.id());

        // Genuinely queued reviews: created and deliberately not run, so Home shows real
        // pending work rather than an invented in-flight state.
        reviewService.create(contracts.get(NDA), 1, ReviewPackRegistry.NDA_PACK);
        reviewService.create(contracts.get(DPA), 1, ReviewPackRegistry.MSA_PACK);

        log.info("Rivell demo: seeded 3 contracts, 3 reviews (1 completed, 2 queued), "
                        + "1 knowledge artifact, {} publications",
                artifactsAndPublications);
    }

    private boolean alreadySeeded() {
        return knowledgeQuery.listArtifacts(null).stream()
                .anyMatch(artifact -> PLAYBOOK.equals(artifact.filename()));
    }

    private Map<String, UUID> seedContracts() {
        var ids = new java.util.LinkedHashMap<String, UUID>();
        ids.put(NDA, contract(NDA, profile(ContractType.NDA, ReviewPackRegistry.NDA_PACK)));
        ids.put(MSA, contract(MSA, profile(ContractType.MSA, ReviewPackRegistry.MSA_PACK)));
        ids.put(DPA, contract(DPA, profile(ContractType.OTHER, ReviewPackRegistry.MSA_PACK)));
        return ids;
    }

    private UUID contract(String filename, ReviewProfile profile) {
        try (InputStream source = document(filename)) {
            var ingested = contractIngestion.ingest(filename, DOCX, source);
            if (enrichment.enrich(ingested.contractId(), 1, profile) == null) {
                throw new IllegalStateException("Demo contract produced no semantics: " + filename);
            }
            return ingested.contractId();
        } catch (IOException e) {
            throw new IllegalStateException("Demo document unreadable: " + filename, e);
        }
    }

    private int seedKnowledge() {
        UUID artifactId;
        try (InputStream source = document(PLAYBOOK)) {
            artifactId = knowledgeIngestion.ingest(
                    PLAYBOOK, DOCX, ArtifactType.PLAYBOOK, manifest(), source).id();
        } catch (IOException e) {
            throw new IllegalStateException("Demo playbook unreadable", e);
        }

        var approved = new ArrayList<UUID>();
        for (var candidate : knowledgeQuery.getCandidates(artifactId)) {
            approved.add(candidateReview.approve(candidate.id(), "demo approval").id());
        }
        if (approved.isEmpty()) {
            log.warn("Rivell demo: playbook produced no candidates, so nothing was published");
            return 0;
        }
        publication.publish(approved, Map.of(), "Rivell demo publication");
        return 1;
    }

    private ArtifactManifest manifest() {
        return new ArtifactManifest(
                java.util.Set.of(), null, null, null, null, null, null, null,
                "Rivell local demo playbook");
    }

    /**
     * The declared pack must match the pack the review later requests; the review service
     * rejects a mismatch rather than silently substituting one.
     */
    private ReviewProfile profile(ContractType type, String packKey) {
        return new ReviewProfile(type, PartyRole.RECEIVING_PARTY, PaperSource.COUNTERPARTY_PAPER,
                Jurisdiction.US, packKey);
    }

    private InputStream document(String filename) throws IOException {
        InputStream source = new ClassPathResource(DOCUMENTS + filename).getInputStream();
        if (source == null) {
            throw new IllegalStateException("Demo document missing from the classpath: " + filename);
        }
        return source;
    }
}
