package io.github.houseelves.desk.security;

import java.util.List;
import java.util.Locale;

import org.eclipse.microprofile.config.inject.ConfigProperty;

import io.vertx.core.http.Cookie;
import io.vertx.core.http.CookieSameSite;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;

/**
 * The desk only listens on this machine, and only answers requests addressed to it by a local name. Listening on
 * 127.0.0.1 isn't enough on its own: a web page can point its own domain at 127.0.0.1 (DNS rebinding) and then
 * talk to the desk as if it were the same site, but its requests still carry that domain as their Host.
 *
 * <p>
 * Its pages are only served at its own name, {@code elfdesk.host} ({@code elf-desk.localhost}, which browsers send to
 * 127.0.0.1 themselves); other local names are redirected there. Browsers keep cookies per host name, not per port,
 * so a sign-in cookie set on {@code localhost} would also go to every other server on {@code localhost}, such as an
 * agent's dev mode. Health checks and the runner may still use any local name.
 *
 * <p>
 * <p>
 * A name in {@code elfdesk.remote-hosts} is the desk's too, for reaching it from your other devices through a private
 * network that proxies to it here, such as {@code tailscale serve} at {@code https://<machine>.<tailnet>.ts.net}: the
 * desk still only listens on 127.0.0.1, and that network decides who gets that far. Empty unless you set it.
 *
 * <p>
 * {@code /open?code=...} swaps a one-time code for a session (see {@link DeskSessions}); {@code /welcome} is
 * where you land without one. No other site may show the desk in a frame.
 */
public class LocalOnly {

    @ConfigProperty(name = "elfdesk.host")
    String deskHost;

    @ConfigProperty(name = "elfdesk.allowed-hosts")
    List<String> allowedHosts;

    @ConfigProperty(name = "elfdesk.remote-hosts")
    java.util.Optional<List<String>> remoteHosts;

    /** The remote names, for the sign-in cookie's name (see {@link DeskSignIn#cookie}). */
    static volatile List<String> remoteNames = List.of();

    @Inject
    DeskSessions sessions;

    void install(@Observes Router router) {
        remoteNames = remoteHosts.orElse(List.of());
        router.route().order(Integer.MIN_VALUE).handler(rc -> {
            // The authority is the Host header in HTTP/1.1 and :authority in HTTP/2 (which has no Host header).
            var authority = rc.request().authority();
            boolean own = authority != null && (authority.host().equalsIgnoreCase(deskHost) || remote(authority.host()));
            if (authority == null || !(own || allowed(authority.host(), allowedHosts))) {
                rc.response().setStatusCode(403).end("The desk only answers on localhost");
                return;
            }
            if (!own && !anyName(rc.normalizedPath())) {
                String desk = "http://" + deskHost + (authority.port() == -1 ? "" : ":" + authority.port());
                if (page(rc)) {
                    rc.redirect(desk + rc.request().uri());
                } else {
                    rc.response().setStatusCode(403).end("Open the desk at " + desk);
                }
                return;
            }
            rc.response().putHeader("X-Frame-Options", "DENY");
            rc.response().putHeader("Content-Security-Policy", "frame-ancestors 'none'");
            rc.next();
        });
        router.get("/open").blockingHandler(rc -> {
            DeskSessions.Opened opened = sessions.redeem(rc.request().getParam("code"));
            if (opened == null) {
                rc.redirect("/welcome?expired");
                return;
            }
            // On a remote name it only ever comes over HTTPS (through the proxy): Secure, and __Host- keeps it to exactly
            // this name and path, so nothing else served at that name, on any port, is sent it.
            rc.response().addCookie(Cookie.cookie(DeskSignIn.cookie(rc), opened.token()).setHttpOnly(true)
                    .setSecure(isRemote(rc)).setPath("/").setSameSite(CookieSameSite.STRICT).setMaxAge(DeskSessions.LIFETIME.toSeconds()));
            // The page key goes in this origin's storage (rpc.js), which another port can't read. Only whoever
            // redeemed the code ever sees this response.
            rc.response().putHeader("Content-Type", "text/html; charset=utf-8").putHeader("Cache-Control", "no-store")
                    .putHeader("Referrer-Policy", "no-referrer")
                    .end("""
                            <!doctype html><meta charset="utf-8"><title>Elf Desk</title>
                            <script>try { localStorage.setItem('elfdesk-page', '%s'); } catch (e) {} location.replace('/');</script>
                            """.formatted(opened.pageKey()));
        });
        router.get("/welcome").handler(rc -> rc.response().putHeader("Content-Type", "text/html; charset=utf-8")
                .putHeader("Cache-Control", "no-store")
                .end(welcome(rc.request().getParam("expired") != null, deskHost)));
    }

