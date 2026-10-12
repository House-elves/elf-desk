package io.github.houseelves.desk.runner;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;

import org.junit.jupiter.api.Test;

class RunnerHubTest {

    RunnerHub hub(String token) {
        RunnerHub h = new RunnerHub();
        h.token = Optional.ofNullable(token);
        return h;
    }

    @Test
    void onlyTheConfiguredTokenGetsIn() {
        assertTrue(hub("s3cret").tokenMatches("s3cret"));
        assertFalse(hub("s3cret").tokenMatches("s3cre"));
        assertFalse(hub("s3cret").tokenMatches(""));
    }

    @Test
    void versionsCompareNumerically() {
        assertTrue(RunnerHub.older("0.1.0", "0.2.0"));
        assertTrue(RunnerHub.older("", "0.2.0"));
        assertFalse(RunnerHub.older("0.2.0", "0.2.0"));
        assertFalse(RunnerHub.older("0.10.0", "0.2.0"));
    }

    @Test
    void noTokenConfiguredRefusesEveryone() {
        assertFalse(hub(null).tokenMatches(""));
        assertFalse(hub("  ").tokenMatches("  "));
    }
}
