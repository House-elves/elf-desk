package io.github.houseelves.desk.ignore;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

@QuarkusTest
class IgnoreServiceTest {

    @Inject
    IgnoreService ignores;

    @BeforeEach
    @Transactional
    void clean() {
        IgnoredItem.deleteAll();
    }

    @Test
    void newActivityBringsItBackWithTheReasonAndIgnoringAgainResets() {
        ignores.ignore("quarkusio/quarkus#1", "me");
        assertTrue(ignores.isIgnored("quarkusio/quarkus#1"));
        assertTrue(ignores.ignoredSince().containsKey("quarkusio/quarkus#1"));

        ignores.bringBack("quarkusio/quarkus#1", "You were mentioned");
        assertFalse(ignores.isIgnored("quarkusio/quarkus#1"));
        assertFalse(ignores.ignoredSince().containsKey("quarkusio/quarkus#1"));
        assertEquals("You were mentioned", ignores.recentlyReturned().get("quarkusio/quarkus#1"));

        ignores.ignore("quarkusio/quarkus#1", "me");
        assertTrue(ignores.isIgnored("quarkusio/quarkus#1"));
        assertTrue(ignores.recentlyReturned().isEmpty());

        ignores.unignore("quarkusio/quarkus#1");
        assertFalse(ignores.isIgnored("quarkusio/quarkus#1"));
    }
}
