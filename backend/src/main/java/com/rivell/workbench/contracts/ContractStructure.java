package com.rivell.workbench.contracts;

import java.util.List;
import java.util.UUID;

public record ContractStructure(
        UUID contractId,
        int versionNumber,
        String parserVersion,
        ParseStatus parseStatus,
        List<String> parseWarnings,
        List<DocumentNode> nodes) {

    public int nodeCount() {
        return nodes.size();
    }
}
