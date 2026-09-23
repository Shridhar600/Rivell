package com.rivell.workbench.knowledge.api;

import java.util.Map;

public record ReviewEditRequest(String contentText, Map<String, String> payloadColumns, String note) {
}
