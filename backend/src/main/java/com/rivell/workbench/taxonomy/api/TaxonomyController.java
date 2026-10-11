package com.rivell.workbench.taxonomy.api;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.rivell.workbench.taxonomy.Concept;
import com.rivell.workbench.taxonomy.ConceptRegistry;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;

@RestController
@RequestMapping("/api/taxonomy")
class TaxonomyController {

    private final ConceptRegistry registry;

    TaxonomyController(ConceptRegistry registry) {
        this.registry = registry;
    }

    @Operation(summary = "List the legal topics clauses and playbook positions are classified against")
    @GetMapping("/concepts")
    TaxonomyResponse concepts() {
        var parents = registry.parents().stream().map(TaxonomyController::view).toList();
        var leaves = registry.leaves().stream().map(TaxonomyController::view).toList();
        return new TaxonomyResponse(registry.version(), parents, leaves);
    }

    private static TaxonomyConceptView view(Concept concept) {
        return new TaxonomyConceptView(concept.key().value(), concept.label(),
                concept.parentKey() == null ? null : concept.parentKey().value(),
                concept.definition());
    }

    record TaxonomyResponse(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) int version,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<TaxonomyConceptView> parents,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<TaxonomyConceptView> leaves) {
    }

    record TaxonomyConceptView(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String key,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String label,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true) String parentKey,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true) String definition) {
    }
}
