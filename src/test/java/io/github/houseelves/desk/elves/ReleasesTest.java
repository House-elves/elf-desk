package io.github.houseelves.desk.elves;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ReleasesTest {

    @Test
    void aReleaseIsNewerByItsNumbersNotItsText() {
        assertTrue(Releases.older("0.6.6", "v0.6.7"));
        assertTrue(Releases.older("v0.5.1", "v0.10.0"));
        assertTrue(Releases.older("0.6", "v0.6.1"));
        assertFalse(Releases.older("0.6.7", "v0.6.7"));
        assertFalse(Releases.older("v0.7.0", "v0.6.9"));
        // A build from source, or GitHub not saying: never behind.
        assertFalse(Releases.older("1.0.0-SNAPSHOT", "v0.6.7"));
        assertFalse(Releases.older("dev", "v0.5.2"));
        assertFalse(Releases.older("0.6.6", null));
    }
}
