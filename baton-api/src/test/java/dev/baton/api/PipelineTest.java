package dev.baton.api;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.*;

class PipelineTest {

    @Test
    void asStageShouldReturnNestedPipelineAdapter() {
        Pipeline pipeline = new Pipeline() {

            @Override
            public PipelineResult run(ContextView input) {
                throw new UnsupportedOperationException();
            }

            @Override
            public PipelineRun start(ContextView input) {
                throw new UnsupportedOperationException();
            }
        };

        Stage stage = pipeline.asStage();

        assertThat(stage)
                .isInstanceOf(NestedPipelineStage.class);
    }

}