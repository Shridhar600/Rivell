package com.rivell.workbench.contracts;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Deterministic contract extraction (S2–S5): metadata, parties with declared roles,
 * defined terms with their references, and internal cross-reference resolution.
 * Pure code — no model calls. Anything not exactly derivable stays absent for
 * bounded semantics or human review.
 */
public final class DeterministicContractExtractors {

    private static final DateTimeFormatter US_DATE =
            DateTimeFormatter.ofPattern("MMMM d, yyyy", Locale.ENGLISH);

    private static final String ENTITY_SUFFIX = "Inc|LLC|Ltd|GmbH|Corp|Corporation|Company|Limited";
    private static final Pattern CAPITALIZED_RUN = Pattern.compile(
            "([A-Z][A-Za-z0-9&.,'’\\-]*(?:[ ]+[A-Z][A-Za-z0-9&.,'’\\-]*)*)");
    private static final Pattern ROLE_LABEL_PATTERN = Pattern.compile(
            "\\(\\s*(?:the\\s+)?\"([^\"]{2,60})\"\\s*\\)");
    private static final Pattern REFERENCE_PATTERN = Pattern.compile(
            "\\b(Sections?|Clauses?|Articles?|Schedules?|Exhibits?|Annex(?:es)?)\\s+"
                    + "([0-9]+(?:\\.[0-9]+)*|[A-Z0-9]+)");
    private static final Pattern EXTERNAL_DOCUMENT_PATTERN = Pattern.compile(
            "\\b(the )?([A-Z][A-Za-z ]{3,40}(?:Agreement|DPA|Addendum))\\s+dated\\s+"
                    + "([A-Z][a-z]+ \\d{1,2}, \\d{4})");
    private static final Pattern DEFINITION_START_PATTERN = Pattern.compile(
            "[\"“]([A-Z][^\"“”]{1,60})[\"”]\\s+"
                    + "(?:means|shall mean|refers to|refers solely to)\\s+");
    private static final List<String> DEFINITION_SEPARATORS =
            List.of("; and", ", and", ";", ",", " and");
    private static final Pattern EFFECTIVE_DATE_PATTERN = Pattern.compile(
            "(?:as of|dated)\\s+([A-Z][a-z]+ \\d{1,2}, \\d{4})");
    private static final Pattern GOVERNING_LAW_PATTERN = Pattern.compile(
            "governed by the laws of ([^.,;]+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern LEADING_NUMBER_PATTERN = Pattern.compile(
            "^(\\d+(?:\\.\\d+)*)[.)]?\\s+");

    public List<MetadataFact> metadata(
            UUID versionId, List<ContractRegionSegmenter.Region> regions, List<DocumentNode> nodes) {
        var facts = new ArrayList<MetadataFact>();

        nodes.stream()
                .filter(node -> node.nodeType() == DocumentNodeType.TITLE)
                .map(DocumentNode::text)
                .filter(text -> text != null && !text.isBlank())
                .findFirst()
                .ifPresent(title -> facts.add(new MetadataFact(UUID.randomUUID(), versionId,
                        MetadataField.AGREEMENT_TITLE, title.strip(),
                        Interpretation.deterministic("agreementTitle", title.strip(),
                                titleNode(nodes)), null, null)));

        for (var node : nodes) {
            if (node.text() == null || node.text().isBlank()) {
                continue;
            }
            effectiveDate(node.text()).ifPresent(date -> addOnce(facts, versionId,
                    MetadataField.EFFECTIVE_DATE, date, "effectiveDate", node));
            governingLaw(node.text()).ifPresent(law -> addOnce(facts, versionId,
                    MetadataField.GOVERNING_LAW, law, "governingLaw", node));
            termOf(node.text()).ifPresent(term -> addOnce(facts, versionId,
                    MetadataField.TERM, term, "term", node));
        }
        return facts;
    }

    public List<ContractParty> parties(
            UUID versionId, List<ContractRegionSegmenter.Region> regions, List<DocumentNode> nodes) {
        var parties = new ArrayList<ContractParty>();
        var seen = new java.util.HashSet<String>();

        for (var region : regions) {
            if (!region.isPreamble()) {
                continue;
            }
            for (var nodeId : region.textNodeIds()) {
                var text = textOf(nodes, nodeId);
                if (text == null || text.isBlank()) {
                    continue;
                }
                parties.addAll(partiesFromPreamble(versionId, nodeId, text, seen));
            }
        }
        return parties;
    }

