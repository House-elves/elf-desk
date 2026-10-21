package io.github.houseelves.desk.security;

import java.util.List;
import java.util.Locale;

import org.eclipse.microprofile.config.inject.ConfigProperty;

import io.vertx.core.http.Cookie;
import io.vertx.core.http.CookieSameSite;
import io.vertx.ext.web.Router;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;

/**
 * The desk only listens on this machine, and only answers requests addressed to it by a local name. Listening on
 * 127.0.0.1 isn't enough on its own: a web page can point its own domain at 127.0.0.1 (DNS rebinding) and then
 * talk to the desk as if it were the same site, but its requests still carry that domain as their Host.
 *
 * <p>
 * {@code /open?code=...} swaps a one-time code for the key's cookie (see {@link DeskKey}); {@code /welcome} is
 * where you land without it.
 */
public class LocalOnly {

    @ConfigProperty(name = "elfdesk.allowed-hosts")
    List<String> allowedHosts;

    @Inject
    DeskKey key;

    void install(@Observes Router router) {
        router.route().order(Integer.MIN_VALUE).handler(rc -> {
            // The authority is the Host header in HTTP/1.1 and :authority in HTTP/2 (which has no Host header).
            var authority = rc.request().authority();
            if (authority == null || !allowed(authority.host(), allowedHosts)) {
                rc.response().setStatusCode(403).end("The desk only answers on localhost");
                return;
            }
            rc.next();
        });
        router.get("/open").blockingHandler(rc -> {
            String k = key.redeem(rc.request().getParam("code"));
            if (k == null) {
                rc.redirect("/welcome?expired");
                return;
            }
            rc.response().addCookie(Cookie.cookie(DeskKeyAuth.COOKIE, k).setHttpOnly(true).setPath("/")
                    .setSameSite(CookieSameSite.STRICT).setMaxAge(400L * 24 * 3600));
            rc.redirect("/");
        });
        router.get("/welcome").handler(rc -> rc.response().putHeader("Content-Type", "text/html; charset=utf-8")
                .putHeader("Cache-Control", "no-store")
                .end(welcome(rc.request().getParam("expired") != null)));
    }

    /** The request's host name (without the port) is one of the allowed names. IPv6 hosts come in brackets or not. */
    static boolean allowed(String host, List<String> names) {
        if (host == null) return false;
        String name = host.contains(":") && !host.startsWith("[") ? "[" + host + "]" : host;
        return names.stream().anyMatch(n -> n.strip().equalsIgnoreCase(name.toLowerCase(Locale.ROOT)));
    }

    private static String welcome(boolean expired) {
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
                <p>Open the desk from a terminal on this machine with <code>house-elves open</code>. It opens this page with a one-time link that signs you in in this browser.</p>
                <p class="muted">From another machine, forward the port first (<code>ssh -L 7777:localhost:7777 &lt;this machine&gt;</code>), then run <code>house-elves open --print</code> on this machine and open the link it prints.</p>
                </main></body></html>
                """.formatted(expired ? "<p>That link was already used. Each link works once.</p>" : "");
    }
}
