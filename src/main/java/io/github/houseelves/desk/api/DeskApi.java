package io.github.houseelves.desk.api;

import java.util.List;

import io.github.houseelves.desk.agent.AgentOutput;
import io.github.houseelves.desk.board.Board;
import io.github.houseelves.desk.board.BoardService;
import io.github.houseelves.desk.ci.CiService;
import io.github.houseelves.desk.conflicts.ConflictService;
import io.github.houseelves.desk.github.GitHub;
import io.github.houseelves.desk.github.Item;
import io.github.houseelves.desk.ignore.IgnoreService;
import io.github.houseelves.desk.runner.Live;
import io.github.houseelves.desk.merge.MergeService;
import io.github.houseelves.desk.mvnpm.MvnpmElf;
import io.github.houseelves.desk.mvnpm.MvnpmRuns;
import io.github.houseelves.desk.runner.RunnerHub;
import io.github.houseelves.desk.scope.ScopeService;
import io.github.houseelves.desk.security.CurrentUser;
import io.github.houseelves.desk.security.OwnerRoleAugmentor;
import io.github.houseelves.desk.settings.Settings;
import io.github.houseelves.desk.thread.Poster;
import io.github.houseelves.desk.thread.ThreadService;
import io.github.houseelves.desk.thread.ThreadView;
import io.quarkiverse.jsonrpc.api.JsonRPCApi;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;

/**
 * The UI's API over JSON-RPC (scope "desk"). Only {@link #post} (after
 * {@link #previewPost}, for reviews, comments and pushes), {@link #close},
 * {@link #mergeOnGreen} and {@link #ciRerun} lead to GitHub writes, each from
 * a click in the page, and all go through the runner.
 */
@JsonRPCApi("desk")
@RolesAllowed(OwnerRoleAugmentor.OWNER)
public class DeskApi {

    @Inject
    CurrentUser user;

    @Inject
    BoardService boards;

    @Inject
    ScopeService scopes;

    @Inject
    Settings settings;

    @Inject
    MvnpmElf mvnpm;

    @Inject
    ThreadService threads;

    @Inject
    Poster poster;

    @Inject
    RunnerHub runner;

    @Inject
    MergeService merges;

    @Inject
    CiService ci;

    @Inject
    GitHub github;

    @Inject
    ConflictService conflicts;

    @Inject
    Live live;

    @Inject
    IgnoreService ignores;

    @Inject
    MvnpmRuns mvnpmRuns;

    // ---- board ----

    /** The board. {@code refresh} skips the short GitHub cache. */
    public Board board(boolean refresh) {
        // Merge on green reads PR state in the background with your latest read token.
        merges.rememberToken(user.login(), user.githubToken());
        return boards.board(user.login(), user.githubToken(), refresh)
                .with(threads.summaries(), merges.recent(), runner.connected())
                .mvnpmChecks(mvnpmRuns.state());
    }

    // ---- threads ----

    /** The thread for one item (owner/repo#number), or null if there is none yet. */
    /**
     * The item as it is on GitHub now (null once it's closed or merged), for the thread page: what it offers
     * depends on it, and it changes with what you do there. The board's copy is updated too.
     */
    public Item item(String itemKey) {
        if (!itemKey.matches("[A-Za-z0-9-]+/[A-Za-z0-9._-]+#\\d+")) throw new IllegalArgumentException("Not an item: " + itemKey);
        return boards.refreshItem(user.login(), user.githubToken(), itemKey);
    }

    public ThreadView thread(String itemKey) {
        return threads.view(itemKey);
    }

    /** Start an agent: action is review (PR), investigate (issue) or ask (question required). */
    public ThreadView start(String itemKey, String action, String question) {
        return threads.start(itemKey, action, question, user.login(), user.githubToken());
    }

    public ThreadView reply(String itemKey, String text) {
        return threads.reply(itemKey, text, user.login(), user.githubToken());
    }

    public ThreadView cancel(String itemKey) {
        return threads.cancel(itemKey);
    }

