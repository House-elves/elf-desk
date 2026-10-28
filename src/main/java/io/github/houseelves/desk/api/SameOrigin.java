package io.github.houseelves.desk.api;

import java.net.URI;

import io.github.houseelves.desk.security.LocalOnly;
import io.vertx.core.net.HostAndPort;
import io.vertx.ext.web.Router;
import jakarta.enterprise.event.Observes;

/**
 * Only the desk's own pages may send it anything: the JSON-RPC socket and every request that isn't a page load.
 * Browsers send your sign-in cookie with a WebSocket handshake from any site and leave the Origin check to the server,
 * and a page on another port of the same machine counts as the same site for SameSite cookies, so without this
 * another page could drive the desk as you.
 */
public class SameOrigin {

    void install(@Observes Router router) {
        router.route().order(Integer.MIN_VALUE + 1).handler(rc -> {
            String origin = rc.request().getHeader("Origin");
            if (origin != null && !LocalOnly.page(rc) && !sameAuthority(origin, rc.request().authority())) {
                rc.response().setStatusCode(403).end("Cross-origin requests aren't allowed");
                return;
            }
            rc.next();
        });
    }

    /** The Origin's host and port are the ones the request was sent to. */
    static boolean sameAuthority(String origin, HostAndPort authority) {
        if (authority == null) return false;
        try {
            URI o = URI.create(origin);
            if (o.getHost() == null) return false;
            if ("https".equals(o.getScheme())) {
                // A page over HTTPS reaches the desk only through a proxy (tailscale serve), which sends no port.
                return (o.getPort() == -1 || o.getPort() == 443) && authority.port() == -1 && o.getHost().equalsIgnoreCase(authority.host());
            }
            if (!"http".equals(o.getScheme())) return false;
            int port = o.getPort() == -1 ? 80 : o.getPort();
            int expected = authority.port() == -1 ? 80 : authority.port();
            return o.getHost().equalsIgnoreCase(authority.host()) && port == expected;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }
}
