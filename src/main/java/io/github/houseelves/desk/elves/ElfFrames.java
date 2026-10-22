package io.github.houseelves.desk.elves;

import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import io.quarkiverse.web.bundler.runtime.Bundle;
import io.vertx.core.buffer.Buffer;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;

/**
 * An elf's own tab, in a sandboxed frame on the desk's page. The elf's UI is code from its repo, so it never runs in
 * the desk's own page: the frame has an origin of its own (none: {@code sandbox} without {@code allow-same-origin}),
 * which can't read the desk's cookie, page key or storage, and its CSP lets it connect nowhere. All it can do is
 * post messages to the desk's page, which answers only what the elf's own elf.yaml allows (see elf-frame.js).
 *
 * <ul>
 * <li>{@code /elf-frame/<elf>}: the frame's page, behind the desk's cookie like any page (it holds no data).</li>
 * <li>{@code /elf-files/<key>/ui/...}: the elf's UI files at its installed commit. The frame can't send the cookie,
 * so these need none: the key, made for each elf and commit when the desk starts, is what lets them be read.</li>
 * <li>{@code /elf-kit/1/kit.js}: what elves build their UI with (Lit, the desk's look, the bridge), also cookie-free.</li>
 * </ul>
 */
public class ElfFrames {

    private static final Logger LOG = Logger.getLogger(ElfFrames.class);
    static final Pattern NAME = Pattern.compile("[a-z0-9][a-z0-9-]{0,40}");
    static final Pattern KEY = Pattern.compile("[0-9a-f]{32}");
    static final Pattern PATH = Pattern.compile("ui(/[A-Za-z0-9_-][A-Za-z0-9._-]{0,80}){1,6}");
    static final Map<String, String> TYPES = Map.of("js", "text/javascript; charset=utf-8", "css", "text/css; charset=utf-8",
            "json", "application/json", "svg", "image/svg+xml", "png", "image/png", "jpg", "image/jpeg", "webp", "image/webp",
            "woff2", "font/woff2");
    static final Set<String> VIEWS = Set.of("tab", "card", "settings");
    static final String FONTS = "https://fonts.googleapis.com/css2?family=Bricolage+Grotesque:opsz,wght@12..96,600;12..96,700"
            + "&family=IBM+Plex+Sans:wght@400;500;600;700&family=JetBrains+Mono:wght@400;500&display=swap";

    @Inject
    ElvesService elves;

    @Inject
    Bundle bundle;

    @ConfigProperty(name = "elfdesk.host")
    String deskHost;

    private final SecureRandom random = new SecureRandom();
    /** Key for elf@commit, and what each key opens. */
    private final Map<String, String> keys = new ConcurrentHashMap<>();
    private final Map<String, String[]> opens = new ConcurrentHashMap<>();

    void install(@Observes Router router) {
        router.get("/elf-frame/:elf").blockingHandler(this::frame);
        router.get("/elf-files/:key/*").blockingHandler(this::file);
        router.get("/elf-data/:key/:folder/:name").blockingHandler(this::data);
        router.get("/elf-kit/1/kit.js").handler(this::kit);
    }

    String key(String elf, String commit) {
        return keys.computeIfAbsent(elf + "@" + commit, k -> {
            byte[] b = new byte[16];
            random.nextBytes(b);
            String key = HexFormat.of().formatHex(b);
            opens.put(key, new String[] { elf, commit });
            return key;
        });
    }

    void frame(RoutingContext rc) {
        String elf = rc.pathParam("elf");
        String view = rc.request().getParam("view", "tab");
        String theme = rc.request().getParam("theme", "");
        ElvesService.Tab t;
        try {
            t = NAME.matcher(elf).matches() && VIEWS.contains(view) ? elves.tab(elf) : null;
        } catch (RuntimeException e) {
            LOG.debugf("No frame for %s: %s", elf, e.getMessage());
            t = null;
        }
        String origin = origin(rc);
        rc.response().headers().remove("X-Frame-Options");
        rc.response().putHeader("Content-Type", "text/html; charset=utf-8")
                .putHeader("Cache-Control", "no-store")
                .putHeader("Referrer-Policy", "no-referrer")
                .putHeader("X-Content-Type-Options", "nosniff")
                // The sandbox here too, so the page is never more than this, however it's opened.
                .putHeader("Content-Security-Policy", "default-src 'none'; script-src " + origin
                        + "; style-src " + origin + " 'unsafe-inline' https://fonts.googleapis.com; font-src https://fonts.gstatic.com"
                        + "; img-src " + origin + " data: blob:; connect-src 'none'; form-action 'none'; base-uri 'none'"
                        + "; frame-ancestors " + origin + "; sandbox allow-scripts allow-popups allow-popups-to-escape-sandbox");
        if (t == null) {
            rc.response().setStatusCode(404).end("""
                    <!doctype html><meta charset="utf-8"><body style="font: 15px system-ui; color: #56635b">
                    That elf isn't installed, or has no tab.</body>""");
            return;
        }
        String themeAttr = theme.equals("light") || theme.equals("dark") ? " data-theme=\"" + theme + "\"" : "";
        rc.response().end("""
                <!doctype html>
                <html lang="en"%s><head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1">
                <title>%s</title>
                <link rel="stylesheet" href="%s">
                <script type="module" src="/elf-files/%s/%s"></script>
                </head><body></body></html>
                """.formatted(themeAttr, t.title().replace("&", "&amp;").replace("<", "&lt;"), FONTS.replace("&", "&amp;"), key(t.elf(), t.commit()), t.page()));
    }

