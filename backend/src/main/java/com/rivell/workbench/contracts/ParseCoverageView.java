package com.rivell.workbench.contracts;

import java.util.List;
import java.util.Optional;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(name = "ParseCoverageResponse", description = "How much of the uploaded file's text the parsed structure holds, from an "
        + "independent read of the raw file. checked is false for versions parsed before the "
        + "check existed or when it could not run.")
public record ParseCoverageView(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) boolean checked,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED,
                description = "False when the check fell back to whole-paragraph matching")
        boolean exact,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) int sourceWords,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) int capturedWords,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED,
                description = "Structure words with no source counterpart")
        int extraWords,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<LocationTotalView> totals,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED,
                description = "Source text the structure does not hold, in source order")
        List<GapView> gaps) {

    public record LocationTotalView(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String location,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) int sourceWords,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) int capturedWords) {
    }

    public record GapView(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String part,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED,
                    description = "BODY, TABLE, TEXT_BOX, HEADER, FOOTER, FOOTNOTE, ENDNOTE or COMMENT")
            String location,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) int words,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String excerpt) {
    }

    /** API view shared by contract versions and knowledge artifacts. */
    public static ParseCoverageView of(Optional<ParseCoverage> coverage) {
        return coverage.map(c -> new ParseCoverageView(true, c.exact(), c.sourceWords(),
                        c.capturedWords(), c.extraWords(),
                        c.totals().stream().map(t -> new LocationTotalView(t.location().name(),
                                t.sourceWords(), t.capturedWords())).toList(),
                        c.gaps().stream().map(g -> new GapView(g.part(), g.location().name(),
                                g.words(), g.excerpt())).toList()))
                .orElseGet(() -> new ParseCoverageView(false, false, 0, 0, 0, List.of(), List.of()));
    }
}
