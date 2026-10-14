package io.github.houseelves.desk.status;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.github.houseelves.desk.runner.Live;
import io.github.houseelves.desk.runner.RunnerHub;
import io.github.houseelves.desk.triage.TriageElf;
import io.github.houseelves.desk.triage.TriageRuns;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * The weekly-status elf's drafts: one JSON file per draft (yyyy-MM-dd_HH-mm-ss.json), saved by its Monday timer or
 * by Generate on the Status tab. The desk only reads them; Send goes through the runner, and the elf records it.
 */
@ApplicationScoped
public class StatusElf {

    private static final Logger LOG = Logger.getLogger(StatusElf.class);
    static final Pattern ID = Pattern.compile("\\d{4}-\\d{2}-\\d{2}_\\d{2}-\\d{2}-\\d{2}");
    static final int HISTORY = 12;
    static final String SEND_SINCE = "0.24.0";

    /**
     * A draft.
     *
     * @param sentAt   when you sent it, null while it's a draft
     * @param sentBody what went out (your edits included)
     */
    public record Status(String id, LocalDateTime at, Instant generatedAt, String weekStart, String weekEnd, String subject,
            String to, String body, Instant sentAt, String sentTo, String sentBody) {
    }

    @ConfigProperty(name = "elfdesk.status.runs-dir")
    Path dir;

    @Inject
    ObjectMapper mapper;

    @Inject
    RunnerHub hub;

    @Inject
    Live live;

    /** Drafts, newest first, as run infos (status "sent" or "draft") for the run picker. */
    public List<TriageElf.RunInfo> runs() {
        List<TriageElf.RunInfo> out = new ArrayList<>();
        for (String id : ids()) {
            Status s = status(id);
            if (s != null) out.add(new TriageElf.RunInfo(id, s.at(), s.sentAt() != null ? "sent" : "draft"));
            if (out.size() == HISTORY) break;
        }
        return out;
    }

    /** A draft by id, or the newest (id null); null when there is none. */
    public Status status(String id) {
        if (id == null) {
            List<String> ids = ids();
            if (ids.isEmpty()) return null;
            id = ids.get(0);
        }
        if (!ID.matcher(id).matches()) throw new IllegalArgumentException("Not a draft: " + id);
        Path file = dir.resolve(id + ".json");
        if (!Files.isRegularFile(file)) return null;
        try {
            JsonNode d = mapper.readTree(file.toFile());
            return new Status(id, LocalDateTime.parse(id.replace('_', 'T').replaceFirst("T(\\d+)-(\\d+)-", "T$1:$2:")),
                    instant(d, "generatedAt"), d.path("weekStart").asText(), d.path("weekEnd").asText(),
                    d.path("subject").asText(), d.path("to").asText(), d.path("body").asText(), instant(d, "sentAt"),
                    d.path("sentTo").asText(null), d.path("sentBody").asText(null));
        } catch (IOException e) {
            LOG.warnf("Cannot read %s: %s", file, e.getMessage());
            return null;
        }
    }

    /** Send: the elf emails the draft with your body to the recipients in its own config, and marks it sent. */
    public Status send(String id, String body) {
        Status s = status(id);
        if (s == null) throw new IllegalArgumentException("No such draft");
        if (s.sentAt() != null) throw new IllegalStateException("It was already sent");
        if (body == null || body.isBlank()) throw new IllegalArgumentException("The status is empty");
        JsonNode result;
        try {
            result = hub.request(hub.message("status.send").put("draft", id).put("body", body), SEND_SINCE)
                    .get(150, TimeUnit.SECONDS);
        } catch (Exception e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            throw new IllegalStateException(cause.getMessage());
        }
        if (result.path("status").asInt() != 200) {
            throw new IllegalStateException("Not sent: " + result.path("error").asText("the runner couldn't send it"));
        }
        live.triage(TriageRuns.STATUS);
        return status(id);
    }

    private static Instant instant(JsonNode d, String field) {
        String v = d.path(field).asText(null);
        return v == null || v.isBlank() ? null : Instant.parse(v);
    }

    private List<String> ids() {
        if (!Files.isDirectory(dir)) return List.of();
        try (Stream<Path> files = Files.list(dir)) {
            return files.map(p -> p.getFileName().toString())
                    .filter(n -> n.endsWith(".json"))
                    .map(n -> n.substring(0, n.length() - ".json".length()))
                    .filter(n -> ID.matcher(n).matches())
                    .sorted(Comparator.reverseOrder())
                    .toList();
        } catch (IOException e) {
            LOG.warnf("Cannot list %s: %s", dir, e.getMessage());
            return List.of();
        }
    }
}
