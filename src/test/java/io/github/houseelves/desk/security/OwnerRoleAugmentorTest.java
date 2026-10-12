package io.github.houseelves.desk.security;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;

import org.junit.jupiter.api.Test;

import io.quarkus.security.runtime.QuarkusPrincipal;
import io.quarkus.security.runtime.QuarkusSecurityIdentity;

class OwnerRoleAugmentorTest {

    final OwnerRoleAugmentor augmentor = new OwnerRoleAugmentor(Set.of("phillip-kruger"));

    boolean isOwner(String login) {
        var identity = QuarkusSecurityIdentity.builder().setPrincipal(new QuarkusPrincipal(login)).build();
        return augmentor.augment(identity, null).await().indefinitely().hasRole(OwnerRoleAugmentor.OWNER);
    }

    @Test
    void allowedLoginIsOwnerCaseInsensitively() {
        assertTrue(isOwner("phillip-kruger"));
        assertTrue(isOwner("Phillip-Kruger"));
    }

    @Test
    void otherAccountsAreNot() {
        // The Great Southern account must not get in.
        assertFalse(isOwner("phillipkruger"));
        assertFalse(isOwner("the-bin-chicken"));
    }
}
