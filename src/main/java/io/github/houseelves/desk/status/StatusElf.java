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
    static final String SEND_SINCE = "0.25.0";
    /** Who a send goes to: you (the elf's SEND_TO) or the team (TEAM_TO). */
    public static final String ME = "me", TEAM = "team";

    /**
     * A draft.
     *
     * @param from     who it's sent as (the elf's Gmail), empty for older drafts
     * @param to       your address (SEND_TO)
     * @param teamTo   the team's list (TEAM_TO), empty when the elf has none
     * @param sentAt   when it was last sent, null while it's a draft
     * @param sentBody what last went out (your edits included)
     * @param sends    every send, oldest first: each target at most once
     */
    public record Status(String id, LocalDateTime at, Instant generatedAt, String weekStart, String weekEnd, String subject,
            String from, String to, String teamTo, String body, Instant sentAt, String sentTo, String sentBody, List<Send> sends) {

        public boolean sentTo(String target) {
            return sends.stream().anyMatch(s -> s.target().equals(target));
        }
    }

    /**
     * One send: to me or the team, the address, when, and the text that went out.
     *
     * @param yourself you sent it yourself and marked it here: the elf emailed nothing, and body is null
     */
    public record Send(String target, String to, Instant at, String body, boolean yourself) {
    }

    @ConfigProperty(name = "elfdesk.status.runs-dir")
    Path dir;

    @Inject
    ObjectMapper mapper;

    @Inject
    RunnerHub hub;

    @Inject
    Live live;

    @Inject
    SentYourselfService yourself;

    /** Drafts, newest first, as run infos (status "sent" or "draft") for the run picker. */
    public List<TriageElf.RunInfo> runs() {
        List<TriageElf.RunInfo> out = new ArrayList<>();
        for (String id : ids()) {
            Status s = status(id);
            if (s != null) out.add(new TriageElf.RunInfo(id, s.at(),
                    s.sentTo(TEAM) ? "sent to the team" : s.sentTo(ME) ? "sent to you" : "draft"));
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
            List<Send> sends = new ArrayList<>();
            for (JsonNode s : d.path("sends")) {
                sends.add(new Send(s.path("target").asText(), s.path("to").asText(), instant(s, "at"), s.path("body").asText(), false));
            }
            // Sent before "sends" existed: that went to you.
            if (sends.isEmpty() && instant(d, "sentAt") != null) {
                sends.add(new Send(ME, d.path("sentTo").asText(d.path("to").asText()), instant(d, "sentAt"), d.path("sentBody").asText(), false));
            }
            Instant marked = yourself.at(id);
            if (marked != null && sends.stream().noneMatch(s -> s.target().equals(TEAM))) {
                sends.add(new Send(TEAM, d.path("teamTo").asText(""), marked, null, true));
                sends.sort(Comparator.comparing(Send::at, Comparator.nullsFirst(Comparator.naturalOrder())));
            }
            return new Status(id, LocalDateTime.parse(id.replace('_', 'T').replaceFirst("T(\\d+)-(\\d+)-", "T$1:$2:")),
                    instant(d, "generatedAt"), d.path("weekStart").asText(), d.path("weekEnd").asText(),
                    d.path("subject").asText(), d.path("from").asText(""), d.path("to").asText(), d.path("teamTo").asText(""),
                    d.path("body").asText(),
                    instant(d, "sentAt"), d.path("sentTo").asText(null), d.path("sentBody").asText(null), List.copyOf(sends));
        } catch (IOException e) {
            LOG.warnf("Cannot read %s: %s", file, e.getMessage());
            return null;
        }
    }

    /**
     * Send: the elf emails the draft with your body to you or the team (the addresses are in its own config), and
     * records it. Each target once per draft.
     */
    public Status send(String id, String body, String target) {
        if (!ME.equals(target) && !TEAM.equals(target)) throw new IllegalArgumentException("Send it to me or the team");
        Status s = status(id);
        if (s == null) throw new IllegalArgumentException("No such draft");
        if (s.sentTo(target)) throw new IllegalStateException("It was already sent to " + (TEAM.equals(target) ? "the team" : "you"));
        if (TEAM.equals(target) && s.teamTo().isBlank()) throw new IllegalStateException("The weekly-status elf has no TEAM_TO");
        if (body == null || body.isBlank()) throw new IllegalArgumentException("The status is empty");
        JsonNode result;
        try {
            result = hub.request(hub.message("status.send").put("draft", id).put("body", body).put("target", target), SEND_SINCE)
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

    /**
     * "I sent it myself": you emailed the team yourself, so the draft counts as sent to the team. The elf sends
     * nothing. Undo takes it back, only for a mark (an email the elf sent stays sent).
     */
    public Status sentYourself(String id, boolean sent) {
        Status s = status(id);
        if (s == null) throw new IllegalArgumentException("No such draft");
        if (sent) {
            if (s.sentTo(TEAM)) throw new IllegalStateException("It's already marked sent to the team");
            yourself.mark(s.id());
        } else {
            if (s.sends().stream().anyMatch(x -> x.target().equals(TEAM) && !x.yourself())) {
                throw new IllegalStateException("The elf sent this one to the team; that can't be undone");
            }
            yourself.unmark(s.id());
        }
        live.triage(TriageRuns.STATUS);
        return status(s.id());
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
