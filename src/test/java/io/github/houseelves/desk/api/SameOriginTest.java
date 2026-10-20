package io.github.houseelves.desk.api;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class SameOriginTest {

    @Test
    void onlyTheDesksOwnPages() {
        assertTrue(SameOrigin.sameHost("http://houseelves.lan:7777", "houseelves.lan:7777"));
        assertTrue(SameOrigin.sameHost("http://192.0.2.10:7777", "192.0.2.10:7777"));
        assertFalse(SameOrigin.sameHost("https://evil.example", "houseelves.lan:7777"));
        assertFalse(SameOrigin.sameHost("http://houseelves.lan:7778", "houseelves.lan:7777"));
        assertFalse(SameOrigin.sameHost("null", "houseelves.lan:7777"));
        assertFalse(SameOrigin.sameHost("http://houseelves.lan:7777", null));
    }
}