    public List<DefinedTerm> definedTerms(
            UUID versionId, List<ContractRegionSegmenter.Region> regions, List<DocumentNode> nodes) {
        var texts = proseTexts(nodes);
        var definitions = new ArrayList<DefinitionHit>();

        for (var entry : texts.entrySet()) {
            definitions.addAll(definitionsIn(entry.getKey(), entry.getValue()));
        }

        var countByTerm = new HashMap<String, Integer>();
        for (var hit : definitions) {
            countByTerm.merge(normalize(hit.term()), 1, Integer::sum);
        }

        var terms = new ArrayList<DefinedTerm>();
        for (var hit : definitions) {
            var duplicate = countByTerm.get(normalize(hit.term())) > 1;
            var references = new ArrayList<UUID>();
            var phrase = Pattern.compile("\\b" + Pattern.quote(hit.term()) + "\\b");
            for (var entry : texts.entrySet()) {
                if (entry.getKey().equals(hit.definingNodeId())) {
                    continue;
                }
                if (phrase.matcher(entry.getValue()).find()) {
                    references.add(entry.getKey());
                }
            }
            terms.add(new DefinedTerm(UUID.randomUUID(), versionId, hit.term(), hit.definition(),
                    hit.definingNodeId(), List.of(hit.definingNodeId()), references, duplicate));
        }
        return terms;
    }

    public List<ContractReference> references(
            UUID versionId, List<ContractRegionSegmenter.Region> regions, List<DocumentNode> nodes) {
        var paragraphs = paragraphTexts(nodes);
        var headings = nodes.stream()
                .filter(node -> node.nodeType() == DocumentNodeType.HEADING)
                .toList();
        var references = new ArrayList<ContractReference>();

        for (var entry : paragraphs.entrySet()) {
            var text = entry.getValue();
            var external = EXTERNAL_DOCUMENT_PATTERN.matcher(text);
            while (external.find()) {
                references.add(new ContractReference(UUID.randomUUID(), versionId,
                        entry.getKey(),
                        external.group().replaceFirst("^the ", "").strip(),
                        ReferenceKind.EXTERNAL, ReferenceStatus.EXTERNAL, null, List.of()));
            }
            var matcher = REFERENCE_PATTERN.matcher(text);
            var emitted = new ArrayList<String>();
            while (matcher.find()) {
                var kind = referenceKind(matcher.group(1));
                var fullText = matcher.group(1) + " " + matcher.group(2);
                if (emitted.contains(fullText)) {
                    continue;
                }
                emitted.add(fullText);
                var candidates = candidatesFor(kind, matcher.group(2), headings, nodes);
                var status = candidates.size() == 1 ? ReferenceStatus.RESOLVED
                        : candidates.isEmpty() ? ReferenceStatus.BROKEN
                                : ReferenceStatus.AMBIGUOUS;
                references.add(new ContractReference(UUID.randomUUID(), versionId,
                        entry.getKey(), fullText, kind, status,
                        candidates.size() == 1 ? candidates.get(0) : null, candidates));
            }
        }
        return references;
    }

    /** The definition text between the verb and the next definition's opening quote. */
    private List<DefinitionHit> definitionsIn(UUID nodeId, String text) {
        var matcher = DEFINITION_START_PATTERN.matcher(text);
        var heads = new ArrayList<DefinitionHead>();
        while (matcher.find()) {
            heads.add(new DefinitionHead(matcher.group(1).strip(), matcher.start(),
                    matcher.end()));
        }
        var hits = new ArrayList<DefinitionHit>();
        var seenTerms = new java.util.HashSet<String>();
        for (var i = 0; i < heads.size(); i++) {
            var head = heads.get(i);
            var end = i + 1 < heads.size() ? heads.get(i + 1).start() : text.length();
            var definition = definitionText(text.substring(head.end(), end), end == text.length());
            if (definition.isBlank()) {
                continue; // adjacent definition starts leave no text for this one
            }
            if (!seenTerms.add(head.term())) {
                // one row per (term, defining node) is what the schema allows: first wording wins
                continue;
            }
            hits.add(new DefinitionHit(head.term(), definition, nodeId));
        }
        return hits;
    }

    private String definitionText(String slice, boolean last) {
        if (last) {
            return slice.strip();
        }
        var trimmed = slice.stripTrailing();
        for (var separator : DEFINITION_SEPARATORS) {
            if (trimmed.endsWith(separator)) {
                return trimmed.substring(0, trimmed.length() - separator.length())
                        .stripTrailing();
            }
        }
        return trimmed;
    }

    private record DefinitionHit(String term, String definition, UUID definingNodeId) {
    }

    private record DefinitionHead(String term, int start, int end) {
    }

    private record MatchedEntity(int start, String name) {
    }

    private record RoleHit(int start, String label) {
    }

