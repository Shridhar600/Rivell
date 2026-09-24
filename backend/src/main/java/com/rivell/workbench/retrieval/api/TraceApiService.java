package com.rivell.workbench.retrieval.api;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.rivell.workbench.retrieval.RetrievalRepository;
import com.rivell.workbench.retrieval.RetrievalTrace;
import com.rivell.workbench.retrieval.TraceNotFoundException;
import com.rivell.workbench.retrieval.UnauthorizedEvidenceAccessException;
import com.rivell.workbench.organization.IdentityProvider;
import com.rivell.workbench.organization.Permissions;

/**
 * Composes the product-facing retrieval trace detail (debug/audit surface).
 */
public class TraceApiService {

    private final RetrievalRepository repository;
    private final IdentityProvider identityProvider;
    private final com.rivell.workbench.organization.AuthorizationService authorizationService;

    public TraceApiService(
            RetrievalRepository repository,
            IdentityProvider identityProvider,
            com.rivell.workbench.organization.AuthorizationService authorizationService) {
        this.repository = repository;
        this.identityProvider = identityProvider;
        this.authorizationService = authorizationService;
    }

    public RetrievalResponses.RetrievalTraceResponse detail(UUID traceId) {
        var identity = identityProvider.currentIdentity();
        if (!authorizationService.isAuthorized(identity, Permissions.RETRIEVAL_READ)) {
            throw new UnauthorizedEvidenceAccessException(identity.userId());
        }
        var trace = repository.findTrace(identity.organizationId(), traceId)
                .orElseThrow(() -> new TraceNotFoundException(traceId));
        return toResponse(trace);
    }

    private RetrievalResponses.RetrievalTraceResponse toResponse(RetrievalTrace trace) {
        var stages = new java.util.LinkedHashMap<String, RetrievalResponses.RetrievalTraceResponse.StageSummaryResponse>();
        for (var entry : trace.stages().entrySet()) {
            stages.put(entry.getKey(), new RetrievalResponses.RetrievalTraceResponse.StageSummaryResponse(
                    entry.getValue().stage(),
                    entry.getValue().candidateCount(),
                    entry.getValue().candidateIds(),
                    entry.getValue().candidateRanks(),
                    entry.getValue().executionIds()));
        }
        var rejected = new ArrayList<RetrievalResponses.RetrievalTraceResponse.RejectedEvidenceResponse>();
        for (var item : trace.rejectedEvidence()) {
            rejected.add(new RetrievalResponses.RetrievalTraceResponse.RejectedEvidenceResponse(
                    item.candidateId(), item.stage(), item.reason()));
        }
        return new RetrievalResponses.RetrievalTraceResponse(
                trace.id(),
                trace.purpose(),
                trace.request(),
                java.util.Collections.unmodifiableMap(stages),
                trace.selectedEvidenceIds(),
                List.copyOf(rejected),
                trace.degradation(),
                trace.stageTimings(),
                trace.modelVersions(),
                trace.knowledgePin().publicationNumber());
    }
}
