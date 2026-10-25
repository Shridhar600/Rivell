package com.rivell.workbench.contracts;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
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

    /**
     * Legal-form suffixes, matched case-insensitively so "plc" and "AG" are read as written.
     * The dotted forms allow their dots to be dropped ("B.V." or "BV"), and the word boundary
     * in front keeps short ones (AB, SE, AG, Oy) out of longer words such as "Grab" or "House".
     */
    private static final String ENTITY_SUFFIX = "(?i:Inc|LLC|Ltd|Limited|Corp|Corporation|Company"
            + "|GmbH|AG|SE|Oy|AB|A/S|plc|N\\.?V\\.?|B\\.?V\\.?|S\\.?A\\.?S\\.?|S\\.?A\\.?"
            + "|S\\.?p\\.?A\\.?|S\\.?r\\.?l\\.?|K\\.?K\\.?)";
    /**
     * A capitalised run of name words. A trailing lowercase "plc" is part of the run, because
     * otherwise the name would stop before its own legal form.
     */
    private static final Pattern CAPITALIZED_RUN = Pattern.compile(
            "([A-Z][A-Za-z0-9&.,'’\\-]*(?:[ ]+[A-Z][A-Za-z0-9&.,'’\\-]*)*"
                    + "(?:[ ]+(?i:plc)\\.?)?)");
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
    /**
     * A term named by apposition at the end of the sentence that describes it:
     * "The parties wish to evaluate a relationship (the \"Purpose\")." The sentence before the
     * bracket is the definition.
     */
    private static final Pattern APPOSITIVE_DEFINITION_PATTERN = Pattern.compile(
            "\\(\\s*the\\s+[\"“]([A-Z][^\"“”]{1,60})[\"”]\\s*\\)");
    private static final Pattern LIST_LABEL =
            Pattern.compile("^(?:\\(?[A-Z0-9]{1,3}[.)]|\\([a-z0-9]{1,3}\\))\\s+");
    /** Party, document and schedule aliases: named in brackets but not concepts to define. */
    private static final Set<String> DEFINITION_ALIASES = Set.of(
            "AGREEMENT", "CONTRACT", "SOW", "MSA", "DPA", "NDA", "DEED", "AMENDMENT",
            "STATEMENT OF WORK", "ORDER FORM", "SCHEDULE", "ANNEX", "EXHIBIT", "APPENDIX",
            "ATTACHMENT", "DISCLOSER", "RECIPIENT", "CLIENT", "AGENCY", "CUSTOMER",
            "SUPPLIER", "CONTRACTOR", "LICENSOR", "LICENSEE", "PURCHASER", "VENDOR",
            "COMPANY", "PARTY", "PARTIES");
    private static final int MAX_APPOSITIVE_DEFINITION = 500;
    private static final List<String> DEFINITION_SEPARATORS =
            List.of("; and", ", and", ";", ",", " and");
    private static final Pattern EFFECTIVE_DATE_PATTERN = Pattern.compile(
            "(?:as of|dated)\\s+([A-Z][a-z]+ \\d{1,2}, \\d{4})");
    /**
     * A jurisdiction name as written: an optional article and country prefix, then capitalised
     * name words. The words are deliberately case-sensitive inside an otherwise
     * case-insensitive pattern, so a capture stops at the first non-name word instead of
     * spanning the rest of a sentence.
     */
    private static final String JURISDICTION_NAME_WORD =
            "(?-i:(?!(?i:This|That|The|Agreement|Section|Clause|Paragraph|Parties?|Party|"
                    + "Company|Contract|Schedule|Exhibit|Annex|Notwithstanding|Subject|"
                    + "Governing|Governed|Terms?|Conditions?|Customer|Supplier|All|Any|Each|"
                    + "Disputes?|Arbitration|Tribunal|Courts?|Jurisdiction|Forum|Venue|Shall|"
                    + "Will|Must|May|Be|Is|Are|Was|Were|Arising|Hereunder|Herein|Hereof|"
                    + "Thereof|Hereto|Hereby|Resolved|Laws?|Provision|Provisions)\\b)"
                    + "[\\p{Lu}][\\p{L}\\p{M}'’\\-]+)";
    private static final String JURISDICTION_COMPOUND =
            "(?-i:(?:United\\s+Kingdom\\s+of\\s+Great\\s+Britain\\s+and\\s+Northern\\s+Ireland"
                    + "|England\\s+and\\s+Wales|Antigua\\s+and\\s+Barbuda"
                    + "|Trinidad\\s+and\\s+Tobago|Bosnia\\s+and\\s+Herzegovina))";
    private static final String JURISDICTION_PREFIX =
            "(?:(?:State|Commonwealth|Province|Territory|Republic|Kingdom)\\s+of\\s+)?";
    private static final String JURISDICTION_NAME_GUARD =
            "(?!n\\s*[./]\\s*a\\b|na\\b|tbd\\b|tba\\b|tbc\\b|as\\s+agreed\\b|a\\s+matter\\b|"
                    + "at\\s+the\\s+discretion\\b|to\\s+be\\s+agreed\\b|mutually\\s+agreed\\b|"
                    + "not\\b|none\\b|set\\s+out\\b|specified\\s+in\\b|stated\\s+in\\b|see\\b|"
                    + "as\\s+follows\\b|to\\s+be\\b|per\\b)";
    private static final String JURISDICTION_NAME =
            "[\"']?\\s*(?:the\\s+)?" + JURISDICTION_PREFIX + "(?:the\\s+)?"
                    + JURISDICTION_NAME_GUARD
                    + "(?:" + JURISDICTION_COMPOUND + "|" + JURISDICTION_NAME_WORD
                    + "(?:\\s+(?:of\\s+)?" + JURISDICTION_NAME_WORD + "){0,7})"
                    + "(?:\\s*[\"'])?";
    private static final Pattern GOVERNING_LAW_DANGLING_PAIR =
            Pattern.compile("^\\s+and\\s*$", Pattern.CASE_INSENSITIVE);
    private static final int GOVERNING_LAW_STRONG = 2;
    private static final int GOVERNING_LAW_WEAK = 1;
    private static final List<GoverningLawPattern> GOVERNING_LAW_PATTERNS = List.of(
            new GoverningLawPattern(Pattern.compile(
                    "\\bgoverned by(?:,? and construed in accordance with,?)?\\s+"
                            + "the laws? of\\s+(" + JURISDICTION_NAME + ")",
                    Pattern.CASE_INSENSITIVE), GOVERNING_LAW_STRONG),
            new GoverningLawPattern(Pattern.compile(
                    "\\bconstrued(?:,? and enforced,?)? in accordance with "
                            + "the laws? of\\s+(" + JURISDICTION_NAME + ")",
                    Pattern.CASE_INSENSITIVE), GOVERNING_LAW_WEAK),
            new GoverningLawPattern(Pattern.compile(
                    "\\bthe laws? of\\s+(" + JURISDICTION_NAME + ")"
                            + "(?:\\s+shall)?\\s+governs?\\b",
                    Pattern.CASE_INSENSITIVE), GOVERNING_LAW_STRONG),
            new GoverningLawPattern(Pattern.compile(
                    "\\bsubject to the laws? of\\s+(" + JURISDICTION_NAME + ")",
                    Pattern.CASE_INSENSITIVE), GOVERNING_LAW_WEAK),
            new GoverningLawPattern(Pattern.compile(
                    "\\bgoverning law(?:\\s+and\\s+jurisdiction)?[\"']?\\s*"
                            + "(?::|\\bis\\b|\\bshall be\\b)\\s*"
                            + "(?:the laws? of\\s+)?(" + JURISDICTION_NAME + ")",
                    Pattern.CASE_INSENSITIVE), GOVERNING_LAW_STRONG, true));
    private static final List<String> GOVERNING_LAW_STOPS = List.of(
            " without regard", " excluding", " and the parties", " and the courts",
            " and any dispute", " and each party");
    private static final List<String> GOVERNING_LAW_UNSET_PREFIXES = List.of(
            "not ", "none", "set out", "as set out", "specified in", "stated in", "see ",
            "as follows", "to be ", "per ", "n/a", "tbd", "tba", "tbc", "as agreed",
            "a matter for", "at the discretion", "to be agreed", "mutually agreed");
    private static final List<String> GOVERNING_LAW_INCOMPLETE = List.of(
            "new", "state", "commonwealth", "province", "territory", "united", "republic",
            "kingdom");
    private static final Pattern GOVERNING_LAW_TRAILING_CONJUNCTION =
            Pattern.compile("\\s+(?:and|or|but)$", Pattern.CASE_INSENSITIVE);
    private static final Pattern GOVERNING_LAW_TRAILING_CONNECTOR =
            Pattern.compile("\\s+(?:of|the|in|to|for|with|under|by|at|as|from|a|an)$",
                    Pattern.CASE_INSENSITIVE);
    private static final Pattern GOVERNING_LAW_TRAILING_INCOMPLETE =
            Pattern.compile("\\s+(?:new|south|north)$", Pattern.CASE_INSENSITIVE);
    private static final Pattern GOVERNING_LAW_COURTS_WORDING = Pattern.compile(
            "\\b(?:court|courts|jurisdiction|dispute|disputes|arbitration|tribunal|forum|venue)\\b",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern GOVERNING_LAW_NAME_WORD =
            Pattern.compile("\\b\\p{Lu}[\\p{L}\\p{M}'’\\-]*\\b");
    private static final Pattern SECTION_REFERENCE =
            Pattern.compile("\\bthis\\s+(?:section|clause|paragraph)\\b",
                    Pattern.CASE_INSENSITIVE);
    private static final int GOVERNING_LAW_MAX_LENGTH = 80;
    private static final int GOVERNING_LAW_SCAN_LIMIT = 20_000;

    private record GoverningLawPattern(Pattern pattern, int rank, boolean lineScoped) {

        GoverningLawPattern(Pattern pattern, int rank) {
            this(pattern, rank, false);
        }
    }

    private record GoverningLawCandidate(String value, int rank, int start) {
    }
    private static final Pattern LEADING_NUMBER_PATTERN = Pattern.compile(
            "^(\\d+(?:\\.\\d+)*)[.)]?\\s+");
    private static final Pattern TERM_WORD_MONTHS =
            Pattern.compile("initial term of ([a-z]+) \\((\\d+)\\) months");
    private static final Pattern TERM_NUMERIC_YEARS =
            Pattern.compile("continues for a period of (\\d+) \\([a-z ]+\\) years?");

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
            termOf(node.text()).ifPresent(term -> addOnce(facts, versionId,
                    MetadataField.TERM, term, "term", node));
        }
        governingLawFact(versionId, regions).ifPresent(facts::add);
        return facts;
    }

    /**
     * Governing law is read from the joined text of each region, never from one node, so a
     * name split across paragraphs is whole. The strongest candidate wins; document order
     * breaks a tie. Provenance is the region the winning candidate came from.
     */
    private Optional<MetadataFact> governingLawFact(
            UUID versionId, List<ContractRegionSegmenter.Region> regions) {
        GoverningLawCandidate best = null;
        ContractRegionSegmenter.Region bestRegion = null;
        for (var region : regions) {
            var candidate = governingLawCandidateIn(region.contentText())
                    .filter(found -> found.rank() > 0);
            if (candidate.isPresent() && (best == null || candidate.get().rank() > best.rank())) {
                best = candidate.get();
                bestRegion = region;
            }
        }
        if (best == null) {
            return Optional.empty();
        }
        var sources = new ArrayList<UUID>(bestRegion.textNodeIds());
        if (!bestRegion.isPreamble() && bestRegion.headingNodeId() != null
                && !sources.contains(bestRegion.headingNodeId())) {
            sources.add(bestRegion.headingNodeId());
        }
        return Optional.of(new MetadataFact(UUID.randomUUID(), versionId,
                MetadataField.GOVERNING_LAW, best.value(),
                Interpretation.deterministic("governingLaw", best.value(), sources), null, null));
    }

    public List<ContractParty> parties(
            UUID versionId, List<ContractRegionSegmenter.Region> regions, List<DocumentNode> nodes) {
        var parties = new ArrayList<ContractParty>();
        var seen = new HashSet<String>();

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
        var statedTerms = new HashSet<String>();

        for (var entry : texts.entrySet()) {
            var stated = definitionsIn(entry.getKey(), entry.getValue());
            for (var hit : stated) {
                statedTerms.add(normalize(hit.term()));
            }
            definitions.addAll(stated);
        }
        for (var entry : texts.entrySet()) {
            definitions.addAll(appositiveDefinitionsIn(
                    entry.getKey(), entry.getValue(), statedTerms));
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
        var seenTerms = new HashSet<String>();
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

    private List<DefinitionHit> appositiveDefinitionsIn(
            UUID nodeId, String text, Set<String> statedTerms) {
        var hits = new ArrayList<DefinitionHit>();
        var seen = new HashSet<String>();
        var matcher = APPOSITIVE_DEFINITION_PATTERN.matcher(text);
        while (matcher.find()) {
            var term = matcher.group(1).strip();
            if (!seen.add(term) || statedTerms.contains(normalize(term))
                    || isNonConceptAlias(term)) {
                continue;
            }
            var definition = text.substring(sentenceStartBefore(text, matcher.start()),
                    matcher.start()).strip();
            definition = stripListLabel(definition)
                    .replaceAll("[\\s,;:]+$", "").strip();
            if (definition.split("\\s+").length < 4
                    || definition.chars().noneMatch(Character::isLowerCase)) {
                continue;
            }
            if (definition.length() > MAX_APPOSITIVE_DEFINITION) {
                definition = definition.substring(0, MAX_APPOSITIVE_DEFINITION).strip();
            }
            hits.add(new DefinitionHit(term, definition, nodeId));
        }
        return hits;
    }

    /** The start of the sentence or list item the bracket closes. */
    private static int sentenceStartBefore(String text, int end) {
        var start = 0;
        for (int index = 0; index < end; index++) {
            var character = text.charAt(index);
            if (character == '\n' || character == ';') {
                start = index + 1;
            } else if (character == '.'
                    && (index + 1 >= end || Character.isWhitespace(text.charAt(index + 1)))) {
                start = index + 1;
            }
        }
        return start;
    }

    private static String stripListLabel(String value) {
        var matcher = LIST_LABEL.matcher(value);
        return matcher.find() ? value.substring(matcher.end()) : value;
    }

    private static boolean isNonConceptAlias(String term) {
        var upper = term.toUpperCase(Locale.ROOT);
        return DEFINITION_ALIASES.contains(upper)
                || upper.endsWith(" PARTY") || upper.endsWith(" PARTIES");
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

    static Optional<String> governingLawIn(String text) {
        return governingLawCandidateIn(text)
                .filter(candidate -> candidate.rank() > 0)
                .map(GoverningLawCandidate::value);
    }

    private static Optional<GoverningLawCandidate> governingLawCandidateIn(String text) {
        if (text == null || text.isBlank()) {
            return Optional.empty();
        }
        var normalized = normalizeGoverningLawText(text);
        var scanned = normalized.length() > GOVERNING_LAW_SCAN_LIMIT
                ? normalized.substring(0, GOVERNING_LAW_SCAN_LIMIT) : normalized;
        GoverningLawCandidate best = null;
        for (var governingPattern : GOVERNING_LAW_PATTERNS) {
            var matcher = governingPattern.pattern().matcher(scanned);
            while (matcher.find()) {
                if (runsIntoScanCap(normalized, scanned, matcher.end())
                        || danglingPairConjunction(scanned, matcher.end())) {
                    continue;
                }
                var law = cleanGoverningLaw(matcher.group(1));
                if (law.isEmpty()) {
                    continue;
                }
                var sentence = sentenceAround(scanned, matcher.start(),
                        governingPattern.lineScoped());
                var rank = SECTION_REFERENCE.matcher(sentence).find() ? 0 : governingPattern.rank();
                var candidate = new GoverningLawCandidate(law.get(), rank, matcher.start());
                if (best == null || candidate.rank() > best.rank()
                        || (candidate.rank() == best.rank() && candidate.start() < best.start())) {
                    best = candidate;
                }
            }
        }
        return Optional.ofNullable(best);
    }

    private static boolean runsIntoScanCap(String normalized, String scanned, int matchEnd) {
        return normalized.length() > scanned.length() && matchEnd >= scanned.length();
    }

    private static boolean danglingPairConjunction(String text, int matchEnd) {
        return GOVERNING_LAW_DANGLING_PAIR.matcher(text.substring(matchEnd)).matches();
    }

    private static String normalizeGoverningLawText(String text) {
        return text.replace('\u00A0', ' ')
                .replace('\u2007', ' ')
                .replace('\u202F', ' ')
                .replace('\u201C', '"')
                .replace('\u201D', '"')
                .replace('\u2018', '\'')
                .replace('\u2019', '\'');
    }

    /**
     * The sentence around a match; used to demote "this Section" wording. A line break is
     * whitespace in prose, so a sentence wrapped across paragraph nodes keeps its context.
     * A label heading is line-scoped so the body's "this Section" cannot demote the label.
     */
    private static String sentenceAround(String text, int matchStart, boolean lineScoped) {
        var start = 0;
        for (var index = Math.min(matchStart - 1, text.length() - 1); index >= 0; index--) {
            if (isSentenceBoundary(text, index, lineScoped)) {
                start = index + 1;
                break;
            }
        }
        var end = text.length();
        for (var index = Math.max(matchStart, 0); index < text.length(); index++) {
            if (isSentenceBoundary(text, index, lineScoped)) {
                end = index;
                break;
            }
        }
        return text.substring(start, end);
    }

    /** A decimal or section number such as "7.1" is not a sentence boundary. */
    private static boolean isSentenceBoundary(String text, int index, boolean lineScoped) {
        var character = text.charAt(index);
        if (character == ';' || character == '!' || character == '?'
                || (lineScoped && character == '\n')) {
            return true;
        }
        if (character != '.') {
            return false;
        }
        return !(index > 0 && index + 1 < text.length()
                && Character.isDigit(text.charAt(index - 1))
                && Character.isDigit(text.charAt(index + 1)));
    }

    private static Optional<String> cleanGoverningLaw(String captured) {
        var law = stripQuotes(captured == null ? "" : captured);
        if (law.isEmpty()) {
            return Optional.empty();
        }
        var lower = law.toLowerCase(Locale.ROOT);
        var end = law.length();
        for (var terminator : new char[] {'.', ';', ','}) {
            var index = law.indexOf(terminator);
            if (index >= 0) {
                end = Math.min(end, index);
            }
        }
        for (var stop : GOVERNING_LAW_STOPS) {
            var index = lower.indexOf(stop);
            if (index >= 0) {
                end = Math.min(end, index);
            }
        }
        law = law.substring(0, end).strip();
        if (law.equalsIgnoreCase("the")) {
            law = "";
        } else if (law.regionMatches(true, 0, "the ", 0, 4)) {
            law = law.substring(4).strip();
        }
        law = law.replaceAll("\\s+", " ").strip();
        law = GOVERNING_LAW_TRAILING_CONJUNCTION.matcher(law).replaceFirst("");
        if (law.length() > GOVERNING_LAW_MAX_LENGTH) {
            law = law.substring(0, GOVERNING_LAW_MAX_LENGTH);
        }
        law = law.replaceAll("[\\s.,;:]+$", "");
        law = GOVERNING_LAW_TRAILING_CONJUNCTION.matcher(law).replaceFirst("");
        law = law.replaceAll("[\\s.,;:]+$", "");
        if (law.isEmpty() || looksUnset(law)
                || GOVERNING_LAW_COURTS_WORDING.matcher(law).find()
                || GOVERNING_LAW_TRAILING_CONNECTOR.matcher(law).find()
                || GOVERNING_LAW_TRAILING_INCOMPLETE.matcher(law).find()
                || GOVERNING_LAW_INCOMPLETE.contains(law.toLowerCase(Locale.ROOT))
                || !GOVERNING_LAW_NAME_WORD.matcher(law).find()) {
            return Optional.empty();
        }
        return Optional.of(law);
    }

    private static String stripQuotes(String value) {
        var stripped = value.strip();
        while (!stripped.isEmpty() && isQuote(stripped.charAt(0))) {
            stripped = stripped.substring(1).strip();
        }
        while (!stripped.isEmpty() && isQuote(stripped.charAt(stripped.length() - 1))) {
            stripped = stripped.substring(0, stripped.length() - 1).strip();
        }
        return stripped;
    }

    private static boolean isQuote(char character) {
        return character == '"' || character == '\'' || character == '\u201C'
                || character == '\u201D' || character == '\u2018' || character == '\u2019';
    }

    private static boolean looksUnset(String law) {
        var lower = law.toLowerCase(Locale.ROOT);
        return GOVERNING_LAW_UNSET_PREFIXES.stream().anyMatch(lower::startsWith);
    }

    private Optional<String> termOf(String text) {
        var matcher = TERM_WORD_MONTHS.matcher(text);
        if (matcher.find()) {
            return Optional.of(matcher.group(2) + " months");
        }
        matcher = TERM_NUMERIC_YEARS.matcher(text);
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
