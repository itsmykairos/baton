package dev.baton.core.builder;

import dev.baton.api.Pipeline;
import dev.baton.api.Stage;
import dev.baton.api.StageOptions;
import dev.baton.core.definition.StageNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

final class DefaultParallelBuilder  implements Pipeline.ParallelBuilder{

    private final List<StageNode> stages = new ArrayList<>();
    private boolean closed;

    @Override
    public DefaultParallelBuilder stage(String id, Stage stage) {
        return stage(id, stage, StageOptions.defaults());
    }

    @Override
   public DefaultParallelBuilder stage(
            String id,
            Stage stage,
            StageOptions options
    ) {
        ensureOpen();

        stages.add(new StageNode(
                id,
                stage,
                options
        ));

        return this;
    }

    List<StageNode> finish() {
        ensureOpen();
        closed = true;

        if (stages.isEmpty()) {
            throw new IllegalArgumentException(
                    "A parallel layer must contain at least one stage"
            );
        }

        return List.copyOf(stages);
    }

    private void ensureOpen() {
        if (closed) {
            throw new IllegalStateException(
                    "Parallel builder is no longer active"
            );
        }
    }
}