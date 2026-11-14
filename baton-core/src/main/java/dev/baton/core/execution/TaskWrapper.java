package dev.baton.core.execution;

import java.util.concurrent.Callable;

/** Wraps a task before it is submitted, for example to propagate context into it. */
@FunctionalInterface
interface TaskWrapper {

    TaskWrapper NONE = new TaskWrapper() {
        @Override
        public <T> Callable<T> wrap(Callable<T> task) {
            return task;
        }
    };

    <T> Callable<T> wrap(Callable<T> task);
}
