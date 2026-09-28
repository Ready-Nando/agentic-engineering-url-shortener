package com.example.sdlc.codebase;

import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * One top-level type of the module.
 *
 * @param typeName      fully qualified name
 * @param path          relative to the module root, forward slashes
 * @param dependsOn     other project types this one references
 * @param annotations   simple names of the annotations on the type declaration
 * @param pathLiterals  string literals that look like URL paths ({@code "/links/{code}"} in a MockMvc request),
 *                      without query or fragment and normalised like endpoint paths; sorted, distinct
 */
public record SourceUnit(
        String typeName,
        String simpleName,
        String path,
        Layer layer,
        boolean test,
        Set<String> dependsOn,
        Set<String> tablesRead,
        Set<String> tablesWritten,
        List<String> annotations,
        List<String> pathLiterals) {

    public SourceUnit {
        dependsOn = sorted(dependsOn);
        tablesRead = sorted(tablesRead);
        tablesWritten = sorted(tablesWritten);
        annotations = List.copyOf(annotations);
        // Missing from models serialized before path literals were indexed, which a resumed run may read back.
        pathLiterals = pathLiterals == null ? List.of() : List.copyOf(new TreeSet<>(pathLiterals));
    }

    public boolean touchesTables() {
        return !tablesRead.isEmpty() || !tablesWritten.isEmpty();
    }

    private static Set<String> sorted(Set<String> values) {
        return Collections.unmodifiableSortedSet(new TreeSet<>(values));
    }
}
