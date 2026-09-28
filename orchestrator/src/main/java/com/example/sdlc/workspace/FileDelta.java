package com.example.sdlc.workspace;

/** Resolved effect of a change on one file; {@code before}/{@code after} are null for created/deleted files. */
public record FileDelta(FileChange.Op op, String path, String before, String after) {

    public boolean existedBefore() {
        return before != null;
    }
}
