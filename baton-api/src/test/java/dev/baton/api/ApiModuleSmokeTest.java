package dev.baton.api;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ApiModuleSmokeTest {

    @Test
    void testRuntimeIsAvailable() {
        assertThat(Runtime.version().feature()).isGreaterThanOrEqualTo(21);
    }
}
