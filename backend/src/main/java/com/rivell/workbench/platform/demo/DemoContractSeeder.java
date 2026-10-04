package com.rivell.workbench.platform.demo;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.rivell.workbench.contracts.ContractEnrichmentService;
import com.rivell.workbench.contracts.ContractIngestionService;
import com.rivell.workbench.contracts.ContractQueryService;
import com.rivell.workbench.contracts.ContractType;
import com.rivell.workbench.contracts.Jurisdiction;
import com.rivell.workbench.contracts.PaperSource;
import com.rivell.workbench.contracts.PartyRole;
import com.rivell.workbench.contracts.ReviewProfile;
import com.rivell.workbench.organization.IdentityProvider;
import com.rivell.workbench.review.ReviewPackRegistry;

/**
 * Seeds the scenario contracts through the real ingestion and enrichment services.
 * A contract is identified by its immutable source filename, so a rerun finds the
 * existing row instead of ingesting a second copy. Nothing writes a parse status or a
 * semantic value directly.
 */
final class DemoContractSeeder {

    private static final Logger log = LoggerFactory.getLogger(DemoContractSeeder.class);
    private static final int LOOKUP_LIMIT = 100;

    private final ContractIngestionService ingestion;
    private final ContractEnrichmentService enrichment;
    private final ContractQueryService queries;
    private final IdentityProvider identityProvider;

    DemoContractSeeder(ContractIngestionService ingestion,
            ContractEnrichmentService enrichment, ContractQueryService queries,
            IdentityProvider identityProvider) {
        this.ingestion = ingestion;
        this.enrichment = enrichment;
        this.queries = queries;
        this.identityProvider = identityProvider;
    }

    record SeededContract(UUID contractId, UUID versionId, String filename,
            ContractType type, String packKey) {
    }

    Map<String, SeededContract> seed() {
        var existing = existingContracts();
        var seeded = new LinkedHashMap<String, SeededContract>();
        for (var spec : specs()) {
            var contractId = existing.get(spec.filename());
            if (contractId == null) {
                contractId = ingest(spec.filename());
            }
            if (spec.enrich() && queries.getSemanticsForOrganization(
                    organizationId(), contractId, 1).isEmpty()) {
                enrichment.enrich(contractId, 1, profile(spec));
            }
            var version = queries.getVersionSummary(contractId, 1);
            seeded.put(spec.filename(), new SeededContract(contractId, version.versionId(),
                    spec.filename(), spec.type(), spec.packKey()));
        }
        return seeded;
    }

    private UUID organizationId() {
        return identityProvider.currentIdentity().organizationId();
    }

    private record ContractSpec(String filename, ContractType type, String packKey,
            boolean enrich) {
    }

    private static List<ContractSpec> specs() {
        var ndaPack = ReviewPackRegistry.NDA_PACK;
        var msaPack = ReviewPackRegistry.MSA_PACK;
        var specs = new ArrayList<ContractSpec>();
        specs.add(new ContractSpec(DemoDocuments.VENDOR_NDA, ContractType.NDA, ndaPack, true));
        specs.add(new ContractSpec(DemoDocuments.ACME_MSA, ContractType.MSA, msaPack, true));
        specs.add(new ContractSpec(DemoDocuments.HOOLI_MSA, ContractType.MSA, msaPack, true));
        specs.add(new ContractSpec(DemoDocuments.PROCUREMENT_NDA, ContractType.NDA, ndaPack, true));
        specs.add(new ContractSpec(DemoDocuments.ATLAS_MSA, ContractType.MSA, msaPack, true));
        specs.add(new ContractSpec(DemoDocuments.EMPLOYEE_NDA, ContractType.NDA, ndaPack, true));
        specs.add(new ContractSpec(DemoDocuments.PROCUREMENT_MSA, ContractType.MSA, msaPack, true));
        specs.add(new ContractSpec(DemoDocuments.GLOBEX_NDA, ContractType.NDA, msaPack, true));
        specs.add(new ContractSpec(DemoDocuments.INITECH_NDA, ContractType.NDA, msaPack, true));
        specs.add(new ContractSpec(DemoDocuments.UMBRELLA_MSA, ContractType.MSA, msaPack, true));
        specs.add(new ContractSpec(DemoDocuments.SOYLENT_MSA, ContractType.MSA, msaPack, true));
        specs.add(new ContractSpec(DemoDocuments.VANDELAY_MSA, ContractType.MSA, msaPack, true));
        specs.add(new ContractSpec(DemoDocuments.CORRUPT_UPLOAD, ContractType.OTHER, null, false));
        specs.add(new ContractSpec(DemoDocuments.SPARSE, ContractType.OTHER, msaPack, true));
        return specs;
    }

    private Map<String, UUID> existingContracts() {
        var found = new LinkedHashMap<String, UUID>();
        for (var entry : queries.listContractsPage(LOOKUP_LIMIT, null).items()) {
            var version = queries.getLatestVersionSummary(entry.contractId());
            if (version != null && version.filename() != null) {
                found.putIfAbsent(version.filename(), entry.contractId());
            }
        }
        return found;
    }

    private UUID ingest(String filename) {
        try (var source = DemoDocuments.open(filename)) {
            var result = ingestion.ingest(filename, DemoDocuments.DOCX, source);
            log.info("demo scenario: ingested contract {} (parse {})",
                    filename, result.parseStatus());
            return result.contractId();
        } catch (IOException e) {
            throw new IllegalStateException("Demo document unreadable: " + filename, e);
        }
    }

    private static ReviewProfile profile(ContractSpec spec) {
        return new ReviewProfile(spec.type(), PartyRole.RECEIVING_PARTY,
                PaperSource.COUNTERPARTY_PAPER, Jurisdiction.US, spec.packKey());
    }
}
