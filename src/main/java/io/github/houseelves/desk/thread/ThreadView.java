package io.github.houseelves.desk.thread;

import java.time.Instant;
import java.util.List;

import io.github.houseelves.desk.agent.AgentOutput;

/** Everything the thread page shows for one issue or PR. {@code own}: it's your PR (reviews can only comment). */
public record ThreadView(
        String itemKey,
        String kind,
        String title,
        String url,
        boolean runnerConnected,
        List<TurnView> turns,
        JobView job,
        DraftView draft,
        List<PostedView> posted,
        boolean own) {

    public record TurnView(long id, String role, String action, String text, Instant created) {
    }

    /** The open job (queued or running), with the output lines so far. */
    public record JobView(long id, String action, String state, Instant created, Instant started, List<String> lines) {
    }

    /** {@code draft} for reviews and comments, {@code push} for commits to push. */
    public record DraftView(long id, String kind, AgentOutput.DraftOut draft, PushOut push, Instant created) {
    }

    public record PostedView(String kind, String url, Instant at) {
    }

    /** One line per thread for the board. state: working, draft or idle. */
    public record Summary(String itemKey, String state) {
    }
}
