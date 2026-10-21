package io.github.houseelves.desk.runner;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import io.github.houseelves.desk.mvnpm.MvnpmRuns;
import io.github.houseelves.desk.thread.ThreadService;
import jakarta.enterprise.inject.Instance;
import io.quarkus.websockets.next.WebSocketConnection;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * The one runner connection. The runner dials in (the home box accepts no
 * connections) and must say hello with the shared token before anything else.
 * See the protocol section of the design doc.
 */
@ApplicationScoped
public class RunnerHub {

    private static final Logger LOG = Logger.getLogger(RunnerHub.class);

    @ConfigProperty(name = "elfdesk.runner.token")
    Optional<String> token;

    @Inject
    ObjectMapper mapper;

    @Inject
    ThreadService threads;

    @Inject
    Live live;

    /** Lazy: MvnpmRuns needs the hub too. */
    @Inject
    Instance<MvnpmRuns> mvnpmRuns;

    /** Lazy, for the same reason. */

    private volatile WebSocketConnection runner;
    private volatile String runnerVersion = "";
    /** The elves' runs going now (elf:command, or a built-in tab's mode), as the runner last said. */
    private volatile List<String> elvesRunning = List.of();
    /** Per run key going now, since when (as far as the desk knows); per key whose last run failed, its log's end. */
    private final Map<String, java.time.Instant> elvesSince = new ConcurrentHashMap<>();
    private volatile Map<String, String> elvesFailed = Map.of();

    /**
     * A message too big for one WebSocket message (a job's answer with a large patch) comes as parts, each a piece
     * of its JSON text. The socket keeps its default size limit because it takes connections before their hello;
     * parts are only put together for the runner once it has said hello, and up to MAX_PARTS.
     */
    static final int MAX_PARTS = 128, MAX_CHARS = 4 * 1024 * 1024;
    private StringBuilder parts;
    private int partsNext, partsOf;

    /** The first runner versions that carry out GitHub writes (github.write), and merges in particular. */
    static final String WRITES_SINCE = "0.2.0";
    static final String MERGE_SINCE = "0.3.0";
    static final String CI_SINCE = "0.4.0";
    static final String APPROVE_SINCE = "0.5.0";
    static final String UPDATE_BRANCH_SINCE = "0.6.0";
    static final String REOPEN_SINCE = "0.7.0";
    public static final String MVNPM_SINCE = "0.8.0";
    static final String SQUASH_SINCE = "0.9.0";
    static final String ASSIGN_SINCE = "0.10.0";
    static final String ISSUE_SINCE = "0.11.0";
    public static final String APPROVE_DEPLOYMENT_SINCE = "0.27.0";
    public static final String ELF_CONFIG_SINCE = "0.28.0";
    public static final String ELVES_SINCE = "0.30.0";
    /** The first runner whose elves draw their own tabs: their UI files, data, actions and failed runs. */
    public static final String ELF_TABS_SINCE = "0.31.0";
    static final String PR_SINCE = "0.15.0";
    static final String READY_PR_SINCE = "0.21.0";

    /** Requests waiting for the runner's answer, by request id. */
    private final Map<String, CompletableFuture<JsonNode>> pending = new ConcurrentHashMap<>();

    public List<String> elvesRunning() {
        return elvesRunning;
    }

    /** {running: {key: since}, failed: {key: log}}: the elves' runs, for their tabs. */
    public ObjectNode elvesState() {
        ObjectNode out = mapper.createObjectNode();
        ObjectNode running = out.putObject("running");
        elvesSince.forEach((k, v) -> running.put(k, v.toString()));
        ObjectNode failed = out.putObject("failed");
        elvesFailed.forEach(failed::put);
        return out;
    }

    public boolean connected() {
        WebSocketConnection c = runner;
        return c != null && c.isOpen();
    }

    public ObjectNode message(String type) {
        return mapper.createObjectNode().put("type", type);
    }

    /** Sends to the runner; false when none is connected (the job stays queued for its hello). */
    public boolean send(ObjectNode msg) {
        WebSocketConnection c = runner;
        if (c == null || !c.isOpen()) return false;
        try {
            c.sendTextAndAwait(mapper.writeValueAsString(msg));
            return true;
        } catch (Exception e) {
            LOG.warnf("Sending %s to the runner failed: %s", msg.path("type").asText(), e.getMessage());
            return false;
        }
    }

    /**
     * Sends a request the runner answers (github.write -> github.result). Fails at once when no runner is
     * connected; the caller decides how long to wait.
     */
    public CompletableFuture<JsonNode> request(ObjectNode msg, String minVersion) {
        String id = UUID.randomUUID().toString();
        msg.put("id", id);
        CompletableFuture<JsonNode> answer = new CompletableFuture<>();
        if (connected() && older(runnerVersion, minVersion)) {
            answer.completeExceptionally(new IllegalStateException("The runner (" + runnerVersion
                    + ") is too old for this; it needs " + minVersion + ". Restart it so it picks up the current "
                    + "version, then try again."));
            return answer;
        }
        pending.put(id, answer);
        answer.whenComplete((r, e) -> pending.remove(id));
        if (!send(msg)) {
            answer.completeExceptionally(new IllegalStateException(
                    "The runner is offline, so nothing was sent to GitHub. Start the runner and try again."));
        }
        return answer;
    }

    void onMessage(WebSocketConnection c, String text) {
        onMessage(c, text, false);
    }

