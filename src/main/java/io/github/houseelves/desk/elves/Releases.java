package io.github.houseelves.desk.elves;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.jboss.logging.Logger;

import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * The newest releases of Elf Desk and its runner (their tags on GitHub, v0.6.6), so the desk can say when there's an
 * update. Asked without a token (they're public), at most every 10 minutes each.
 */
@ApplicationScoped
public class Releases {

    private static final Logger LOG = Logger.getLogger(Releases.class);
    static final Duration KEEP = Duration.ofMinutes(10);

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final Map<String, Seen> seen = new ConcurrentHashMap<>();

    record Seen(String tag, Instant at) {
    }

    @Inject
    ObjectMapper mapper;

    /** The newest release's tag of House-elves/{@code repo}, or null if GitHub didn't say. */
    public String latest(String repo) {
        Seen s = seen.get(repo);
        if (s != null && s.at().plus(KEEP).isAfter(Instant.now())) return s.tag();
        String tag = null;
        try {
            HttpResponse<String> r = http.send(HttpRequest.newBuilder(URI.create("https://api.github.com/repos/House-elves/" + repo
                    + "/releases/latest")).timeout(Duration.ofSeconds(15)).header("Accept", "application/vnd.github+json")
                    .header("User-Agent", "elf-desk").build(), HttpResponse.BodyHandlers.ofString());
            if (r.statusCode() == 200) tag = mapper.readTree(r.body()).path("tag_name").asText(null);
            else LOG.debugf("GitHub answered %d for %s's latest release", r.statusCode(), repo);
        } catch (Exception e) {
            LOG.debugf("Couldn't ask for %s's latest release: %s", repo, e.getMessage());
        }
        if (tag != null && !tag.matches("v\\d{1,4}(\\.\\d{1,4}){1,3}")) tag = null;
        // Remembered even when GitHub didn't answer, so a desk offline doesn't ask on every look.
        seen.put(repo, new Seen(tag != null ? tag : s != null ? s.tag() : null, Instant.now()));
        return tag != null ? tag : s != null ? s.tag() : null;
    }

    /** a is older than b, both like v0.6.6 or 0.6.6. */
    public static boolean older(String a, String b) {
        int[] x = parts(a), y = parts(b);
        if (x == null || y == null) return false;
        for (int i = 0; i < Math.max(x.length, y.length); i++) {
            int p = i < x.length ? x[i] : 0, q = i < y.length ? y[i] : 0;
            if (p != q) return p < q;
        }
        return false;
    }

    private static int[] parts(String v) {
        if (v == null || !v.matches("v?\\d{1,4}(\\.\\d{1,4}){0,3}")) return null;
        String[] s = v.replaceFirst("^v", "").split("\\.");
        int[] out = new int[s.length];
        for (int i = 0; i < s.length; i++) out[i] = Integer.parseInt(s[i]);
        return out;
    }
}
