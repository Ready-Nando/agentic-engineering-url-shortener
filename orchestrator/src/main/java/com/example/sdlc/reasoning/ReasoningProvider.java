package com.example.sdlc.reasoning;

import com.example.sdlc.plan.PlanProposal;
import com.example.sdlc.reasoning.ReasoningRequests.ChangeRequest;
import com.example.sdlc.reasoning.ReasoningRequests.DesignRequest;
import com.example.sdlc.reasoning.ReasoningRequests.ImpactRequest;
import com.example.sdlc.reasoning.ReasoningRequests.PlanRequest;
import com.example.sdlc.reasoning.ReasoningRequests.RequirementRequest;

/**
 * The probabilistic edge of the system. Implementations only <em>propose</em> structured content; they never
 * decide workflow transitions, approvals or policy outcomes. Failures should be thrown as
 * {@link ReasoningException} so the engine can classify them.
 */
public interface ReasoningProvider {

    String name();

    RequirementSpec analyzeRequirement(RequirementRequest request);

    ImpactSeeds proposeImpactSeeds(ImpactRequest request);

    PlanProposal proposePlan(PlanRequest request);

    DesignProposal proposeDesign(DesignRequest request);

    ChangeProposal proposeChanges(ChangeRequest request);
}
