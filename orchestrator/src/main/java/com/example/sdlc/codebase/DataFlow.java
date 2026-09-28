package com.example.sdlc.codebase;

import java.util.List;

/**
 * A shortest dependency path from an endpoint's handler type to a type that touches a table.
 *
 * <p>This is a type-level over-approximation, not call-level data flow: the path follows type references,
 * and a type's table access is whatever all of its SQL reads or writes. A flow therefore says that the
 * handler type can reach SQL with that access to the table, not that this endpoint's handler method runs it;
 * endpoints handled by the same type get the same flows.
 *
 * @param path   type names from the handler type (first) to the type issuing the SQL (last)
 * @param access {@code READ} or {@code WRITE}; there is one flow per reachable (table, access) pair
 */
public record DataFlow(Endpoint entry, List<String> path, String table, String access) {

    public DataFlow {
        path = List.copyOf(path);
    }
}
