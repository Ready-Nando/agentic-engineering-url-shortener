package com.example.sdlc.artifact;

/** Well-known artifact keys: the contract between capabilities, gates and the engine. */
public final class ArtifactKeys {

    public static final String REQUIREMENT_SPEC = "requirement/spec";
    public static final String CLARIFICATIONS = "requirement/clarifications";
    public static final String CODEBASE_MODEL = "codebase/model";
    public static final String BASELINE_VERIFICATION = "verification/baseline";
    public static final String IMPACT_ANALYSIS = "analysis/impact";
    public static final String PLAN_PROPOSAL = "plan/proposal";
    public static final String DESIGN_OVERVIEW = "design/overview";
    public static final String API_CONTRACT = "design/api-contract";
    public static final String DATA_MODEL = "design/data-model";
    public static final String DECISION_PREFIX = "decision/";
    public static final String CHANGES_PREFIX = "changes/";
    public static final String VERIFICATION_REPORT = "verification/report";
    public static final String SECURITY_REVIEW = "review/security";
    public static final String API_COMPATIBILITY = "review/api-compatibility";
    public static final String RELEASE_READINESS = "release/readiness";
    public static final String ABANDONED_CHANGES = "outcome/abandoned-changes";

    private ArtifactKeys() {
    }

    public static String changesOf(String taskId) {
        return CHANGES_PREFIX + taskId;
    }
}
