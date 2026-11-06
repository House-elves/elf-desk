package io.github.houseelves.desk.elves;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import jakarta.enterprise.event.Observes;

/**
 * Files an elf's tab handed you to download (from one of its slots: ElvesService.slotFile), each at
 * {@code /elf-download/<random>} for one download, within ten minutes, then gone. Behind the desk's sign-in and
 * the page key like every route; always a download, never a page.
 */
public class Downloads {

    static final Duration KEPT = Duration.ofMinutes(10);
    static final int MAX_KEPT = 20;
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final ObjectMapper JSON = new ObjectMapper();

    record Kept(Path file, String name, long size, Instant until) {
    }

    static final Map<String, Kept> KEPT_FILES = new ConcurrentHashMap<>();

    /** Keeps the file (the desk's own, moved in) for one download: {url, name, size}. */
    public static JsonNode add(Path file, String name, long size) throws IOException {
        sweep();
        if (KEPT_FILES.size() >= MAX_KEPT) {
            Files.deleteIfExists(file);
            throw new IllegalStateException("Too many downloads waiting: try again in a few minutes");
        }
        byte[] b = new byte[16];
        RANDOM.nextBytes(b);
        String id = HexFormat.of().formatHex(b);
        String safe = safeName(name);
        KEPT_FILES.put(id, new Kept(file, safe, size, Instant.now().plus(KEPT)));
        return JSON.createObjectNode().put("url", "/elf-download/" + id).put("name", safe).put("size", size);
    }

    /** A name a browser saves as it is: letters, digits and . _ - (others become _), never only dots. */
    static String safeName(String name) {
        String n = name == null ? "" : name.replaceAll("[^A-Za-z0-9._-]", "_");
        if (n.length() > 120) n = n.substring(n.length() - 120);
        return n.isEmpty() || n.matches("\\.*") ? "download" : n;
    }

    static void sweep() {
        Instant now = Instant.now();
        KEPT_FILES.entrySet().removeIf(e -> {
            if (e.getValue().until().isAfter(now)) return false;
            delete(e.getValue().file());
            return true;
        });
    }

    static void delete(Path f) {
        try {
            Files.deleteIfExists(f);
        } catch (IOException ignored) {
            // the system's temp folder clears it
        }
    }

    void install(@Observes Router router) {
        router.get("/elf-download/:id").handler(this::download);
    }

    void download(RoutingContext rc) {
        sweep();
        String id = rc.pathParam("id");
        Kept k = id != null && id.matches("[0-9a-f]{32}") ? KEPT_FILES.remove(id) : null;
        if (k == null) {
            rc.response().setStatusCode(404).putHeader("Content-Type", "text/plain").end("That download is gone: click the file again");
            return;
        }
        rc.response().putHeader("Content-Type", "application/octet-stream")
                .putHeader("Content-Disposition", "attachment; filename=\"" + k.name() + "\"")
                .putHeader("X-Content-Type-Options", "nosniff")
                .putHeader("Content-Security-Policy", "default-src 'none'; sandbox")
                .putHeader("Cache-Control", "no-store")
                .sendFile(k.file().toString())
                .onComplete(x -> delete(k.file()));
    }
}
