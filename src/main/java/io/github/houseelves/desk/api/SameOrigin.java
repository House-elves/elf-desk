package io.github.houseelves.desk.api;

import java.net.URI;

import io.vertx.ext.web.Router;
import jakarta.enterprise.event.Observes;

/**
 * The desk's JSON-RPC socket only takes connections from the desk's own pages. Browsers send your sign-in cookie
 * with a WebSocket handshake from any site and leave the Origin check to the server, so without this another page
 * could drive the desk as you.
 */
public class SameOrigin {

    static final String JSON_RPC = "/json-rpc";

    void install(@Observes Router router) {
        router.route(JSON_RPC).order(Integer.MIN_VALUE).handler(rc -> {
            String origin = rc.request().getHeader("Origin");
            if (origin != null && !sameHost(origin, rc.request().getHeader("Host"))) {
                rc.response().setStatusCode(403).end("Cross-origin requests aren't allowed");
                return;
            }
            rc.next();
        });
    }

    /** The Origin's host:port is the Host the request was sent to. */
    static boolean sameHost(String origin, String host) {
        if (host == null) return false;
        try {
            URI o = URI.create(origin);
            if (o.getHost() == null) return false;
            String authority = o.getPort() == -1 ? o.getHost() : o.getHost() + ":" + o.getPort();
            return authority.equalsIgnoreCase(host);
        } catch (IllegalArgumentException e) {
            return false;
        }
    }
}
