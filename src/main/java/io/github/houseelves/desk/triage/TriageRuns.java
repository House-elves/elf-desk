package io.github.houseelves.desk.triage;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import com.fasterxml.jackson.databind.JsonNode;

import io.github.houseelves.desk.runner.Live;
import io.github.houseelves.desk.runner.RunnerHub;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * Running daily-triage from the desk, instead of its morning timer and email. The runner starts it as a systemd
 * unit per mode and reports which run and which failed; when one finishes, open pages read the saved run.
 */
@ApplicationScoped
public class TriageRuns {

    /** The email triage, the Zulip digest, and the weekly-status elf's draft (it runs the same way). */
    public static final String EMAIL = "email", ZULIP = "zulip", STATUS = "status";
    static final Set<String> MODES = Set.of(EMAIL, ZULIP, STATUS);

    @Inject
    RunnerHub hub;

    @Inject
    Live live;

    private final Map<String, Instant> running = new ConcurrentHashMap<>();
    private volatile Map<String, String> failed = Map.of();

    /**
     * @param running per mode running now, since when (as far as the desk knows)
     * @param failed  per mode whose last run failed, the end of its log
     */
    public record State(Map<String, Instant> running, Map<String, String> failed) {
    }

    public State state() {
        return new State(Map.copyOf(running), failed);
    }

    public void run(String mode) {
        if (!MODES.contains(mode)) throw new IllegalArgumentException("Bad mode: " + mode);
        JsonNode result;
        try {
            result = hub.request(hub.message("triage.run").put("mode", mode),
                    switch (mode) {
                        case EMAIL -> RunnerHub.EMAIL_TRIAGE_SINCE;
                        case STATUS -> RunnerHub.STATUS_SINCE;
                        default -> RunnerHub.TRIAGE_SINCE;
                    })
                    .get(60, TimeUnit.SECONDS);
        } catch (Exception e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            throw new IllegalStateException(cause.getMessage());
        }
        if (result.path("status").asInt() != 200) {
            throw new IllegalStateException(result.path("error").asText("The runner couldn't start daily-triage"));
        }
        running.putIfAbsent(mode, Instant.now());
        Map<String, String> f = new HashMap<>(failed);
        f.remove(mode);
        failed = Map.copyOf(f);
        live.triage(mode);
    }

    /** From the runner: which modes run now, and the failed ones' logs. */
    public void onState(List<String> now, Map<String, String> failedNow) {
        for (String mode : List.copyOf(running.keySet())) {
            if (!now.contains(mode)) {
                running.remove(mode);
                live.triage(mode); // finished: pages read the new run
            }
        }
        for (String mode : now) {
            if (MODES.contains(mode) && running.putIfAbsent(mode, Instant.now()) == null) live.triage(mode);
        }
        if (!failedNow.equals(failed)) {
            failed = Map.copyOf(failedNow);
            failedNow.keySet().forEach(live::triage);
        }
    }

    /** The runner went away: what runs is no longer known. */
    public void onRunnerGone() {
        running.clear();
    }
}
