package io.github.houseelves.desk.security;

import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

import org.eclipse.microprofile.config.inject.ConfigProperty;

import io.quarkus.security.identity.AuthenticationRequestContext;
import io.quarkus.security.identity.SecurityIdentity;
import io.quarkus.security.identity.SecurityIdentityAugmentor;
import io.quarkus.security.runtime.QuarkusSecurityIdentity;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * GitHub tokens carry no roles. A signed-in identity whose GitHub login is on
 * {@code elfdesk.allowed-logins} (case-insensitive) gets the {@code owner}
 * role; everyone else is authenticated but gets nothing.
 */
@ApplicationScoped
public class OwnerRoleAugmentor implements SecurityIdentityAugmentor {

    public static final String OWNER = "owner";

    private final Set<String> allowed;

    public OwnerRoleAugmentor(@ConfigProperty(name = "elfdesk.allowed-logins") Set<String> allowedLogins) {
        this.allowed = allowedLogins.stream()
                .map(l -> l.trim().toLowerCase(Locale.ROOT))
                .filter(l -> !l.isEmpty())
                .collect(Collectors.toUnmodifiableSet());
    }

    @Override
    public Uni<SecurityIdentity> augment(SecurityIdentity identity, AuthenticationRequestContext context) {
        if (identity.isAnonymous() || !isAllowed(identity.getPrincipal().getName())) {
            return Uni.createFrom().item(identity);
        }
        return Uni.createFrom().item(QuarkusSecurityIdentity.builder(identity).addRole(OWNER).build());
    }

    boolean isAllowed(String login) {
        return login != null && allowed.contains(login.toLowerCase(Locale.ROOT));
    }
}
