/**
 * The legal-topic vocabulary shared by contracts, knowledge and review ("the taxonomy is
 * the bridge"). Topics are data in {@code legal_concept}, never code constants; this
 * module owns loading them and classifying a text section against them.
 */
@org.springframework.modulith.ApplicationModule(
        displayName = "Taxonomy",
        allowedDependencies = {"intelligence"})
package com.rivell.workbench.taxonomy;