    private List<ContractParty> partiesFromPreamble(
            UUID versionId, UUID nodeId, String text, java.util.Set<String> seen) {
        var entities = new ArrayList<MatchedEntity>();
        var entityMatcher = CAPITALIZED_RUN.matcher(text);
        while (entityMatcher.find()) {
            var name = entityMatcher.group(1).strip()
                    .replaceAll("[,;:]+$", "")
                    .strip();
            if (!isLikelyEntityName(name) || precededByArticle(text, entityMatcher.start())) {
                continue;
            }
            if (seen.add(normalize(name))) {
                entities.add(new MatchedEntity(entityMatcher.start(), name));
            }
        }

        var roles = new ArrayList<RoleHit>();
        var roleMatcher = ROLE_LABEL_PATTERN.matcher(text);
        while (roleMatcher.find()) {
            roles.add(new RoleHit(roleMatcher.start(), roleMatcher.group(1)));
        }

        var assigned = new HashMap<RoleHit, MatchedEntity>();
        for (var role : roles) {
            MatchedEntity best = null;
            for (var entity : entities) {
                if (entity.start() < role.start() && !assigned.containsValue(entity)
                        && (best == null || entity.start() > best.start())) {
                    best = entity;
                }
            }
            if (best != null && roleOf(role.label()) != null) {
                assigned.put(role, best);
            }
        }

        var aliasesByEntity = new HashMap<MatchedEntity, List<String>>();
        var aliasMatcher = Pattern.compile(
                "(?:may(?: each)? be referred to as|referred to as) (?:a |an )?\"([^\"]{2,40})\"")
                .matcher(text);
        while (aliasMatcher.find()) {
            var alias = aliasMatcher.group(1).strip();
            if (alias.matches("(?i)part(y|ies)")) {
                continue;
            }
            MatchedEntity owner = null;
            var ownerIndex = -1;
            for (var entity : entities) {
                var lastIndex = text.lastIndexOf(entity.name(), aliasMatcher.start());
                if (lastIndex >= 0 && lastIndex > ownerIndex) {
                    ownerIndex = lastIndex;
                    owner = entity;
                }
            }
            if (owner == null) {
                for (var entity : entities) {
                    if (entity.start() < aliasMatcher.start()) {
                        owner = entity;
                    }
                }
            }
            if (owner != null) {
                aliasesByEntity.computeIfAbsent(owner, ignored -> new ArrayList<>())
                        .add(alias);
            }
        }

        var parties = new ArrayList<ContractParty>();
        var docOrder = new int[] {0};
        for (var entity : entities) {
            var order = docOrder[0]++;
            var declared = assigned.entrySet().stream()
                    .filter(entry -> entry.getValue().equals(entity))
                    .map(Map.Entry::getKey)
                    .map(hit -> roleOf(hit.label()))
                    .filter(java.util.Objects::nonNull)
                    .findFirst();
            var machineRole = declared.orElseGet(() -> order == 0
                    ? PartyRole.PARTY_A : PartyRole.PARTY_B);
            parties.add(new ContractParty(UUID.randomUUID(), versionId, entity.name(),
                    normalize(entity.name()), aliasesByEntity.getOrDefault(entity, List.of()),
                    List.of(Interpretation.deterministic("roles",
                            machineRole.name(), List.of(nodeId))),
                    List.of(machineRole), PartyCorrection.NONE, null));
        }
        return parties;
    }

    private boolean precededByArticle(String text, int start) {
        if (start == 0) {
            return false;
        }
        var before = text.substring(Math.max(0, start - 4), start)
                .toLowerCase(java.util.Locale.ROOT);
        return before.matches(".*\\b(a|an|the)\\s?");
    }

    private boolean isLikelyEntityName(String name) {
        if (!name.matches(".*\\b(?:" + ENTITY_SUFFIX + ")\\.?$")) {
            return false;
        }
        var suffixWord = name.substring(name.lastIndexOf(' ') + 1);
        if (name.equals(suffixWord)) {
            return false;
        }
        return name.indexOf(' ') > 0;
    }

    private List<UUID> candidatesFor(
            ReferenceKind kind, String label, List<DocumentNode> headings,
            List<DocumentNode> nodes) {
        if (kind == ReferenceKind.SECTION) {
            var candidates = new ArrayList<UUID>();
            for (var node : nodes) {
                var labelMatch = node.numberingLabel() != null
                        && node.numberingLabel().equals(label);
                var leadingMatch = node.text() != null
                        && leadingNumber(node.text()).map(leading -> leading.equals(label))
                                .orElse(false);
                if (labelMatch || leadingMatch) {
                    candidates.add(node.id());
                }
            }
            return candidates;
        }
        var keyword = switch (kind) {
            case SCHEDULE -> "Schedule";
            case EXHIBIT -> "Exhibit";
            default -> "Annex";
        };
        return headings.stream()
                .filter(heading -> heading.text() != null
                        && heading.text().strip().startsWith(keyword + " " + label))
                .map(node -> node.id())
                .toList();
    }