    void file(RoutingContext rc) {
        String[] open = KEY.matcher(rc.pathParam("key")).matches() ? opens.get(rc.pathParam("key")) : null;
        String path = rc.pathParam("*");
        String ext = path == null ? "" : path.substring(path.lastIndexOf('.') + 1);
        if (open == null || path == null || !PATH.matcher(path).matches() || !TYPES.containsKey(ext)) {
            rc.response().setStatusCode(404).end();
            return;
        }
        byte[] data;
        try {
            data = elves.file(open[0], open[1], path);
        } catch (RuntimeException e) {
            data = null;
        }
        if (data == null) {
            rc.response().setStatusCode(404).end();
            return;
        }
        // The frame's origin is opaque, so a module from here is a cross-origin load: it needs CORS.
        rc.response().putHeader("Content-Type", TYPES.get(ext))
                .putHeader("Access-Control-Allow-Origin", "*")
                .putHeader("X-Content-Type-Options", "nosniff")
                .putHeader("Content-Security-Policy", "default-src 'none'; sandbox")
                .putHeader("Cache-Control", "private, max-age=3600")
                .end(Buffer.buffer(data));
    }

    static final Pattern FOLDER = Pattern.compile("[a-z0-9][a-z0-9_-]{0,40}");
    static final Pattern FILE = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,120}");
    static final Map<String, String> IMAGES = Map.of("png", "image/png", "jpg", "image/jpeg", "jpeg", "image/jpeg",
            "gif", "image/gif", "webp", "image/webp");
    static final long MAX_IMAGE = 10L * 1024 * 1024;

    /**
     * An image in one of the folders the elf's elf.yaml lets its tab read (an agent's screenshot, say), for an img in
     * its frame: only images, only a plain file in that folder, read here (the folder is ~/.config/<elf>/<folder>).
     */
    void data(RoutingContext rc) {
        String[] open = KEY.matcher(rc.pathParam("key")).matches() ? opens.get(rc.pathParam("key")) : null;
        String folder = rc.pathParam("folder"), name = rc.pathParam("name");
        String ext = name == null ? "" : name.substring(name.lastIndexOf('.') + 1).toLowerCase(java.util.Locale.ROOT);
        byte[] data = null;
        if (open != null && folder != null && FOLDER.matcher(folder).matches() && FILE.matcher(name).matches()
                && IMAGES.containsKey(ext)) {
            try {
                if (elves.dataFolders(open[0]).contains(folder)) {
                    java.nio.file.Path dir = java.nio.file.Path.of(System.getProperty("user.home"), ".config", open[0], folder);
                    java.nio.file.Path f = dir.resolve(name);
                    if (java.nio.file.Files.isRegularFile(f, java.nio.file.LinkOption.NOFOLLOW_LINKS)
                            && java.nio.file.Files.size(f) <= MAX_IMAGE
                            && f.toRealPath().getParent().equals(dir.toRealPath())) {
                        data = java.nio.file.Files.readAllBytes(f);
                    }
                }
            } catch (Exception e) {
                LOG.debugf("No %s/%s for %s: %s", folder, name, open[0], e.getMessage());
            }
        }
        if (data == null) {
            rc.response().setStatusCode(404).end();
            return;
        }
        rc.response().putHeader("Content-Type", IMAGES.get(ext))
                .putHeader("Access-Control-Allow-Origin", "*")
                .putHeader("X-Content-Type-Options", "nosniff")
                .putHeader("Content-Security-Policy", "default-src 'none'; sandbox")
                .putHeader("Cache-Control", "private, max-age=600")
                .end(Buffer.buffer(data));
    }

    /** A stable name for the kit bundle, whose file name changes with each build. */
    void kit(RoutingContext rc) {
        rc.response().putHeader("Content-Type", "text/javascript; charset=utf-8")
                .putHeader("Access-Control-Allow-Origin", "*")
                .putHeader("X-Content-Type-Options", "nosniff")
                .putHeader("Cache-Control", "no-cache")
                .end("export * from '" + bundle.script("kit") + "';\n");
    }

    private String origin(RoutingContext rc) {
        var a = rc.request().authority();
        return "http://" + deskHost + (a == null || a.port() == -1 ? "" : ":" + a.port());
    }
}
