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
 * Signs you in with a session cookie (see {@link DeskSessions}): with it you are the desk's GitHub account and have
 * the {@code owner} role. Without it, pages go to {@code /welcome}, which says how to open the desk.
 */
@ApplicationScoped
public class DeskSignIn implements HttpAuthenticationMechanism {

    public static final String OWNER = "owner";

    /**
     * The session cookie's name for this request. Cookies are kept per host name, not per port, so each port has its
     * own name: two desks on one machine (another instance, dev mode) don't overwrite each other's.
     */
    public static String cookie(RoutingContext rc) {
        var authority = rc.request().authority();
        int port = authority == null || authority.port() == -1 ? 80 : authority.port();
        return "elfdesk-session-" + port;
    }

    @Override
    public Uni<SecurityIdentity> authenticate(RoutingContext rc, IdentityProviderManager providers) {
        Cookie c = rc.request().getCookie(cookie(rc));
        if (c == null) return Uni.createFrom().optional(Optional.empty());
        return providers.authenticate(new SessionRequest(c.getValue()));
    }

    @Override
    public Uni<ChallengeData> getChallenge(RoutingContext rc) {
        boolean page = "GET".equals(rc.request().method().name())
                && !"websocket".equalsIgnoreCase(rc.request().getHeader("Upgrade"));
        return Uni.createFrom().item(page ? new ChallengeData(302, "Location", "/welcome") : new ChallengeData(401));
    }

    @Override
    public Set<Class<? extends AuthenticationRequest>> getCredentialTypes() {
        return Set.of(SessionRequest.class);
    }

    public static final class SessionRequest extends BaseAuthenticationRequest {
        final String value;

        SessionRequest(String value) {
            this.value = value;
        }
    }

    @ApplicationScoped
    public static class SessionProvider implements IdentityProvider<SessionRequest> {

        @Inject
        DeskSessions sessions;

        @Inject
        GhAccount account;

        @Override
        public Class<SessionRequest> getRequestType() {
            return SessionRequest.class;
        }

        @Override
        public Uni<SecurityIdentity> authenticate(SessionRequest request, AuthenticationRequestContext context) {
            if (!sessions.isSession(request.value)) return Uni.createFrom().nullItem();
            // Asking gh for the login can take a moment the first time: not on the event loop.
            return context.runBlocking(() -> QuarkusSecurityIdentity.builder()
                    .setPrincipal(new QuarkusPrincipal(account.login()))
                    .addRole(OWNER)
                    .build());
        }
    }
}
