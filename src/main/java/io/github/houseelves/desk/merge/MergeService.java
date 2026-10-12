package io.github.houseelves.desk.merge;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.jboss.logging.Logger;

import io.github.houseelves.desk.github.GitHub;
import io.github.houseelves.desk.github.Item;
import io.github.houseelves.desk.runner.GitHubWriter;
import io.github.houseelves.desk.runner.Live;
import io.github.houseelves.desk.thread.DeskThread;
import io.github.houseelves.desk.thread.GitHubAction;
import io.github.houseelves.desk.thread.Turn;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * Merge. Offered only for approved PRs with green CI, and merged straight away
 * through the runner, pinned to the head commit you saw. If GitHub is still
 * working out mergeability, the request waits (checked every minute) and the
 * same rules apply: new commits, red CI, conflicts or a lost approval stop it.
 *
 * <p>Reading needs a GitHub token and the desk has no session in a scheduled
 * job, so it uses the read token from your latest request
 * ({@link #rememberToken}); held in memory only. After a restart, waiting
 * watches resume the next time you open the desk.
 */
@ApplicationScoped
public class MergeService {

    private static final Logger LOG = Logger.getLogger(MergeService.class);

    @Inject
    GitHub github;

    @Inject
    GitHubWriter writer;

    @Inject
    Live live;

    @Inject
    ObjectMapper mapper;

    private final Map<String, String> readTokens = new ConcurrentHashMap<>();

    /** Your options for one PR: what the repo allows, your usual method, and where CI stands. */
    public record Options(String itemKey, List<String> methods, String defaultMethod, String ci, String mergeable,
            String headSha, boolean open, boolean approved) {
    }

    /**
     * A watch as the page shows it.
     *
     * @param state waiting, merged, failed or cancelled
     */
    public record View(String itemKey, String method, String state, String outcome, Instant created, Instant finished) {
    }

    public void rememberToken(String login, String token) {
        readTokens.put(login, token);
    }

    public Options options(String itemKey, String token) {
        GitHub.PullState pr = github.pullState(token, repo(itemKey), number(itemKey));
        return new Options(itemKey, pr.methods(), pr.defaultMethod(), pr.ci(), pr.mergeable(), pr.headSha(),
                pr.state().equals("OPEN"), pr.approved());
    }

    /**
     * Starts watching. {@code headSha} is the head shown to you when you confirmed; if the PR has moved on since,
     * this refuses rather than merge commits you haven't seen. Checks once straight away, so an already green PR
     * merges now.
     */
    public View request(String itemKey, String method, String headSha, String login, String token) {
        return request(itemKey, method, headSha, login, token, false);
    }

    /**
     * @param withoutApproval "Merge without approval": you know it's fine, so merge without an approval. CI must still be
     *                        green, and branch protection on GitHub may still refuse it.
     */
    public View request(String itemKey, String method, String headSha, String login, String token,
            boolean withoutApproval) {
        GitHub.PullState pr = github.pullState(token, repo(itemKey), number(itemKey));
        if (!pr.state().equals("OPEN")) throw new IllegalStateException(itemKey + " isn't open");
        if (pr.draft()) throw new IllegalStateException(itemKey + " is a draft PR");
        if (!pr.approved() && !withoutApproval) {
            throw new IllegalStateException("Merging is only for approved PRs, and " + itemKey + " isn't approved.");
        }
        boolean green = "SUCCESS".equals(pr.ci()) || (pr.ci() == null && Item.requiredChecksPassed(pr.mergeState()));
        if (!green) {
            throw new IllegalStateException("CI isn't green on " + itemKey
                    + ("FAILURE".equals(pr.ci()) || "ERROR".equals(pr.ci()) ? " (it's failing; re-run or fix it first)." : " yet."));
        }
        // GitHub's mergeStateStatus: CLEAN means every required check passed and nothing else blocks it. UNKNOWN
        // (still computing) is allowed through and settled by the next check round.
        if (!Item.requiredChecksPassed(pr.mergeState()) && !"UNKNOWN".equals(pr.mergeState())) {
            throw new IllegalStateException(switch (pr.mergeState()) {
                case "BEHIND" -> itemKey + " is behind its base branch, and the repo requires it to be up to date.";
                case "DIRTY" -> itemKey + " has merge conflicts.";
                default -> "GitHub doesn't consider " + itemKey + " mergeable yet: required checks haven't all passed"
                        + (withoutApproval && !pr.approved() ? ", or the repo requires an approval." : ".");
            });
        }
        if (!pr.methods().contains(method)) {
            throw new IllegalArgumentException(repo(itemKey) + " doesn't allow " + method + "; it allows " + pr.methods());
        }
        if (!pr.headSha().equals(headSha)) {
            throw new IllegalStateException("The PR got new commits while you were looking. Open it again to see them.");
        }
        rememberToken(login, token);
        QuarkusTransaction.requiringNew().run(() -> {
            if (MergeWatch.waitingFor(itemKey) != null) {
                throw new IllegalStateException("A merge is already in progress for " + itemKey);
            }
            MergeWatch w = new MergeWatch();
            w.itemKey = itemKey;
            w.method = method;
            w.headSha = headSha;
            w.login = login;
            w.requireApproval = !withoutApproval;
            w.persist();
            note(itemKey, withoutApproval && !pr.approved()
                    ? "You asked to merge this without an approval (" + method + ")."
                    : "You asked to merge this (" + method + ").");
        });
        check(itemKey, pr);
        announce(itemKey);
        return view(itemKey);
    }

    public View cancel(String itemKey) {
        QuarkusTransaction.requiringNew().run(() -> {
            MergeWatch w = MergeWatch.waitingFor(itemKey);
            if (w == null) return;
            end(w, MergeWatch.CANCELLED, "You cancelled the merge.");
        });
        announce(itemKey);
        return view(itemKey);
    }

    public View view(String itemKey) {
        return QuarkusTransaction.requiringNew().call(() -> {
            MergeWatch w = MergeWatch.latestFor(itemKey);
            return w == null ? null : new View(w.itemKey, w.method, w.state, w.outcome, w.created, w.finished);
        });
    }

    /** Waiting watches, plus ones that ended in the last day, for the board's chips. */
    public Map<String, View> recent() {
        return QuarkusTransaction.requiringNew().call(() -> {
            Map<String, View> out = new HashMap<>();
            Instant since = Instant.now().minus(MergeDecision.GIVE_UP_AFTER);
            for (MergeWatch w : MergeWatch.<MergeWatch>list("state = ?1 or finished > ?2 order by id",
                    MergeWatch.WAITING, since)) {
                out.put(w.itemKey, new View(w.itemKey, w.method, w.state, w.outcome, w.created, w.finished));
            }
            return out;
        });
    }

    @Scheduled(every = "60s", delayed = "30s", concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    void poll() {
        List<MergeWatch> waiting = QuarkusTransaction.requiringNew().call(MergeWatch::waiting);
        for (MergeWatch w : waiting) {
            String token = readTokens.get(w.login);
            if (token == null) continue; // resumes once you open the desk again
            try {
                check(w.itemKey, github.pullState(token, repo(w.itemKey), number(w.itemKey)));
            } catch (Exception e) {
                LOG.warnf("Merge on green: checking %s failed: %s", w.itemKey, e.getMessage());
            }
        }
    }

    /** One round for one PR: decide, and act. */
    void check(String itemKey, GitHub.PullState pr) {
        MergeWatch w = QuarkusTransaction.requiringNew().call(() -> {
            MergeWatch x = MergeWatch.waitingFor(itemKey);
            if (x != null) x.checked = Instant.now();
            return x;
        });
        if (w == null) return;
        MergeDecision d = MergeDecision.decide(w.headSha, w.created, Instant.now(), pr, w.requireApproval);
        switch (d.step()) {
            case WAIT -> { }
            case DONE -> finish(w.id, pr.state().equals("MERGED") ? MergeWatch.MERGED : MergeWatch.CANCELLED, d.reason());
            case STOP -> finish(w.id, MergeWatch.FAILED, d.reason());
            case MERGE -> merge(w);
        }
    }

    private void merge(MergeWatch w) {
        try {
            var response = writer.merge(repo(w.itemKey), number(w.itemKey), w.method, w.headSha);
            QuarkusTransaction.requiringNew().run(() -> {
                GitHubAction a = new GitHubAction();
                DeskThread t = DeskThread.byKey(w.itemKey);
                a.threadId = t == null ? null : t.id;
                a.kind = "merge";
                a.request = "{\"item\":\"" + w.itemKey + "\",\"merge_method\":\"" + w.method + "\",\"sha\":\""
                        + w.headSha + "\"}";
                a.resultUrl = "https://github.com/" + repo(w.itemKey) + "/pull/" + number(w.itemKey);
                a.login = w.login;
                a.persist();
            });
            String sha = response.path("sha").asText("");
            finish(w.id, MergeWatch.MERGED, "Merged" + (sha.isEmpty() ? "." : " (" + sha.substring(0, Math.min(7, sha.length())) + ")."));
        } catch (GitHubWriter.WriteFailed e) {
            if (e.status == 0) {
                // The runner is offline or didn't answer: nothing was merged (or GitHub will tell us next round).
                LOG.warnf("Merge on green: couldn't merge %s yet: %s", w.itemKey, e.getMessage());
                return;
            }
            String why = gitHubMessage(e);
            finish(w.id, MergeWatch.FAILED, "GitHub refused the merge: " + why + (why.endsWith(".") ? "" : "."));
        }
    }

    private void finish(long watchId, String state, String outcome) {
        String key = QuarkusTransaction.requiringNew().call(() -> {
            MergeWatch w = MergeWatch.findById(watchId);
            if (w == null || !w.state.equals(MergeWatch.WAITING)) return null;
            end(w, state, outcome);
            return w.itemKey;
        });
        if (key != null) announce(key);
    }

    private static void end(MergeWatch w, String state, String outcome) {
        w.state = state;
        w.outcome = outcome;
        w.finished = Instant.now();
        note(w.itemKey, outcome);
    }

    /** A line in the PR's thread, if it has one. */
    private static void note(String itemKey, String text) {
        DeskThread t = DeskThread.byKey(itemKey);
        if (t != null) Turn.add(t.id, Turn.SYSTEM, null, text);
    }

    private void announce(String itemKey) {
        live.changed(itemKey);
        live.boardUpdated();
    }

    private String gitHubMessage(GitHubWriter.WriteFailed e) {
        try {
            return mapper.readTree(e.body).path("message").asText(e.getMessage());
        } catch (Exception ignored) {
            return e.getMessage();
        }
    }

    static String repo(String itemKey) {
        return itemKey.substring(0, itemKey.indexOf('#'));
    }

    static int number(String itemKey) {
        return Integer.parseInt(itemKey.substring(itemKey.indexOf('#') + 1));
    }
}
