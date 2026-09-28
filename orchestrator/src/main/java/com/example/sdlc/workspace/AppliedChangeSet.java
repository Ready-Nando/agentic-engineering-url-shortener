package com.example.sdlc.workspace;

import java.util.List;

/**
 * Record of a change set that was written to the workspace, holding what is needed to compensate it:
 * the pre-image of every file and the hash of what was written (to detect tampering before restoring).
 */
public record AppliedChangeSet(String id, String taskId, int attempt, String summary, List<AppliedFile> files) {

    public AppliedChangeSet {
        files = List.copyOf(files);
    }

    public record AppliedFile(String path, FileChange.Op op, String preImage, String postHash) {
    }

    public List<String> paths() {
        return files.stream().map(AppliedFile::path).toList();
    }
}
