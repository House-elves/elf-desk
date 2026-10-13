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

    /** {@code draft} for reviews, comments and issues, {@code push} for commits to push, {@code pr} for a new PR. */
    public record DraftView(long id, String kind, AgentOutput.DraftOut draft, PushOut push, PrOut pr, Instant created) {
    }

    public record PostedView(String kind, String url, Instant at) {
    }

    /**
     * One line per thread for the board.
     *
     * @param state      working, draft, answered or idle
     * @param action     what the agent is doing (working) or last did: review, investigate, fix, ci-why, ...
     * @param draftKind  the pending draft's kind (review, comment, push, pr, issue), or null
     * @param since      when the current state began: the job started, the draft or answer arrived, or the last job ended
     * @param failed     the last job failed or was cancelled (state idle)
     * @param postedKind what you last posted from here (review, comment, push, pr, issue), or null
     * @param postedAt   when
     */
    public record Summary(String itemKey, String state, String action, String draftKind, java.time.Instant since,
            boolean failed, String postedKind, java.time.Instant postedAt) {

        public Summary(String itemKey, String state) {
            this(itemKey, state, null, null, null, false, null, null);
        }
    }
}
