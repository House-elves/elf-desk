package io.github.houseelves.desk.elves;

import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

import com.fasterxml.jackson.databind.JsonNode;

import io.github.houseelves.desk.runner.Live;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

/**
 * Which elves need you, for a green dot on their tabs: an elf says so in attention.json in the first of its data
 * folders ({attention: true, why}), such as Weekly status from Monday's draft until the status has gone to the team.
 * Read when the runner connects, when one of the elf's runs ends, when its data changes and after its actions: never
 * on a timer, and only through the runner, as its tab reads.
 */
@ApplicationScoped
public class Attention {

    private static final Logger LOG = Logger.getLogger(Attention.class);
    static final String FILE = "attention.json";

    @Inject
    ElvesService elves;

    @Inject
    Live live;

    /** elf -> why it needs you. */
    private final Map<String, String> on = new ConcurrentHashMap<>();

    /** {elf: why}, for the tabs. */
    public Map<String, String> now() {
        return new TreeMap<>(on);
    }

    /** Every installed elf's, once the runner is there. */
    public void readAll() {
        Thread.ofVirtual().start(() -> {
            try {
                java.util.Set<String> seen = new java.util.HashSet<>();
                for (JsonNode e : elves.installed().path("elves")) {
                    String name = e.path("name").asText("");
                    seen.add(name);
                    readNow(name, e.path("data"));
                }
                if (on.keySet().retainAll(seen)) live.attention(now());
            } catch (RuntimeException e) {
                LOG.warnf("Couldn't read which elves need you: %s", e.getMessage());
            }
        });
    }

    /** One elf's, after something it did (off the thread that told us: this asks the runner). */
    public void read(String elf) {
        if (elf == null || elf.isBlank()) return;
        Thread.ofVirtual().start(() -> {
            try {
                for (JsonNode e : elves.installed().path("elves")) {
                    if (e.path("name").asText("").equals(elf)) {
                        readNow(elf, e.path("data"));
                        return;
                    }
                }
                if (on.remove(elf) != null) live.attention(now());
            } catch (RuntimeException e) {
                LOG.warnf("Couldn't read whether %s needs you: %s", elf, e.getMessage());
            }
        });
    }

    private void readNow(String elf, JsonNode data) {
        String why = null;
        if (data.isArray() && !data.isEmpty()) {
            JsonNode a = elves.read(elf, data.get(0).asText(), java.util.List.of(FILE), java.util.List.of("attention", "why"))
                    .path("files").path(FILE);
            if (a.path("attention").asBoolean(false)) {
                String w = a.path("why").asText("").strip();
                why = w.isEmpty() ? "Needs you" : w.length() > 120 ? w.substring(0, 120) : w;
            }
        }
        String was = why == null ? on.remove(elf) : on.put(elf, why);
        if (!java.util.Objects.equals(was, why)) live.attention(now());
    }
}