    private Optional<String> leadingNumber(String text) {
        var matcher = LEADING_NUMBER_PATTERN.matcher(text.strip());
        return matcher.find() ? Optional.of(matcher.group(1)) : Optional.empty();
    }

    private ReferenceKind referenceKind(String keyword) {
        var singular = keyword.endsWith("s") ? keyword.substring(0, keyword.length() - 1) : keyword;
        return switch (singular) {
            case "Section", "Clause", "Article" -> ReferenceKind.SECTION;
            case "Schedule" -> ReferenceKind.SCHEDULE;
            case "Exhibit" -> ReferenceKind.EXHIBIT;
            default -> ReferenceKind.ANNEX;
        };
    }

    private PartyRole roleOf(String label) {
        var key = label.toUpperCase(Locale.ROOT).replace(" ", "_");
        try {
            return PartyRole.valueOf(key);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private Optional<String> effectiveDate(String text) {
        var matcher = EFFECTIVE_DATE_PATTERN.matcher(text);
        if (!matcher.find()) {
            return Optional.empty();
        }
        try {
            return Optional.of(LocalDate.parse(matcher.group(1), US_DATE).toString());
        } catch (java.time.format.DateTimeParseException e) {
            return Optional.empty();
        }
    }

    private Optional<String> governingLaw(String text) {
        var matcher = GOVERNING_LAW_PATTERN.matcher(text);
        if (!matcher.find()) {
            return Optional.empty();
        }
        var law = matcher.group(1).strip();
        if (law.startsWith("the State of ") || law.startsWith("State of ")) {
            return Optional.of("US");
        }
        var lower = law.toLowerCase(Locale.ROOT);
        if (lower.contains("england") || lower.contains("wales") || lower.contains("scotland")
                || lower.contains("united kingdom") || lower.contains("northern ireland")) {
            return Optional.of("UK");
        }
        if (lower.contains("united states")) {
            return Optional.of("US");
        }
        return Optional.empty();
    }

    private Optional<String> termOf(String text) {
        var matcher = Pattern.compile("initial term of ([a-z]+) \\((\\d+)\\) months").matcher(text);
        if (matcher.find()) {
            return Optional.of(matcher.group(2) + " months");
        }
        matcher = Pattern.compile("continues for a period of (\\d+) \\([a-z ]+\\) years?")
                .matcher(text);
        if (matcher.find()) {
            return Optional.of(matcher.group(1) + " years");
        }
        return Optional.empty();
    }

    private void addOnce(
            List<MetadataFact> facts, UUID versionId, MetadataField field, String value,
            String interpretationField, DocumentNode node) {
        var present = facts.stream().anyMatch(fact -> fact.field() == field);
        if (!present) {
            facts.add(new MetadataFact(UUID.randomUUID(), versionId, field, value,
                    Interpretation.deterministic(interpretationField, value, List.of(node.id())),
                    null, null));
        }
    }

    private List<UUID> titleNode(List<DocumentNode> nodes) {
        return nodes.stream()
                .filter(node -> node.nodeType() == DocumentNodeType.TITLE)
                .map(node -> node.id())
                .findFirst()
                .map(java.util.List::of)
                .orElseGet(List::of);
    }

    private String textOf(List<DocumentNode> nodes, UUID nodeId) {
        for (var node : nodes) {
            if (node.id().equals(nodeId)) {
                return node.text();
            }
        }
        return null;
    }

    private Map<UUID, String> paragraphTexts(List<DocumentNode> nodes) {
        var texts = new LinkedHashMap<UUID, String>();
        for (var node : nodes) {
            if (node.nodeType() == DocumentNodeType.PARAGRAPH && node.text() != null
                    && !node.text().isBlank()) {
                texts.put(node.id(), node.text());
            }
        }
        return texts;
    }

    private Map<UUID, String> proseTexts(List<DocumentNode> nodes) {
        var texts = new LinkedHashMap<UUID, String>();
        for (var node : nodes) {
            var prose = node.nodeType() == DocumentNodeType.PARAGRAPH
                    || node.nodeType() == DocumentNodeType.HEADING;
            if (prose && node.text() != null && !node.text().isBlank()) {
                texts.put(node.id(), node.text());
            }
        }
        return texts;
    }

    private String normalize(String value) {
        return value.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").strip();
    }
}
