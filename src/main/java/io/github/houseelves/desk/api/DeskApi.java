package io.github.houseelves.desk.api;

import java.util.List;

import io.github.houseelves.desk.agent.AgentOutput;
import io.github.houseelves.desk.board.Board;
import io.github.houseelves.desk.board.BoardService;
import io.github.houseelves.desk.ci.CiService;
import io.github.houseelves.desk.elves.ElvesService;
import io.github.houseelves.desk.conflicts.ConflictService;
import io.github.houseelves.desk.github.GitHub;
import io.github.houseelves.desk.github.Item;
import io.github.houseelves.desk.ignore.IgnoreService;
import io.github.houseelves.desk.runner.Live;
import io.github.houseelves.desk.merge.MergeService;
import io.github.houseelves.desk.settings.ElfSettings;
import io.github.houseelves.desk.mvnpm.MvnpmElf;
import io.github.houseelves.desk.mvnpm.MvnpmRuns;
import io.github.houseelves.desk.runner.RunnerHub;
import io.github.houseelves.desk.scope.ScopeService;
import io.github.houseelves.desk.security.CurrentUser;
import io.github.houseelves.desk.security.DeskSignIn;
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
@RolesAllowed(DeskSignIn.OWNER)
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

    @Inject
    ElfSettings elfSettings;

    @Inject
    ElvesService elves;

    // ---- board ----

    /** The board. {@code refresh} skips the short GitHub cache. */
    public Board board(boolean refresh) {
        // Merge on green reads PR state in the background with your latest read token.
        merges.rememberToken(user.login(), user.githubToken());
        return boards.board(user.login(), user.githubToken(), refresh)
                .with(threads.summaries(), merges.recent(), runner.connected())
                .mvnpmChecks(mvnpmRuns.state())
                .withFlows(java.time.Instant.now());
    }

    // ---- threads ----

    /**
     * The item as it is on GitHub now (null once it's closed or merged), for the thread page: what it offers
     * depends on it, and it changes with what you do there. The board's copy is updated too.
     */
    public Item item(String itemKey) {
        if (!itemKey.matches("[A-Za-z0-9-]+/[A-Za-z0-9._-]+#\\d+")) throw new IllegalArgumentException("Not an item: " + itemKey);
        return boards.refreshItem(user.login(), user.githubToken(), itemKey);
    }

    /** What the item is about, from its description on GitHub: for the thread page's recap. */
    public GitHub.About about(String itemKey) {
        if (!itemKey.matches("[A-Za-z0-9-]+/[A-Za-z0-9._-]+#\\d+")) throw new IllegalArgumentException("Not an item: " + itemKey);
        int hash = itemKey.indexOf('#');
        return github.about(user.githubToken(), itemKey.substring(0, hash), Integer.parseInt(itemKey.substring(hash + 1)));
    }

    /** The thread for one item (owner/repo#number), or null if there is none yet. Only the thread page asks: its answers count as seen. */
    public ThreadView thread(String itemKey) {
        ThreadView v = threads.view(itemKey);
        if (v != null && v.job() == null) threads.seen(itemKey);
        return v;
    }

    /** Start an agent: action is review (PR), investigate (issue) or ask (question required). */
    public ThreadView start(String itemKey, String action, String question) {
        return threads.start(itemKey, action, question, user.login(), user.githubToken());
    }

    /** After a self-review: the agent makes the fixes it listed. */
    public ThreadView fixReview(String itemKey) {
        return threads.fixReview(itemKey);
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
        return poster.preview(draftId, user.githubToken(), user.login());
    }

    /** Step two: post to GitHub as you, through the runner's gh login. asDraft: a PR opens as a draft (else ready). */
    public Poster.Posted post(long draftId, String confirm, Boolean asDraft) {
        Poster.Posted posted = poster.post(draftId, confirm, user.login(), asDraft == null || asDraft);
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

    /** A PR's change as a unified diff (the first 2 MB), or null when GitHub won't give one. */
    public GitHub.Diff prDiff(String itemKey) {
        if (!itemKey.matches("[A-Za-z0-9-]+/[A-Za-z0-9._-]+#\\d+")) throw new IllegalArgumentException("Not a PR: " + itemKey);
        return github.prDiff(user.githubToken(), itemKey.substring(0, itemKey.indexOf('#')),
                Integer.parseInt(itemKey.substring(itemKey.indexOf('#') + 1)), 2_000_000);
    }

    /** Mark your own draft PR ready for review (through the runner). Returns the PR as it is now. */
    public Item readyForReview(String itemKey) {
        poster.ready(itemKey, user.login());
        return boards.refreshItem(user.login(), user.githubToken(), itemKey);
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
        if (!settings.mvnpmEnabled()) throw new IllegalStateException("mvnpm is turned off in the GitHub settings.");
        mvnpmRuns.run(repo, pr);
        return mvnpmRuns.state();
    }

    // ---- the elves' settings ----

    /** An installed elf's settings, or the runner's own ("elf-runner"). */
    public com.fasterxml.jackson.databind.JsonNode elfSettings(String elf) {
        return elfSettings.get(elf);
    }

    /** Change some of an elf's settings; a wrong value changes nothing and comes back as {wrong: {key: why}}. */
    public com.fasterxml.jackson.databind.JsonNode saveElfSettings(String elf, java.util.Map<String, String> values) {
        return elfSettings.set(elf, values);
    }

    // ---- the Elves page ----

    /** The installed elves, with their schedules and their commands' last runs. */
    public com.fasterxml.jackson.databind.JsonNode elves() {
        return elves.installed();
    }

    /** The elves you can install: House-elves' and your own, each with what its elf.yaml says it does. */
    public com.fasterxml.jackson.databind.JsonNode elfCatalog() {
        return elves.catalog(user.login(), user.githubToken());
    }

    /** Installs an elf from the catalog at the commit you looked at. */
    public com.fasterxml.jackson.databind.JsonNode installElf(String repo, String commit) {
        return elves.install(user.login(), user.githubToken(), repo, commit);
    }

    /** What updating an elf to a commit would change, before you update it. */
    public com.fasterxml.jackson.databind.JsonNode elfChanges(String elf, String commit) {
        return elves.changes(elf, commit);
    }

    public com.fasterxml.jackson.databind.JsonNode updateElf(String elf, String commit) {
        return elves.update(elf, commit);
    }

    /** Removes an elf's program; its settings stay. */
    public com.fasterxml.jackson.databind.JsonNode removeElf(String elf) {
        return elves.remove(elf);
    }

    /** Runs one of an elf's own commands (its dry run, or what its tab's button runs), with the values it takes. */
    public com.fasterxml.jackson.databind.JsonNode runElf(String elf, String command,
            com.fasterxml.jackson.databind.JsonNode params) {
        return elves.run(elf, command, params);
    }

    /** When an elf refreshes on its own: a calendar time, null or empty for only when you start it, "default" for its own. */
    public com.fasterxml.jackson.databind.JsonNode scheduleElf(String elf, String calendar) {
        return elves.schedule(elf, calendar);
    }

    /** The elves' runs going now. */
    public List<String> elvesRunning() {
        return elves.running();
    }

    // ---- what an elf's own tab asks for (the elf-frame passes on its frame's requests) ----

    /** The elves' runs: {running: {elf:command: since}, failed: {elf:command: log}}. */
    public com.fasterxml.jackson.databind.JsonNode elvesState() {
        return elves.state();
    }

    /** The files in one of the folders an elf's tab may read. */
    public com.fasterxml.jackson.databind.JsonNode elfData(String elf, String folder) {
        return elves.data(elf, folder);
    }

    /** Some of those files, whole, or (fields) only those top-level fields of each JSON file. */
    public com.fasterxml.jackson.databind.JsonNode elfRead(String elf, String folder, List<String> files, List<String> fields) {
        return elves.read(elf, folder, files, fields);
    }

    /**
     * One of the actions an elf declares, with values from its tab. One that asks is only sent after you confirmed
     * it in the desk's own bar (elf-frame); the runner checks every value against the elf.yaml.
     */
    public com.fasterxml.jackson.databind.JsonNode elfAction(String elf, String action,
            com.fasterxml.jackson.databind.JsonNode params) {
        return elves.action(elf, action, params);
    }

    /** Stops one of an elf's agents (its tab's Stop). */
    public com.fasterxml.jackson.databind.JsonNode cancelElfAgent(String elf, String id) {
        return elves.cancelAgent(elf, id);
    }

    // ---- what's new ----

    /** What happened on a PR since your last review or comment: commits, reviews, comments, review requests. */
    public GitHub.Activity prActivity(String itemKey) {
        String repo = itemKey.substring(0, itemKey.indexOf('#'));
        int number = Integer.parseInt(itemKey.substring(itemKey.indexOf('#') + 1));
        return github.activity(user.githubToken(), user.login(), repo, number);
    }

    /** What reviewers said on a PR, in full: review summaries, comments and inline threads with their code. */
    public GitHub.Reviews prReviews(String itemKey) {
        String repo = itemKey.substring(0, itemKey.indexOf('#'));
        int number = Integer.parseInt(itemKey.substring(itemKey.indexOf('#') + 1));
        return github.reviews(user.githubToken(), user.login(), repo, number);
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
        GitHub.CiReport report = ci.report(itemKey, user.githubToken());
        if (!CiService.failing(report)) boards.refreshItem(user.login(), user.githubToken(), itemKey);
        return report;
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
        return why(itemKey, false);
    }

    /** Ask why CI is failing again, even if it already answered for this commit. */
    public ThreadView ciWhyAgain(String itemKey) {
        return why(itemKey, true);
    }

    /** When CI passes by now, the board's copy of the PR is read again so the old failure goes. */
    private ThreadView why(String itemKey, boolean again) {
        try {
            return ci.why(itemKey, user.login(), user.githubToken(), again);
        } catch (CiService.NotFailing e) {
            boards.refreshItem(user.login(), user.githubToken(), itemKey);
            throw e;
        }
    }

    /** Start the agent on fixing CI; its commits come back as a push draft for you to confirm. */
    public ThreadView ciFix(String itemKey) {
        return ci.fix(itemKey, user.login(), user.githubToken());
    }

    /** Start the agent on the open review threads of your PR; its commits come back as a push draft. */
    public ThreadView addressReview(String itemKey) {
        return ci.address(itemKey, user.login(), user.githubToken());
    }

    // ---- merge conflicts ----

    public ConflictService.Options conflictOptions(String itemKey) {
        return conflicts.options(itemKey, user.githubToken());
    }

    /** Comment "@dependabot rebase" on a Dependabot PR (as you, through the runner). Returns the comment's URL. */
    public String askDependabotToRebase(String itemKey) {
        String url = conflicts.askDependabotToRebase(itemKey, user.login(), user.githubToken());
        boards.markRebasing(user.login(), itemKey);
        return url;
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

    /** @param alwaysInvolved repos you maintain: every open PR and issue in them is on the board */
    public record SettingsView(String login, List<ScopeRow> scope, boolean mvnpmInstalled, List<String> mvnpmRepos,
            int refreshMinutes, boolean mvnpmEnabled, List<String> alwaysInvolved) {
    }

    public SettingsView settings() {
        List<ScopeRow> rows = scopes.rows().stream()
                .map(w -> new ScopeRow(w.id, w.kind.name(), w.name, w.mode.name()))
                .toList();
        return new SettingsView(user.login(), rows, mvnpm.installed(), mvnpm.repos(), settings.refreshMinutes(),
                settings.mvnpmEnabled(), settings.alwaysInvolved());
    }

    /** A repo you maintain: its open PRs come to "Needs my review" and its issues to the board, involved or not. */
    public SettingsView addAlwaysInvolved(String repo) {
        String name = ScopeService.repoName(repo);
        // GitHub refuses a whole search that names a repo it can't find, so only real ones go in.
        if (!github.isPublic(user.githubToken(), name)) {
            throw new IllegalArgumentException("There's no public repo " + name + " on GitHub. Check the name.");
        }
        // Outside what the board watches it would be hidden, so it's watched too.
        if (!scopes.scope().covers(name)) scopes.watch(name);
        List<String> repos = new java.util.ArrayList<>(settings.alwaysInvolved());
        if (!repos.contains(name)) repos.add(name);
        settings.setAlwaysInvolved(repos);
        return settings();
    }

    public SettingsView removeAlwaysInvolved(String repo) {
        String name = ScopeService.repoName(repo);
        settings.setAlwaysInvolved(settings.alwaysInvolved().stream().filter(r -> !r.equals(name)).toList());
        return settings();
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

    /** The mvnpm lane and checks on or off for this desk (for desks without mvnpm-dependabot work). */
    public SettingsView setMvnpmEnabled(boolean on) {
        settings.setMvnpmEnabled(on);
        return settings();
    }

    public SettingsView setRefreshMinutes(int minutes) {
        settings.setRefreshMinutes(minutes);
        return settings();
    }
}
