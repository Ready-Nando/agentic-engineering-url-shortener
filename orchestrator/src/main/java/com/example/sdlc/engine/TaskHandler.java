package com.example.sdlc.engine;

/**
 * Behaviour behind a capability. Runs on a worker thread against an immutable {@link TaskContext}; it must
 * not mutate shared state. Side effects on the workspace are expressed as a proposed change set in the result.
 */
@FunctionalInterface
public interface TaskHandler {

    TaskResult execute(TaskContext context) throws Exception;
}
