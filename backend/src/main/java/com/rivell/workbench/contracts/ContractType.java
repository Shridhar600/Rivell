package com.rivell.workbench.contracts;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;

/**
 * Declared contract type. Codes match the taxonomy's contract-type codes
 * ({@code legal_concept.contract_types}); NDA and OTHER stay valid for profiles declared before
 * the wider set. Model states receive the {@link #label()}, never the code.
 */
public enum ContractType {
    NDA("Non-disclosure agreement", null, Area.CONFIDENTIALITY),
    MNDA("Mutual non-disclosure agreement", "NDA", Area.CONFIDENTIALITY),
    ONDA("One-way non-disclosure agreement", "NDA", Area.CONFIDENTIALITY),
    MSA("Master services agreement", null, Area.COMMERCIAL),
    SAAS("SaaS subscription agreement", null, Area.COMMERCIAL),
    DPA("Data processing agreement", null, Area.DATA_PROTECTION, "MSA"),
    SOW("Statement of work", null, Area.COMMERCIAL, "MSA"),
    SWL("Software licence agreement", null, Area.COMMERCIAL),
    SUPPLY("Supply agreement", null, Area.COMMERCIAL),
    SERVICES("Services agreement", null, Area.COMMERCIAL),
    RESELLER("Reseller or distribution agreement", null, Area.COMMERCIAL),
    ORDER_FORM("Order form", null, Area.COMMERCIAL, "MSA", "SAAS"),
    AMENDMENT("Amendment", null, Area.COMMERCIAL, "MSA"),
    OTHER("Other agreement", null, null);

    /**
     * The subject-matter area of an agreement type. Types in one area share positions, while
     * literal wording belongs to the type it was written for. A type the product does not
     * model has no area.
     */
    public enum Area {
        COMMERCIAL,
        CONFIDENTIALITY,
        DATA_PROTECTION
    }

    private final String label;
    private final String familyCode;
    private final Area area;
    private final Set<String> parentTypeCodes;

    ContractType(String label, String familyCode, Area area, String... parentTypeCodes) {
        this.label = label;
        this.familyCode = familyCode;
        this.area = area;
        this.parentTypeCodes = Set.of(parentTypeCodes);
    }

    public String label() {
        return label;
    }

    /** The family this type belongs to (a mutual NDA is an NDA); itself when it has none. */
    public ContractType family() {
        return familyCode == null ? this : valueOf(familyCode);
    }

    /** The area this type belongs to; empty for a type the product does not model. */
    public Optional<Area> area() {
        return Optional.ofNullable(area);
    }

    /** Whether this type is made under a parent agreement (a statement of work, an amendment, ...). */
    public boolean madeUnderParentType() {
        return !parentTypeCodes.isEmpty();
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
     *
     * <p>This is the literal-wording scope. For an unmodelled contract it is not the whole
     * rule: only wording whose record scope is empty applies there (and a position's own scope
     * never excludes it).
     */
    public Set<String> recordApplicableCodes() {
        var codes = new LinkedHashSet<>(applicableCodes());
        codes.addAll(parentTypeCodes);
        return Set.copyOf(codes);
    }

    /**
     * Types a position's own "Applies to" may name for it to apply to a contract of this type:
     * every type in the same {@link Area}, plus {@link #recordApplicableCodes()}. A playbook
     * writes "MSA" loosely for general commercial terms, so an MSA payment position governs a
     * reseller or supply agreement too. It does not reach another area except through the
     * parent types a child is made under, and a data processing agreement still reads the
     * master agreement it sits under.
     *
     * <p>For an unmodelled contract this set is not the rule either: any position applies
     * whatever its own scope says.
     */
    public Set<String> positionApplicableCodes() {
        var codes = new LinkedHashSet<>(recordApplicableCodes());
        Arrays.stream(values())
                .filter(type -> type.area != null && type.area == area)
                .forEach(type -> codes.add(type.name()));
        return Set.copyOf(codes);
    }

    /**
     * Whether some modelled contract type exists that both records may govern, so a
     * contradiction between them is material. A record governs a type when its artifact's
     * declared types admit the type (an empty declaration is a wildcard) and its own scope
     * admits it by the rule above (an empty scope is a wildcard). Positions share their area;
     * literal wording meets only on a type both were written for, parent types and the NDA
     * family included. A type the product does not model is not a type two records can share:
     * an unmodelled contract accepts every position, so counting it would compare every pair.
     */
    public static boolean recordsMeetInAContractType(
            boolean literalWording,
            Set<String> firstDeclared, Set<String> firstScope,
            Set<String> secondDeclared, Set<String> secondScope) {
        var firstDeclaredKeys = keys(firstDeclared);
        var secondDeclaredKeys = keys(secondDeclared);
        var firstScopeKeys = keys(firstScope);
        var secondScopeKeys = keys(secondScope);
        return Arrays.stream(values())
                .filter(type -> type != OTHER)
                .anyMatch(type -> declaredAdmits(firstDeclaredKeys, type)
                        && declaredAdmits(secondDeclaredKeys, type)
                        && scopeAdmits(literalWording, firstScopeKeys, type)
                        && scopeAdmits(literalWording, secondScopeKeys, type));
    }

    /**
     * Codes are matched the way retrieval matches them: case and punctuation do not distinguish
     * a declared type from its code (an uploaded manifest may be stored verbatim).
     */
    private static Set<String> keys(Set<String> codes) {
        return codes.stream()
                .filter(code -> code != null)
                .map(ContractType::key)
                .collect(java.util.stream.Collectors.toSet());
    }

    private static String key(String code) {
        return code.toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }

    private static boolean declaredAdmits(Set<String> declaredKeys, ContractType type) {
        return declaredKeys.isEmpty() || type.applicableCodes().stream()
                .map(ContractType::key).anyMatch(declaredKeys::contains);
    }

    private static boolean scopeAdmits(
            boolean literalWording, Set<String> scopeKeys, ContractType type) {
        if (scopeKeys.isEmpty()) {
            return true;
        }
        var applicable = literalWording
                ? type.recordApplicableCodes() : type.positionApplicableCodes();
        return applicable.stream().map(ContractType::key).anyMatch(scopeKeys::contains);
    }
}
