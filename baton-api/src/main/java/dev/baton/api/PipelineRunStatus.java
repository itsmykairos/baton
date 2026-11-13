package dev.baton.api;

public enum PipelineRunStatus {
    CREATED,
    RUNNING,
    COMPLETED,
    FAILED,
    TIMED_OUT,
    CANCELLED
}