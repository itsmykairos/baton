package dev.baton.api;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ApiModuleSmokeTest {

    @Test
    void testRuntimeIsAvailable() {
        assertThat(Runtime.version().feature()).isGreaterThanOrEqualTo(21);
    }
}
