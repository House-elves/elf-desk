package io.github.houseelves.desk.board;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import io.github.houseelves.desk.github.GitHub;
import io.github.houseelves.desk.github.Item;
import io.github.houseelves.desk.merge.MergeService;
import io.github.houseelves.desk.mvnpm.MvnpmElf;
import io.github.houseelves.desk.mvnpm.MvnpmRuns;
import io.github.houseelves.desk.thread.ThreadView;

/**
 * Everything the board page renders, in display order.
 *
 * @param lanes     the four work lanes: review, mine, assigned, involved
 * @param hidden    open items you're involved in from repos outside the scope
 * @param ignored   items you chose to ignore (someone else is handling them), whatever lane they'd be in
 * @param truncated GitHub had more results than one search can return, so some items are missing
 * @param threads   the desk's threads by item key, so rows can show working / draft ready
 * @param refreshing this came from a stale cache and newer data is on its way (pushed as desk.board)
 * @param merges    merges: in progress, and recently finished, by item key
 * @param returned  ignored items that came back in the last day, with why
 * @param linkedPrs open PRs that fix or mention an issue, by the issue's key
 */
public record Board(
        String login,
        Instant fetchedAt,
        int refreshMinutes,
        boolean scopeEmpty,
        boolean truncated,
        List<Lane> lanes,
        MvnpmLane mvnpm,
        List<Item> hidden,
        List<Item> ignored,
        Map<String, ThreadView.Summary> threads,
        boolean runnerConnected,
        boolean refreshing,
        Map<String, MergeService.View> merges,
        Map<String, String> returned,
        MvnpmRuns.State mvnpmChecks,
        Map<String, String> sinceReview,
        Map<String, List<GitHub.LinkedPr>> linkedPrs) {

    /** The same board with the desk's own state (threads, merges, runner) added. */
    public Board with(Map<String, ThreadView.Summary> threads, Map<String, MergeService.View> merges,
            boolean runnerConnected) {
        return new Board(login, fetchedAt, refreshMinutes, scopeEmpty, truncated, lanes, mvnpm, hidden, ignored, threads,
                runnerConnected, refreshing, merges, returned, mvnpmChecks, sinceReview, linkedPrs);
    }

    /** Adds which PRs you reviewed moved on since ("New commits since your review"). */
    public Board sinceReview(Map<String, String> since) {
        return new Board(login, fetchedAt, refreshMinutes, scopeEmpty, truncated, lanes, mvnpm, hidden, ignored, threads,
                runnerConnected, refreshing, merges, returned, mvnpmChecks, since, linkedPrs);
    }

    /** Adds which mvnpm-dependabot checks are running now. */
    public Board mvnpmChecks(MvnpmRuns.State checks) {
        return new Board(login, fetchedAt, refreshMinutes, scopeEmpty, truncated, lanes, mvnpm, hidden, ignored, threads,
                runnerConnected, refreshing, merges, returned, checks, sinceReview, linkedPrs);
    }

    /** Adds why recently un-ignored items came back ("Review requested", "You were mentioned"). */
    public Board returned(Map<String, String> returned) {
        return new Board(login, fetchedAt, refreshMinutes, scopeEmpty, truncated, lanes, mvnpm, hidden, ignored, threads,
                runnerConnected, refreshing, merges, returned, mvnpmChecks, sinceReview, linkedPrs);
    }

    /** Adds the open PRs that fix or mention each issue. */
    public Board linkedPrs(Map<String, List<GitHub.LinkedPr>> links) {
        return new Board(login, fetchedAt, refreshMinutes, scopeEmpty, truncated, lanes, mvnpm, hidden, ignored, threads,
                runnerConnected, refreshing, merges, returned, mvnpmChecks, sinceReview, links);
    }

    /** Marks a board served from a stale cache while fresh data is being fetched. */
    public Board refreshing(boolean refreshing) {
        return new Board(login, fetchedAt, refreshMinutes, scopeEmpty, truncated, lanes, mvnpm, hidden, ignored, threads,
                runnerConnected, refreshing, merges, returned, mvnpmChecks, sinceReview, linkedPrs);
    }

    public record Lane(String id, String title, List<Item> items) {
    }

    /**
     * @param installed false when mvnpm-dependabot isn't set up on this machine
     * @param repos     the repos that elf watches
     */
    public record MvnpmLane(boolean installed, List<String> repos, List<MvnpmRow> rows) {
    }

    /**
     * @param check   the elf's last recorded check, or null if it never checked this PR
     * @param current true when that check was made at the PR's current head commit
     */
    public record MvnpmRow(Item item, MvnpmElf.Check check, boolean current) {
    }
}