    private void onMessage(WebSocketConnection c, String text, boolean assembled) {
        JsonNode msg;
        try {
            msg = mapper.readTree(text);
        } catch (Exception e) {
            LOG.warn("Unreadable message from a runner connection; closing it");
            c.closeAndAwait();
            return;
        }
        String type = msg.path("type").asText();
        if (type.equals("hello") && !assembled) {
            hello(c, msg);
            return;
        }
        if (c != runner) {
            LOG.warn("Runner message before hello; closing the connection");
            c.closeAndAwait();
            return;
        }
        if (type.equals("part") || (assembled && type.equals("hello"))) {
            String whole = assembled ? null : part(msg);
            if (whole != null) onMessage(c, whole, true);
            return;
        }
        if (type.equals("mvnpm.state")) {
            List<String> units = new ArrayList<>();
            msg.path("running").forEach(u -> units.add(u.asText()));
            mvnpmRuns.get().onState(units);
            return;
        }
        if (type.equals("elves.state")) {
            List<String> keys = new ArrayList<>();
            msg.path("running").forEach(k -> keys.add(k.asText()));
            elvesRunning = List.copyOf(keys);
            elvesSince.keySet().retainAll(keys);
            keys.forEach(k -> elvesSince.putIfAbsent(k, java.time.Instant.now()));
            Map<String, String> failed = new HashMap<>();
            msg.path("failed").fields().forEachRemaining(f -> failed.put(f.getKey(), f.getValue().asText()));
            elvesFailed = Map.copyOf(failed);
            live.elves();
            return;
        }
                if (type.equals("github.result") || type.equals("git.result") || type.equals("mvnpm.result")
                || type.equals("elf.config.result") || type.equals("elves.result")) {
            CompletableFuture<JsonNode> answer = pending.get(msg.path("id").asText());
            if (answer != null) answer.complete(msg);
            return;
        }
        long job = msg.path("job").asLong();
        switch (type) {
            case "job.accepted" -> threads.jobAccepted(job);
            case "job.event" -> threads.jobLine(job, msg.path("text").asText(""));
            case "job.file" -> threads.jobFile(job, msg.path("name").asText(null), msg.path("part").asInt(-1),
                    msg.path("parts").asInt(0), msg.path("data").asText(null));
            case "job.done" -> threads.jobDone(job, msg.path("session").asText(null), msg.path("output"),
                    msg.path("cost").isNumber() ? msg.path("cost").decimalValue() : null,
                    msg.path("git").isObject() ? msg.path("git") : null);
            case "job.failed" -> threads.jobFailed(job, msg.path("error").asText("The runner reported a failure"));
            default -> LOG.debugf("Ignoring runner message %s", type);
        }
    }

    /** Adds a part; the whole message once its last part is in, else null. Parts out of order drop the message. */
    private synchronized String part(JsonNode msg) {
        int part = msg.path("part").asInt(-1), of = msg.path("parts").asInt(0);
        String data = msg.path("data").asText(null);
        if (part == 0) {
            parts = new StringBuilder();
            partsNext = 0;
            partsOf = of;
        }
        if (data == null || parts == null || part != partsNext || of != partsOf || of < 2 || of > MAX_PARTS) {
            LOG.warnf("Runner message part %d of %d out of order; dropping the message", part, of);
            parts = null;
            return null;
        }
        if (parts.length() + data.length() > MAX_CHARS) {
            LOG.warnf("Runner message over %d characters; dropping it", MAX_CHARS);
            parts = null;
            return null;
        }
        parts.append(data);
        if (++partsNext < partsOf) return null;
        String whole = parts.toString();
        parts = null;
        return whole;
    }

    private synchronized void dropParts() {
        parts = null;
    }

    private void hello(WebSocketConnection c, JsonNode msg) {
        if (!tokenMatches(msg.path("token").asText(""))) {
            LOG.warn("Runner hello with a wrong token; closing the connection");
            c.closeAndAwait();
            return;
        }
        WebSocketConnection previous = runner;
        dropParts();
        runner = c;
        runnerVersion = msg.path("version").asText("");
        if (previous != null && previous != c && previous.isOpen()) previous.closeAndAwait();
        Set<Long> known = new HashSet<>();
        msg.path("jobs").forEach(j -> known.add(j.asLong()));
        LOG.infof("Runner connected (%s), it holds jobs %s", msg.path("version").asText("?"), known);
        live.runner(true);
        threads.runnerConnected(known);
    }

    void onClose(WebSocketConnection c) {
        if (c == runner) {
            runner = null;
            dropParts();
            LOG.info("Runner disconnected");
            mvnpmRuns.get().onRunnerGone();
            elvesRunning = List.of();
            elvesSince.clear();
            pending.values().forEach(p -> p.completeExceptionally(new IllegalStateException(
                    "The runner disconnected before GitHub answered. Check GitHub before trying again.")));
            live.runner(false);
        }
    }

    /** True when version a is older than b (dotted numbers; anything unreadable counts as old). */
    static boolean older(String a, String b) {
        String[] x = a.split("\\."), y = b.split("\\.");
        for (int i = 0; i < Math.max(x.length, y.length); i++) {
            int xi, yi;
            try {
                xi = i < x.length ? Integer.parseInt(x[i]) : 0;
                yi = i < y.length ? Integer.parseInt(y[i]) : 0;
            } catch (NumberFormatException e) {
                return true;
            }
            if (xi != yi) return xi < yi;
        }
        return false;
    }

    boolean tokenMatches(String offered) {
        String expected = token.orElse("");
        // An unset token refuses every runner.
        return !expected.isBlank() && MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),
                offered.getBytes(StandardCharsets.UTF_8));
    }
}