    public ThreadView updateDraft(long draftId, AgentOutput.DraftOut draft) {
        return threads.updateDraft(draftId, draft);
    }

    public ThreadView discardDraft(long draftId) {
        return threads.discardDraft(draftId);
    }

    /** Step one of posting: exactly what will be sent, and a one-time confirmation. */
    public Poster.Preview previewPost(long draftId) {
        return poster.preview(draftId, user.githubToken());
    }

    /** Step two: post to GitHub as you, through the runner's gh login. */
    public Poster.Posted post(long draftId, String confirm) {
        Poster.Posted posted = poster.post(draftId, confirm, user.login());
        // A push starts CI again: show it running now, not the old result until the next fetch.
        if (posted.pushedTo() != null) boards.markCi(user.login(), posted.pushedTo(), "PENDING");
        return posted;
    }

    /** Close an issue or PR on GitHub, as you. The page confirms first. Returns its URL. */
    public String close(String itemKey) {
        String url = poster.close(itemKey, user.login());
        boards.forget(user.login(), itemKey);
        return url;
    }

    /** Assign yourself to an issue or PR on GitHub (through the runner). */
    public boolean assignMe(String itemKey) {
        poster.assign(itemKey, user.login());
        boards.markAssigned(user.login(), itemKey);
        return true;
    }

    // ---- mvnpm-dependabot ----

    /** Run mvnpm-dependabot now: every repo (repo and pr null), or one PR (re-checked even if already checked). */
    public MvnpmRuns.State mvnpmRun(String repo, Integer pr) {
        mvnpmRuns.run(repo, pr);
        return mvnpmRuns.state();
    }

    // ---- what's new ----

    /** What happened on a PR since your last review or comment: commits, reviews, comments, review requests. */
    public GitHub.Activity prActivity(String itemKey) {
        String repo = itemKey.substring(0, itemKey.indexOf('#'));
        int number = Integer.parseInt(itemKey.substring(itemKey.indexOf('#') + 1));
        return github.activity(user.githubToken(), user.login(), repo, number);
    }

    // ---- ignore ----

    /** Move an item to Ignored: you're involved, but someone else is handling it. Nothing changes on GitHub. */
    public void ignore(String itemKey) {
        ignores.ignore(itemKey, user.login());
        live.boardUpdated();
    }

    public void unignore(String itemKey) {
        ignores.unignore(itemKey);
        live.boardUpdated();
    }

    public boolean isIgnored(String itemKey) {
        return ignores.isIgnored(itemKey);
    }

    // ---- failing CI ----

    /** What failed on a PR's head, whether there are Actions runs to re-run, and whether a fix could be pushed. */
    public GitHub.CiReport ciReport(String itemKey) {
        return ci.report(itemKey, user.githubToken());
    }

    /** Re-run the failed GitHub Actions jobs (through the runner). Returns how many workflow runs. */
    public int ciRerun(String itemKey) {
        int runs = ci.rerun(itemKey, user.login(), user.githubToken());
        boards.markCi(user.login(), itemKey, "PENDING"); // CI is running again: don't keep showing the failure
        return runs;
    }

    /** Your own PR, no write access to the repo: close and reopen it so CI runs again. */
    public void ciReopen(String itemKey) {
        ci.reopenToRerun(itemKey, user.login(), user.githubToken());
        boards.markCi(user.login(), itemKey, "PENDING");
    }

    /** "Approve and run": approve the workflow runs waiting for a maintainer on a fork PR. Returns how many runs. */
    public int ciApprove(String itemKey) {
        int runs = ci.approve(itemKey, user.login(), user.githubToken());
        boards.markCi(user.login(), itemKey, "PENDING");
        return runs;
    }

    /** The agent on why CI is failing: opens the existing answer if there is one for this commit (or it's working). */
    public ThreadView ciWhy(String itemKey) {
        return ci.why(itemKey, user.login(), user.githubToken(), false);
    }

    /** Ask why CI is failing again, even if it already answered for this commit. */
    public ThreadView ciWhyAgain(String itemKey) {
        return ci.why(itemKey, user.login(), user.githubToken(), true);
    }

