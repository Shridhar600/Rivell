/**
 * Centralized semantic intelligence. All external model access lives here — no provider
 * client types outside this module. Domain modules see only provider-neutral contracts:
 * SemanticDecisionService, DecisionSpec, QuestionPack, state and execution types.
 */
@org.springframework.modulith.ApplicationModule(
        allowedDependencies = {},
        type = org.springframework.modulith.ApplicationModule.Type.CLOSED)
package com.rivell.workbench.intelligence;
