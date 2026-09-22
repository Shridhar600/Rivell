package com.rivell.workbench.knowledge.api;

import java.util.List;
import java.util.UUID;

public record PublishRequest(List<UUID> candidateIds, String note) {
}
