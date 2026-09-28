package com.example.sdlc.reasoning;

import java.util.List;

/** Architecture/design output: decisions with alternatives, API contract changes and data model changes. */
public record DesignProposal(
        String summary,
        List<Component> components,
        List<DecisionRecord> decisions,
        List<ApiOperation> apiOperations,
        List<TableChange> dataModel) {

    public DesignProposal {
        components = components == null ? List.of() : List.copyOf(components);
        decisions = decisions == null ? List.of() : List.copyOf(decisions);
        apiOperations = apiOperations == null ? List.of() : List.copyOf(apiOperations);
        dataModel = dataModel == null ? List.of() : List.copyOf(dataModel);
    }

    public record Component(String name, String responsibility, String location, boolean isNew) {
    }

    public record DecisionRecord(String id, String title, String context, List<String> alternatives, String choice,
                                 String rationale, Impact impact) {
        public DecisionRecord {
            alternatives = alternatives == null ? List.of() : List.copyOf(alternatives);
        }
    }

    /** {@code change} is NEW, MODIFIED or UNCHANGED relative to the current API. */
    public record ApiOperation(String method, String path, String summary, List<Integer> responses, String change) {
        public ApiOperation {
            responses = responses == null ? List.of() : List.copyOf(responses);
        }
    }

    public record TableChange(String table, String change, List<Column> columns) {
        public TableChange {
            columns = columns == null ? List.of() : List.copyOf(columns);
        }
    }

    /** {@code personalData}: NONE, PSEUDONYMISED or RAW. */
    public record Column(String name, String type, boolean nullable, String personalData) {
    }
}
