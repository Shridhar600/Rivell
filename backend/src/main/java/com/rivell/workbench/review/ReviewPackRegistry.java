package com.rivell.workbench.review;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.rivell.workbench.contracts.LegalConcept;

import static com.rivell.workbench.contracts.LegalConcept.CONFIDENTIALITY;
import static com.rivell.workbench.contracts.LegalConcept.GOVERNING_LAW;
import static com.rivell.workbench.contracts.LegalConcept.INDEMNIFICATION;
import static com.rivell.workbench.contracts.LegalConcept.LIMITATION_OF_LIABILITY;
import static com.rivell.workbench.contracts.LegalConcept.TERM_TERMINATION;

/** Code-owned pack registry; M7 MVP ships one versioned constant set. */
public final class ReviewPackRegistry {

    public static final String VERSION = "rivell-review-pack-1";
    public static final String MSA_PACK = "MSA_PACK";
    public static final String NDA_PACK = "NDA_PACK";

    private static final java.util.LinkedHashMap<String, ReviewPack> PACKS = build();

    public java.util.List<ReviewPack> all() {
        return java.util.List.copyOf(PACKS.values());
    }

    public Optional<ReviewPack> find(String key) {
        if (key == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(PACKS.get(key.trim()));
    }

    private static java.util.LinkedHashMap<String, ReviewPack> build() {
        var msaExpectations = new LinkedHashMap<LegalConcept, CheckExpectation>();
        msaExpectations.put(LIMITATION_OF_LIABILITY, CheckExpectation.REQUIRED);
        msaExpectations.put(CONFIDENTIALITY, CheckExpectation.REQUIRED);
        msaExpectations.put(TERM_TERMINATION, CheckExpectation.EXPECTED);
        msaExpectations.put(INDEMNIFICATION, CheckExpectation.EXPECTED);
        msaExpectations.put(GOVERNING_LAW, CheckExpectation.OPTIONAL);

        var msa = new ReviewPack(MSA_PACK, "Master Services Agreement Pack",
                "Completeness, liability-cap, confidentiality-survival, and "
                        + "carve-out consistency review for MSAs.",
                VERSION, msaExpectations,
                Map.of(LIMITATION_OF_LIABILITY, CheckMechanism.LIABILITY_CAP),
                List.of(
                        new ReviewPack.CrossClauseRule(
                                CheckMechanism.CONFIDENTIALITY_SURVIVAL,
                                CONFIDENTIALITY, TERM_TERMINATION),
                        new ReviewPack.CrossClauseRule(
                                CheckMechanism.CARVE_OUT_CONSISTENCY,
                                LIMITATION_OF_LIABILITY, INDEMNIFICATION)));

        var ndaExpectations = new LinkedHashMap<LegalConcept, CheckExpectation>();
        ndaExpectations.put(CONFIDENTIALITY, CheckExpectation.REQUIRED);
        ndaExpectations.put(TERM_TERMINATION, CheckExpectation.EXPECTED);
        ndaExpectations.put(LIMITATION_OF_LIABILITY, CheckExpectation.OPTIONAL);
        ndaExpectations.put(INDEMNIFICATION, CheckExpectation.OPTIONAL);
        ndaExpectations.put(GOVERNING_LAW, CheckExpectation.OPTIONAL);

        var nda = new ReviewPack(NDA_PACK, "Mutual NDA Pack",
                "Confidentiality completeness and survival review for NDAs.",
                VERSION, ndaExpectations,
                Map.of(LIMITATION_OF_LIABILITY, CheckMechanism.LIABILITY_CAP),
                List.of(new ReviewPack.CrossClauseRule(
                        CheckMechanism.CONFIDENTIALITY_SURVIVAL,
                        CONFIDENTIALITY, TERM_TERMINATION)));

        var packs = new java.util.LinkedHashMap<String, ReviewPack>();
        packs.put(msa.key(), msa);
        packs.put(nda.key(), nda);
        return packs;
    }
}
