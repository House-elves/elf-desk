package io.github.houseelves.desk.security;

import java.util.Optional;
import java.util.Set;

import io.quarkus.security.identity.AuthenticationRequestContext;
import io.quarkus.security.identity.IdentityProvider;
import io.quarkus.security.identity.IdentityProviderManager;
import io.quarkus.security.identity.SecurityIdentity;
import io.quarkus.security.identity.request.AuthenticationRequest;
import io.quarkus.security.identity.request.BaseAuthenticationRequest;
import io.quarkus.security.runtime.QuarkusPrincipal;
import io.quarkus.security.runtime.QuarkusSecurityIdentity;
import io.quarkus.vertx.http.runtime.security.ChallengeData;
import io.quarkus.vertx.http.runtime.security.HttpAuthenticationMechanism;
import io.smallrye.mutiny.Uni;
import io.vertx.core.http.Cookie;
import io.vertx.ext.web.RoutingContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * Signs you in with the desk key's cookie (see {@link DeskKey}): with it you are the desk's GitHub account and
 * have the {@code owner} role. Without it, pages go to {@code /welcome}, which says how to open the desk.
 */
@ApplicationScoped
public class DeskKeyAuth implements HttpAuthenticationMechanism {

    public static final String OWNER = "owner";
    public static final String COOKIE = "elfdesk-key";

    @Override
    public Uni<SecurityIdentity> authenticate(RoutingContext rc, IdentityProviderManager providers) {
        Cookie c = rc.request().getCookie(COOKIE);
        if (c == null) return Uni.createFrom().optional(Optional.empty());
        return providers.authenticate(new KeyRequest(c.getValue()));
    }

    @Override
    public Uni<ChallengeData> getChallenge(RoutingContext rc) {
        boolean page = "GET".equals(rc.request().method().name())
                && !"websocket".equalsIgnoreCase(rc.request().getHeader("Upgrade"));
        return Uni.createFrom().item(page ? new ChallengeData(302, "Location", "/welcome") : new ChallengeData(401));
    }

    @Override
    public Set<Class<? extends AuthenticationRequest>> getCredentialTypes() {
        return Set.of(KeyRequest.class);
    }

    public static final class KeyRequest extends BaseAuthenticationRequest {
        final String value;

        KeyRequest(String value) {
            this.value = value;
        }
    }

    @ApplicationScoped
    public static class KeyProvider implements IdentityProvider<KeyRequest> {

        @Inject
        DeskKey key;

        @Inject
        GhAccount account;

        @Override
        public Class<KeyRequest> getRequestType() {
            return KeyRequest.class;
        }

        @Override
        public Uni<SecurityIdentity> authenticate(KeyRequest request, AuthenticationRequestContext context) {
            if (!key.isKey(request.value)) return Uni.createFrom().nullItem();
            // Asking gh for the login can take a moment the first time: not on the event loop.
            return context.runBlocking(() -> QuarkusSecurityIdentity.builder()
                    .setPrincipal(new QuarkusPrincipal(account.login()))
                    .addRole(OWNER)
                    .build());
        }
    }
}
