package io.github.houseelves.desk.triage;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.enterprise.context.ApplicationScoped;

/**
 * Read-only view of the runs daily-triage saved: one JSON file per run (yyyy-MM-dd_HH-mm-ss.json). The desk never
 * writes there; that elf owns the directory.
 */
@ApplicationScoped
public class TriageElf {

    private static final Logger LOG = Logger.getLogger(TriageElf.class);
    static final Pattern RUN_ID = Pattern.compile("\\d{4}-\\d{2}-\\d{2}_\\d{2}-\\d{2}-\\d{2}");
    static final int HISTORY = 30;

    /**
     * The two things the desk shows: the email triage (any run that triaged email, including the old morning timer's)
     * and the Zulip digest. A briefing-only run has neither.
     */
    public enum Kind {
        EMAIL, ZULIP;

        static Kind of(String mode) {
            return switch (mode) {
                case "zulip" -> ZULIP;
                case "briefing-only" -> null;
                default -> EMAIL;
            };
        }
    }

    /** One saved run, without its contents. {@code at} is the elf's local time. */
    public record RunInfo(String id, LocalDateTime at, String status) {
    }

    /**
     * An email triage run.
     *
     * @param accounts per Gmail account: what was processed and each email's decision and reason
     * @param emailed  whether the elf also emailed the summary (its morning timer did; desk runs don't)
     */
    public record Email(RunInfo run, JsonNode accounts, boolean emailed, long durationMs) {
    }

    /**
     * A Zulip run: the messages of the last {@code hours} and a digest per "stream > topic".
     *
     * @param done per "stream > topic" you marked Done on the desk, when (filled in by {@link ZulipDoneService})
     */
    public record Zulip(RunInfo run, int hours, JsonNode messages, JsonNode digests, long durationMs, java.util.Map<String, String> done) {

        public Zulip(RunInfo run, int hours, JsonNode messages, JsonNode digests, long durationMs) {
            this(run, hours, messages, digests, durationMs, java.util.Map.of());
        }
    }

    private final Path dir;
    private final ObjectMapper mapper;

    public TriageElf(@ConfigProperty(name = "elfdesk.triage.runs-dir") Path dir, ObjectMapper mapper) {
        this.dir = dir;
        this.mapper = mapper;
    }

    /** Runs of a kind, newest first. */
    public List<RunInfo> runs(Kind kind) {
        List<RunInfo> out = new ArrayList<>();
        for (String id : ids()) {
            JsonNode run = read(id);
            if (run == null || Kind.of(run.path("mode").asText()) != kind) continue;
            out.add(info(id, run));
            if (out.size() == HISTORY) break;
        }
        return out;
    }

    /** An email triage run by id, or the newest one (id null); null when there is none. */
    public Email email(String id) {
        String found = find(Kind.EMAIL, id);
        if (found == null) return null;
        JsonNode run = read(found);
        // Runs from before --no-email have no "emailed": their timer emailed them unless it was a dry run.
        boolean emailed = run.has("emailed") ? run.path("emailed").asBoolean() : !run.path("dryRun").asBoolean();
        return new Email(info(found, run), run.path("triage").path("accounts"), emailed, run.path("durationMs").asLong());
    }

    /** A Zulip run by id, or the newest one (id null); null when there is none. */
    public Zulip zulip(String id) {
        String found = find(Kind.ZULIP, id);
        if (found == null) return null;
        JsonNode run = read(found);
        return new Zulip(info(found, run), run.path("zulipHours").asInt(24), run.path("zulip").path("messages"),
                run.path("zulip").path("digests"), run.path("durationMs").asLong());
    }

    private String find(Kind kind, String id) {
        if (id != null) {
            if (!RUN_ID.matcher(id).matches()) throw new IllegalArgumentException("Not a run: " + id);
            JsonNode run = read(id);
            return run != null && Kind.of(run.path("mode").asText()) == kind ? id : null;
        }
        for (String candidate : ids()) {
            JsonNode run = read(candidate);
            if (run != null && Kind.of(run.path("mode").asText()) == kind) return candidate;
        }
        return null;
    }

    private RunInfo info(String id, JsonNode run) {
        return new RunInfo(id, LocalDateTime.parse(id.replace('_', 'T').replaceFirst("T(\\d+)-(\\d+)-", "T$1:$2:")),
                run.path("status").asText("success"));
    }

    /** Run ids, newest first. */
    private List<String> ids() {
        if (!Files.isDirectory(dir)) return List.of();
        try (Stream<Path> files = Files.list(dir)) {
            return files.map(p -> p.getFileName().toString())
                    .filter(n -> n.endsWith(".json"))
                    .map(n -> n.substring(0, n.length() - ".json".length()))
                    .filter(n -> RUN_ID.matcher(n).matches())
                    .sorted(Comparator.reverseOrder())
                    .toList();
        } catch (IOException e) {
            LOG.warnf("Cannot list %s: %s", dir, e.getMessage());
            return List.of();
        }
    }

    private JsonNode read(String id) {
        Path file = dir.resolve(id + ".json");
        if (!Files.isRegularFile(file)) return null;
        try {
            return mapper.readTree(file.toFile());
        } catch (IOException e) {
            LOG.warnf("Cannot read %s: %s", file, e.getMessage());
            return null;
        }
    }
}
