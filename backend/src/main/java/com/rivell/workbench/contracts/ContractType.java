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
    DPA("Data processing agreement", null, "MSA"),
    SOW("Statement of work", null, "MSA"),
    SWL("Software licence agreement", null),
    SUPPLY("Supply agreement", null),
    SERVICES("Services agreement", null),
    RESELLER("Reseller or distribution agreement", null),
    ORDER_FORM("Order form", null, "MSA", "SAAS"),
    AMENDMENT("Amendment", null, "MSA"),
    OTHER("Other agreement", null);

    private final String label;
    private final String familyCode;
    private final Set<String> parentTypeCodes;

    ContractType(String label, String familyCode, String... parentTypeCodes) {
        this.label = label;
        this.familyCode = familyCode;
        this.parentTypeCodes = Set.of(parentTypeCodes);
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

    /**
     * Types a record's own "Applies to" may name for it to apply to a contract of this type:
     * {@link #applicableCodes()}, plus the {@link #parentTypeCodes} the type is made under. A
     * statement of work, a data processing agreement, an order form and an amendment all form
     * part of the master agreement (or SaaS agreement) they sit under, so a position stated for
     * the parent (governing law, data export) also governs the child that departs from it.
     */
    public Set<String> recordApplicableCodes() {
        var codes = new LinkedHashSet<>(applicableCodes());
        codes.addAll(parentTypeCodes);
        return Set.copyOf(codes);
    }
}