    /** Start the agent on fixing CI; its commits come back as a push draft for you to confirm. */
    public ThreadView ciFix(String itemKey) {
        return ci.fix(itemKey, user.login(), user.githubToken());
    }

    // ---- merge conflicts ----

    public ConflictService.Options conflictOptions(String itemKey) {
        return conflicts.options(itemKey, user.githubToken());
    }

    /** Comment "@dependabot rebase" on a Dependabot PR (as you, through the runner). Returns the comment's URL. */
    public String askDependabotToRebase(String itemKey) {
        return conflicts.askDependabotToRebase(itemKey, user.login(), user.githubToken());
    }

    /** Bring a PR that's behind its base up to date: method REBASE (Dependabot PRs: "@dependabot rebase"). */
    public String updateBranch(String itemKey, String method) {
        String done = conflicts.updateBranch(itemKey, method, user.login(), user.githubToken());
        boards.markCi(user.login(), itemKey, "PENDING"); // new head: CI runs again
        return done;
    }

    /** Start the agent on merging the base branch in; its merge commit comes back as a push draft. */
    public ThreadView resolveConflicts(String itemKey) {
        return conflicts.resolve(itemKey, user.login(), user.githubToken());
    }

    /** Commits on the PR: more than one is offered "Squash into one commit". */
    public int commitCount(String itemKey) {
        return conflicts.commitCount(itemKey, user.githubToken());
    }

    /** The runner squashes the PR into one commit on its base; it comes back as a push you confirm. */
    public ThreadView squash(String itemKey) {
        return conflicts.squash(itemKey, user.githubToken());
    }

    // ---- merge on green ----

    /** What the confirmation needs: allowed methods, your usual one, CI state, and the head you're agreeing to. */
    public MergeService.Options mergeOptions(String itemKey) {
        return merges.options(itemKey, user.githubToken());
    }

    /** Merge when CI is green, pinned to {@code headSha} (from mergeOptions). Merges at once if it's green already. */
    public MergeService.View mergeOnGreen(String itemKey, String method, String headSha) {
        return merges.request(itemKey, method, headSha, user.login(), user.githubToken());
    }

    /** Merge anyway: green CI but no approval, because you know it's fine (e.g. a routine parent bump). */
    public MergeService.View mergeWithoutApproval(String itemKey, String method, String headSha) {
        return merges.request(itemKey, method, headSha, user.login(), user.githubToken(), true);
    }

    public MergeService.View cancelMerge(String itemKey) {
        return merges.cancel(itemKey);
    }

    /** The latest merge on green for a PR, or null. */
    public MergeService.View mergeStatus(String itemKey) {
        return merges.view(itemKey);
    }

    // ---- settings ----

    public record ScopeRow(long id, String kind, String name, String mode) {
    }

    public record SettingsView(String login, List<ScopeRow> scope, boolean mvnpmInstalled, List<String> mvnpmRepos,
            int refreshMinutes) {
    }

    public SettingsView settings() {
        List<ScopeRow> rows = scopes.rows().stream()
                .map(w -> new ScopeRow(w.id, w.kind.name(), w.name, w.mode.name()))
                .toList();
        return new SettingsView(user.login(), rows, mvnpm.installed(), mvnpm.repos(), settings.refreshMinutes());
    }

    public List<BoardService.Suggestion> suggestions() {
        return boards.suggestions(user.login(), user.githubToken());
    }

    /** Watch an org, a user, or one owner/repo. */
    public SettingsView watch(String name) {
        scopes.watch(name);
        return settings();
    }

    /** First-run save: watch these owners in one go. */
    public SettingsView watchAll(List<String> owners) {
        scopes.watchAll(owners);
        return settings();
    }

    /** Stop showing one repo of a watched org. */
    public SettingsView exclude(String repo) {
        scopes.exclude(repo);
        return settings();
    }

    public SettingsView remove(long id) {
        scopes.remove(id);
        return settings();
    }

    public SettingsView setRefreshMinutes(int minutes) {
        settings.setRefreshMinutes(minutes);
        return settings();
    }
}
