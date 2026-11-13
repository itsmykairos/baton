package dev.baton.core.execution;

import dev.baton.api.ContextView;
import dev.baton.api.StageContext;
import dev.baton.api.StageTasks;

import java.util.Objects;

final class DefaultStageContext implements StageContext {

    private final ContextView context;
    private final StageTasks tasks;

    DefaultStageContext(ContextView context, StageTasks tasks) {
        this.context = Objects.requireNonNull(context, "context");
        this.tasks = Objects.requireNonNull(tasks, "tasks");
    }

    @Override
    public ContextView context() {
        return context;
    }

    @Override
    public StageTasks tasks() {
        return tasks;
    }
}