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
 * Signs you in with a session (see {@link DeskSessions}): with it you are the desk's GitHub account and have the
 * {@code owner} role. Without it, pages go to {@code /welcome}, which says how to open the desk.
 *
 * <p>
 * The page itself (its HTML and bundles, which hold no data) needs only the cookie. Everything else, the JSON-RPC
 * socket and the agent's files included, also needs the session's page key, which the page sends as {@code k}.
 */
@ApplicationScoped
public class DeskSignIn implements HttpAuthenticationMechanism {

    public static final String OWNER = "owner";

    /**
     * The session cookie's name for this request. Cookies are kept per host name, not per port, so each port has its
     * own name: two desks on one machine (another instance, dev mode) don't overwrite each other's.
     */
    public static String cookie(RoutingContext rc) {
        // By a remote name (HTTPS, through the proxy): its own name, never the local one, with __Host-'s rules.
        if (LocalOnly.isRemote(rc)) return "__Host-elfdesk-session";
        var authority = rc.request().authority();
        int port = authority == null || authority.port() == -1 ? 80 : authority.port();
        return "elfdesk-session-" + port;
    }

    /** The page's HTML and bundles, and an elf tab's frame page: no data in them, so the cookie alone is enough. */
    static boolean pageAsset(String path) {
        return path.equals("/") || path.equals("/index.html") || path.startsWith("/static/") || path.startsWith("/elf-frame/")
                || path.startsWith("/_static/") || path.startsWith("/q/");
    }

    /**
     * Where you sign in: the cookie isn't looked at there. A cookie that doesn't sign you in (it ended, or it was
     * sent without the page key) would otherwise send these pages to /welcome, and /welcome to itself.
     */
    static boolean signInPage(String path) {
        return path.equals("/open") || path.equals("/welcome");
    }

    @Override
    public Uni<SecurityIdentity> authenticate(RoutingContext rc, IdentityProviderManager providers) {
        Cookie c = signInPage(rc.normalizedPath()) ? null : rc.request().getCookie(cookie(rc));
        if (c == null) return Uni.createFrom().optional(Optional.empty());
        boolean needsPageKey = !pageAsset(rc.normalizedPath());
        return providers.authenticate(new SessionRequest(c.getValue(), needsPageKey, rc.request().getParam("k")));
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
        final boolean needsPageKey;
        final String pageKey;

        SessionRequest(String value, boolean needsPageKey, String pageKey) {
            this.value = value;
            this.needsPageKey = needsPageKey;
            this.pageKey = pageKey;
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
            if (!sessions.isSession(request.value, request.needsPageKey, request.pageKey)) return Uni.createFrom().nullItem();
            // Asking gh for the login can take a moment the first time: not on the event loop.
            return context.runBlocking(() -> QuarkusSecurityIdentity.builder()
                    .setPrincipal(new QuarkusPrincipal(account.login()))
                    .addRole(OWNER)
                    .build());
        }
    }
}
