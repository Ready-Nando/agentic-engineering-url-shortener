package com.example.sdlc.workspace;

/**
 * One proposed file operation. Edits are exact search/replace so a proposal made against stale content fails
 * loudly instead of being silently merged.
 */
public record FileChange(Op op, String path, String content, String find, String replace) {

    public enum Op { CREATE, EDIT, DELETE }

    public static FileChange create(String path, String content) {
        return new FileChange(Op.CREATE, path, content, null, null);
    }

    public static FileChange edit(String path, String find, String replace) {
        return new FileChange(Op.EDIT, path, null, find, replace);
    }

    public static FileChange delete(String path) {
        return new FileChange(Op.DELETE, path, null, null, null);
    }
}
