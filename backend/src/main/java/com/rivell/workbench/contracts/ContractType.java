package com.rivell.workbench.contracts;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Declared contract type. Codes match the taxonomy's contract-type codes
 * ({@code legal_concept.contract_types}); NDA and OTHER stay valid for profiles declared before
 * the wider set. Model states receive the {@link #label()}, never the code.
 */
public enum ContractType {
    NDA("Non-disclosure agreement", null),
    MNDA("Mutual non-disclosure agreement", "NDA"),
    ONDA("One-way non-disclosure agreement", "NDA"),
    MSA("Master services agreement", null),
    SAAS("SaaS subscription agreement", null),
    DPA("Data processing agreement", null),
    SOW("Statement of work", null),
    SWL("Software licence agreement", null),
    OTHER("Other agreement", null);

    private final String label;
    private final String familyCode;

    ContractType(String label, String familyCode) {
        this.label = label;
        this.familyCode = familyCode;
    }

    public String label() {
        return label;
    }

    /** The family this type belongs to (a mutual NDA is an NDA); itself when it has none. */
    public ContractType family() {
        return familyCode == null ? this : valueOf(familyCode);
    }

    /**
     * Declared types under which a knowledge source applies to a contract of this type: the type
     * itself, its family, and for a family type every member (a source declared for NDA applies
     * to a mutual NDA, and a source for a mutual NDA applies to a contract declared only as NDA).
     */
    public Set<String> applicableCodes() {
        var codes = new LinkedHashSet<String>();
        codes.add(name());
        codes.add(family().name());
        Arrays.stream(values())
                .filter(type -> type != this && type.family() == this)
                .forEach(type -> codes.add(type.name()));
        return Set.copyOf(codes);
    }
}
