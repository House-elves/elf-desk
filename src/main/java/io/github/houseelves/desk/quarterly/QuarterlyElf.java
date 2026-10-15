package io.github.houseelves.desk.quarterly;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
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

import io.github.houseelves.desk.triage.TriageElf;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * The quarterly-review elf's reviews: one JSON file per review (yyyy-MM-dd_HH-mm-ss.json), written when you click
 * Write the review on the Quarterly tab. The desk only reads them.
 */
@ApplicationScoped
public class QuarterlyElf {

    private static final Logger LOG = Logger.getLogger(QuarterlyElf.class);
    static final Pattern ID = Pattern.compile("\\d{4}-\\d{2}-\\d{2}_\\d{2}-\\d{2}-\\d{2}");
    /** A quarter, as the elf takes it: 2026-Q3. */
    public static final Pattern QUARTER = Pattern.compile("\\d{4}-Q[1-4]");
    static final int HISTORY = 20;

    /**
     * A review.
     *
     * @param quarter        2026-Q3
     * @param title          Q3 2026
     * @param until          the last day it covers (before the quarter's end while the quarter is still on)
     * @param prs            merged PRs it was written from
     * @param issues         closed issues it was written from
     * @param weeklyStatuses weekly statuses sent in the quarter it read
     * @param body           the review, Markdown: "# Q3 2026", "## Project" sections, "- " bullets
     */
    public record Review(String id, LocalDateTime at, Instant generatedAt, String quarter, String title, String start,
            String end, String until, int prs, int issues, int weeklyStatuses, String body) {
    }

    @ConfigProperty(name = "elfdesk.quarterly.runs-dir")
    Path dir;

    @Inject
    ObjectMapper mapper;

    /** Reviews, newest first, as run infos (status: the quarter's title) for the run picker. */
    public List<TriageElf.RunInfo> runs() {
        List<TriageElf.RunInfo> out = new ArrayList<>();
        for (String id : ids()) {
            Review r = review(id);
            if (r != null) out.add(new TriageElf.RunInfo(id, r.at(), r.title()));
            if (out.size() == HISTORY) break;
        }
        return out;
    }

    /** A review by id, or the newest (id null); null when there is none. */
    public Review review(String id) {
        if (id == null) {
            List<String> ids = ids();
            if (ids.isEmpty()) return null;
            id = ids.get(0);
        }
        if (!ID.matcher(id).matches()) throw new IllegalArgumentException("Not a review: " + id);
        Path file = dir.resolve(id + ".json");
        if (!Files.isRegularFile(file)) return null;
        try {
            JsonNode d = mapper.readTree(file.toFile());
            String generated = d.path("generatedAt").asText(null);
            return new Review(id, LocalDateTime.parse(id.replace('_', 'T').replaceFirst("T(\\d+)-(\\d+)-", "T$1:$2:")),
                    generated == null ? null : Instant.parse(generated), d.path("quarter").asText(),
                    d.path("title").asText(), d.path("start").asText(), d.path("end").asText(), d.path("until").asText(),
                    d.path("prs").asInt(), d.path("issues").asInt(), d.path("weeklyStatuses").asInt(), d.path("body").asText());
        } catch (IOException | RuntimeException e) {
            LOG.warnf("Cannot read %s: %s", file, e.getMessage());
            return null;
        }
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