    /** The request came by a remote name: through the HTTPS proxy, so with no port of its own. */
    static boolean isRemote(RoutingContext rc) {
        var a = rc.request().authority();
        return a != null && a.port() == -1 && allowed(a.host(), remoteNames);
    }

    /** A name you reach the desk by from your other devices (elfdesk.remote-hosts). */
    boolean remote(String host) {
        return remoteHosts.isPresent() && allowed(host, remoteHosts.get());
    }

    /** Health checks and the runner's socket: for programs on this machine, which may use any local name. */
    static boolean anyName(String path) {
        return path.equals("/runner") || path.equals("/runner/") || path.equals("/q/health") || path.startsWith("/q/health/");
    }

    /** A browser loading a page, as opposed to sending something or opening a socket. */
    public static boolean page(RoutingContext rc) {
        String method = rc.request().method().name();
        return (method.equals("GET") || method.equals("HEAD"))
                && !"websocket".equalsIgnoreCase(rc.request().getHeader("Upgrade"));
    }

    /** The request's host name (without the port) is one of the allowed names. IPv6 hosts come in brackets or not. */
    public static boolean allowed(String host, List<String> names) {
        if (host == null) return false;
        String name = host.contains(":") && !host.startsWith("[") ? "[" + host + "]" : host;
        return names.stream().anyMatch(n -> n.strip().equalsIgnoreCase(name.toLowerCase(Locale.ROOT)));
    }

    private static String welcome(boolean expired, String deskHost) {
        return """
                <!doctype html>
                <html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1">
                <title>Elf Desk</title>
                <style>
                  :root { --bg: #f4f6f1; --ink: #17201b; --muted: #56635b; --accent: #24533f; --line: #dde3da; color-scheme: light; }
                  @media (prefers-color-scheme: dark) { :root { --bg: #0f1411; --ink: #e6ece7; --muted: #94a39a; --accent: #7db68a; --line: #27322c; color-scheme: dark; } }
                  body { margin: 0; background: var(--bg); color: var(--ink); font: 16px/1.55 system-ui, sans-serif; }
                  main { max-width: 34rem; margin: 18vh auto 0; padding: 0 20px; }
                  h1 { font-size: 26px; margin: 0 0 12px; color: var(--accent); }
                  code { font-size: 15px; background: var(--line); padding: 2px 6px; border-radius: 4px; }
                  p { margin: 0 0 12px; } .muted { color: var(--muted); font-size: 14px; }
                </style></head>
                <body><main>
                <h1>Elf Desk</h1>
                %s
                <p>Open the desk from a terminal on this machine with <code>house-elves open</code>. It opens this page with a one-time link that signs you in in this browser for 30 days.</p>
                <p class="muted">From another machine, forward the port first (<code>ssh -L 7777:localhost:7777 &lt;this machine&gt;</code>), then run <code>house-elves open --print</code> on this machine and open the link it prints. Use Chrome, Firefox or Edge there: they send <code>%s</code> to your own machine.</p>
                </main></body></html>
                """.formatted(expired ? "<p>That link was already used. Each link works once.</p>" : "", deskHost);
    }
}
