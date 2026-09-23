package com.rivell.workbench.intelligence;

/**
 * Port to an external semantic model provider. Implementations live behind this boundary;
 * the current adapter is TypeSafe/Jev. Domain code never sees provider transport types.
 */
public interface SemanticDecisionProvider {

    String id();

    ProviderExecutionResult execute(DecisionExecutionRequest request);
}
