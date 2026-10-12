package io.github.houseelves.desk.runner;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import io.quarkiverse.jsonrpc.api.JsonRPCBroadcaster;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * Pushes changes to open pages over JSON-RPC, and keeps the recent output
 * lines of running jobs so a page opened mid-run can catch up. Lines are not
 * persisted: only a job's final reply is.
 */
@ApplicationScoped
public class Live {

    public static final String THREAD = "desk.thread";
    public static final String RUNNER = "desk.runner";
    public static final String BOARD = "desk.board";
    static final int KEEP_LINES = 300;

    @Inject
    JsonRPCBroadcaster broadcaster;

    private final Map<Long, List<String>> lines = new ConcurrentHashMap<>();

    /**
     * @param type "line" (agent output; {@code text} set) or "changed" (reload the thread)
     */
    public record ThreadEvent(String itemKey, String type, Long jobId, String text) {
    }

    public void line(String itemKey, long jobId, String text) {
        List<String> buf = lines.computeIfAbsent(jobId, id -> new ArrayList<>());
        synchronized (buf) {
            buf.add(text);
            if (buf.size() > KEEP_LINES) buf.subList(0, buf.size() - KEEP_LINES).clear();
        }
        broadcaster.broadcast(THREAD, new ThreadEvent(itemKey, "line", jobId, text));
    }

    public List<String> lines(long jobId) {
        List<String> buf = lines.get(jobId);
        if (buf == null) return List.of();
        synchronized (buf) {
            return List.copyOf(buf);
        }
    }

    public void finished(long jobId) {
        lines.remove(jobId);
    }

    public void changed(String itemKey) {
        broadcaster.broadcast(THREAD, new ThreadEvent(itemKey, "changed", null, null));
    }

    /** New GitHub data is cached: open boards should reload (cheaply, from the cache). */
    public void boardUpdated() {
        broadcaster.broadcast(BOARD, Map.of("updated", true));
    }

    public void runner(boolean connected) {
        broadcaster.broadcast(RUNNER, Map.of("connected", connected));
    }
}
