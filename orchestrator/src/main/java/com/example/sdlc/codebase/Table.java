package com.example.sdlc.codebase;

import java.util.List;

/**
 * A table as it looks after all migrations ran.
 *
 * @param name      lower-case, without schema
 * @param columns   lower-case, in declaration order, including columns added by later migrations
 * @param definedIn path of the migration that created the table
 */
public record Table(String name, List<String> columns, String definedIn) {

    public Table {
        columns = List.copyOf(columns);
    }
}
