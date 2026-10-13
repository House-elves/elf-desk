package io.github.houseelves.desk.mvnpm;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.TimeUnit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import io.github.houseelves.desk.board.BoardService;
import io.github.houseelves.desk.runner.Live;
import io.github.houseelves.desk.runner.RunnerHub;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * Running mvnpm-dependabot from the desk (instead of its morning timer). The runner starts it as systemd units
 * on the host and reports which run; when they finish, the board refreshes to show the elf's new verdicts.
 */
@ApplicationScoped
public class MvnpmRuns {

    @Inject
    RunnerHub hub;

    @Inject
    Live live;

    @Inject
    BoardService boards;

    private volatile List<String> running = List.of();
    private volatile Instant since;

    /** @param running the systemd units checking right now; empty when idle */
    public record State(List<String> running, Instant since) {
    }

    public State state() {
        return new State(running, since);
    }

    /** A full round over every repo ({@code repo} null), or one PR, which is re-checked even if already checked. */
    public void run(String repo, Integer pr) {
        if (repo != null && !repo.matches("[A-Za-z0-9-]+/[A-Za-z0-9._-]+")) throw new IllegalArgumentException("Bad repo");
        if ((repo == null) != (pr == null || pr <= 0)) throw new IllegalArgumentException("Give both repo and pr, or neither");
        ObjectNode msg = hub.message("mvnpm.run");
        if (repo != null) msg.put("repo", repo).put("pr", pr);
        JsonNode result;
        try {
            result = hub.request(msg, RunnerHub.MVNPM_SINCE).get(60, TimeUnit.SECONDS);
        } catch (Exception e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            throw new IllegalStateException(cause.getMessage());
        }
        if (result.path("status").asInt() != 200) {
            throw new IllegalStateException(result.path("error").asText("The runner couldn't start the check"));
        }
        // The runner reports its units a few seconds later; until then the board shows the check as running.
        String unit = repo == null ? "mvnpm-dependabot.service"
                : "mvnpm-dependabot-pr-" + repo.replace('/', '-') + "-" + pr + ".service";
        synchronized (this) {
            if (!running.contains(unit)) {
                if (running.isEmpty()) since = Instant.now();
                running = java.util.stream.Stream.concat(running.stream(), java.util.stream.Stream.of(unit)).toList();
            }
        }
        live.boardUpdated();
    }

    /** From the runner: which checks run now. When the last one finishes, the board refreshes. */
    public synchronized void onState(List<String> now) {
        boolean finished = !running.isEmpty() && now.isEmpty();
        if (running.isEmpty() && !now.isEmpty()) since = Instant.now();
        running = List.copyOf(now);
        if (finished) {
            since = null;
            boards.expire(); // the elf commented and approved: fetch GitHub again
        }
        live.boardUpdated();
    }

    /** The runner went away: its report of running checks is no longer known. */
    public synchronized void onRunnerGone() {
        running = List.of();
        since = null;
    }
}
