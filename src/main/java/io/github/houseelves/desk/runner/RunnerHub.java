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

import io.github.houseelves.desk.triage.TriageRuns;
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
    @Inject
    Instance<TriageRuns> triageRuns;

    private volatile WebSocketConnection runner;
    private volatile String runnerVersion = "";

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
    public static final String TRIAGE_SINCE = "0.12.0";
    public static final String EMAIL_TRIAGE_SINCE = "0.13.0";
    public static final String STATUS_SINCE = "0.24.0";
    public static final String QUARTERLY_SINCE = "0.26.0";
    public static final String APPROVE_DEPLOYMENT_SINCE = "0.27.0";
    public static final String ELF_CONFIG_SINCE = "0.28.0";
    static final String PR_SINCE = "0.15.0";
    static final String READY_PR_SINCE = "0.21.0";

    /** Requests waiting for the runner's answer, by request id. */
    private final Map<String, CompletableFuture<JsonNode>> pending = new ConcurrentHashMap<>();

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
        JsonNode msg;
        try {
            msg = mapper.readTree(text);
        } catch (Exception e) {
            LOG.warn("Unreadable message from a runner connection; closing it");
            c.closeAndAwait();
            return;
        }
        String type = msg.path("type").asText();
        if (type.equals("hello")) {
            hello(c, msg);
            return;
        }
        if (c != runner) {
            LOG.warn("Runner message before hello; closing the connection");
            c.closeAndAwait();
            return;
        }
        if (type.equals("mvnpm.state")) {
            List<String> units = new ArrayList<>();
            msg.path("running").forEach(u -> units.add(u.asText()));
            mvnpmRuns.get().onState(units);
            return;
        }
        if (type.equals("triage.state")) {
            List<String> modes = new ArrayList<>();
            msg.path("running").forEach(m -> modes.add(m.asText()));
            Map<String, String> failed = new HashMap<>();
            msg.path("failed").fields().forEachRemaining(f -> failed.put(f.getKey(), f.getValue().asText()));
            triageRuns.get().onState(modes, failed);
            return;
        }
        if (type.equals("github.result") || type.equals("git.result") || type.equals("mvnpm.result")
                || type.equals("triage.result") || type.equals("elf.config.result")) {
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

    private void hello(WebSocketConnection c, JsonNode msg) {
        if (!tokenMatches(msg.path("token").asText(""))) {
            LOG.warn("Runner hello with a wrong token; closing the connection");
            c.closeAndAwait();
            return;
        }
        WebSocketConnection previous = runner;
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
            LOG.info("Runner disconnected");
            mvnpmRuns.get().onRunnerGone();
            triageRuns.get().onRunnerGone();
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
