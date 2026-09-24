package com.rivell.workbench.retrieval.api;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.rivell.workbench.retrieval.RetrievalRepository;
import com.rivell.workbench.retrieval.RetrievalTrace;
import com.rivell.workbench.retrieval.TraceNotFoundException;
import com.rivell.workbench.organization.IdentityProvider;

/**
 * Composes the product-facing retrieval trace detail (debug/audit surface).
 */
public class TraceApiService {

    private final RetrievalRepository repository;
    private final IdentityProvider identityProvider;

    public TraceApiService(
            RetrievalRepository repository,
            IdentityProvider identityProvider) {
        this.repository = repository;
        this.identityProvider = identityProvider;
    }

    public RetrievalResponses.RetrievalTraceResponse detail(UUID traceId) {
        var trace = repository.findTrace(identityProvider.currentIdentity().organizationId(),
                traceId)
                .orElseThrow(() -> new TraceNotFoundException(traceId));
        return toResponse(trace);
    }

    private RetrievalResponses.RetrievalTraceResponse toResponse(RetrievalTrace trace) {
        var stages = new java.util.LinkedHashMap<String, RetrievalResponses.RetrievalTraceResponse.StageSummaryResponse>();
        for (var entry : trace.stages().entrySet()) {
            stages.put(entry.getKey(), new RetrievalResponses.RetrievalTraceResponse.StageSummaryResponse(
                    entry.getValue().stage(),
                    entry.getValue().candidateCount(),
                    entry.getValue().candidateIds()));
        }
        return new RetrievalResponses.RetrievalTraceResponse(
                trace.id(),
                trace.purpose(),
                trace.request(),
                java.util.Collections.unmodifiableMap(stages),
                trace.selectedEvidenceIds(),
                trace.degradation(),
                trace.stageTimings(),
                trace.modelVersions(),
                trace.knowledgePin().publicationNumber());
    }
}
