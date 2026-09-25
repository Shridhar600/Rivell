package com.rivell.workbench.review.api;

import java.util.List;

/** Currently selectable contract packs (G-M7-3); pack-less remains a null pack key. */
public record ReviewPackCatalogView(List<PackView> packs) {

    public record PackView(String key, String displayName, String summary, String version) {
    }
}
