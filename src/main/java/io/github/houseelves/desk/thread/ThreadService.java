package io.github.houseelves.desk.thread;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

import org.jboss.logging.Logger;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import io.github.houseelves.desk.agent.AgentOutput;
import io.github.houseelves.desk.agent.Prompts;
import io.github.houseelves.desk.github.GitHub;
import io.github.houseelves.desk.github.Item;
import io.github.houseelves.desk.github.ItemContext;
import io.github.houseelves.desk.runner.GitHubWriter;
import io.github.houseelves.desk.runner.Live;
import io.github.houseelves.desk.runner.RunnerHub;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * Threads, their turns, jobs and drafts. Starting an action records your turn
 * and a queued job, then hands the job to the runner; the runner's messages
 * come back through {@link RunnerHub} into the job* methods here.
 *
 * <p>Nothing here writes to GitHub. Posting a draft is {@link Poster}'s job.
 */
@ApplicationScoped
public class ThreadService {

    private static final Logger LOG = Logger.getLogger(ThreadService.class);

    @Inject
    GitHub github;

    @Inject
    RunnerHub hub;

    @Inject
    Live live;

    @Inject
    GitHubWriter writes;

    @Inject
    ObjectMapper mapper;

    /** jobId -> itemKey, so streamed lines don't need a database read. */
    private final Map<Long, String> jobKeys = new ConcurrentHashMap<>();

    // ---- reads ----

    public ThreadView view(String itemKey) {
        return QuarkusTransaction.requiringNew().call(() -> {
            DeskThread t = DeskThread.byKey(itemKey);
            if (t == null) return null;
            List<ThreadView.TurnView> turns = Turn.of(t.id).stream()
                    .map(x -> new ThreadView.TurnView(x.id, x.role, x.action, x.text, x.created)).toList();
            Job open = Job.openFor(t.id);
            ThreadView.JobView job = open == null ? null
                    : new ThreadView.JobView(open.id, open.action, open.state, open.created, open.started,
                            live.lines(open.id));
            Draft d = Draft.pendingFor(t.id);
            ThreadView.DraftView draft = d == null ? null
                    : d.kind.equals(Draft.PUSH)
                            ? new ThreadView.DraftView(d.id, d.kind, null, mapper.readValue(d.payload, PushOut.class), null, d.created)
                    : d.kind.equals(Draft.PR)
                            ? new ThreadView.DraftView(d.id, d.kind, null, null, mapper.readValue(d.payload, PrOut.class), d.created)
                            : new ThreadView.DraftView(d.id, d.kind, readDraft(d.payload), null, null, d.created);
            List<ThreadView.PostedView> posted = Draft.<Draft>list("threadId = ?1 and state = ?2 order by postedAt",
                    t.id, Draft.POSTED).stream()
                    .map(x -> new ThreadView.PostedView(x.kind, x.postedUrl, x.postedAt)).toList();
            return new ThreadView(t.itemKey, t.itemKind, t.title, t.url, hub.connected(), turns, job, draft, posted,
                    isOwn(t.id));
        });
    }

    /**
     * For every thread: working (a job is open), draft (something waits to be posted), answered (the agent answered
     * since the thread page last showed it) or idle.
     */
    public Map<String, ThreadView.Summary> summaries() {
        return QuarkusTransaction.requiringNew().call(() -> {
            Map<String, ThreadView.Summary> out = new HashMap<>();
            for (DeskThread t : DeskThread.<DeskThread>listAll()) {
                Job job = Job.latestFor(t.id);
                Draft draft = Draft.pendingFor(t.id);
                Draft posted = Draft.find("threadId = ?1 and state = ?2 order by postedAt desc", t.id, Draft.POSTED).firstResult();
                Turn answer = Turn.find("threadId = ?1 and role = ?2 order by id desc", t.id, Turn.AGENT).firstResult();
                boolean working = job != null && job.open();
                String state = working ? "working" : draft != null ? "draft" : unseenAnswer(t) ? "answered" : "idle";
                Instant since = switch (state) {
                    case "working" -> job.started != null ? job.started : job.created;
                    case "draft" -> draft.created;
                    case "answered" -> answer.created;
                    default -> job == null ? t.updated : job.finished != null ? job.finished : job.created;
                };
                boolean failed = !working && job != null && (job.state.equals(Job.FAILED) || job.state.equals(Job.CANCELLED));
                out.put(t.itemKey, new ThreadView.Summary(t.itemKey, state, job == null ? null : job.action,
                        draft == null ? null : draft.kind, since, failed && draft == null,
                        posted == null ? null : posted.kind, posted == null ? null : posted.postedAt));
            }
            return out;
        });
    }

    private static boolean unseenAnswer(DeskThread t) {
        return t.seen == null
                ? Turn.count("threadId = ?1 and role = ?2", t.id, Turn.AGENT) > 0
                : Turn.count("threadId = ?1 and role = ?2 and created > ?3", t.id, Turn.AGENT, t.seen) > 0;
    }

    /** The thread page showed the thread: its answers so far are no longer new. */
    public void seen(String itemKey) {
        QuarkusTransaction.requiringNew().run(() -> {
            DeskThread t = DeskThread.byKey(itemKey);
            if (t != null) t.seen = Instant.now();
        });
    }

    // ---- your actions ----

    /** Review (PR), investigate (issue) or ask (either). Fetches the item's current text from GitHub with your token. */
    public ThreadView start(String itemKey, String action, String question, String login, String token) {
        return start(itemKey, action, question, login, token, null, null);
    }

    /**
     * As above, plus the CI and conflict actions (PRs): {@code extra} is added to the prompt (failed checks and log
     * tails), and {@code checkoutExtras} is merged into what the runner gets: {@code push} says where the PR's branch
     * lives, {@code head} which commit the question was about.
     */
    public ThreadView start(String itemKey, String action, String question, String login, String token, String extra,
            ObjectNode checkoutExtras) {
        if (!List.of(Prompts.REVIEW, Prompts.INVESTIGATE, Prompts.FIX, Prompts.ASK, Prompts.CI_WHY, Prompts.CI_FIX,
                Prompts.CONFLICTS).contains(action)) {
            throw new IllegalArgumentException("Unknown action " + action);
        }
        if (action.equals(Prompts.ASK) && (question == null || question.isBlank())) {
            throw new IllegalArgumentException("Type a question first");
        }
        String repo = itemKey.substring(0, itemKey.indexOf('#'));
        int number = Integer.parseInt(itemKey.substring(itemKey.indexOf('#') + 1));
        ItemContext ctx = github.context(token, repo, number);
        if ((action.equals(Prompts.REVIEW) || action.startsWith("ci-") || action.equals(Prompts.CONFLICTS))
                && ctx.kind() != Item.Kind.PR) {
            throw new IllegalArgumentException(itemKey + " is an issue, not a PR");
        }
        if ((action.equals(Prompts.INVESTIGATE) || action.equals(Prompts.FIX)) && ctx.kind() != Item.Kind.ISSUE) {
            throw new IllegalArgumentException(itemKey + " is a PR; use Review");
        }
        String material = ctx.kind() == Item.Kind.ISSUE ? join(extra, openPrsFor(token, itemKey)) : extra;

        long jobId = QuarkusTransaction.requiringNew().call(() -> {
            DeskThread t = upsert(ctx);
            requireIdle(t);
            Turn.add(t.id, Turn.YOU, action, Prompts.label(action, question));
            boolean fresh = t.claudeSession == null;
            String prompt = fresh
                    ? Prompts.start(action, ctx, login, question, material)
                    : Prompts.again(action, ctx, question, material, login);
            // Every action sends the item's current text, which may have new comments since the last check.
            String untrusted = ctx.untrustedText();
            ObjectNode checkout = (ObjectNode) mapper.readTree(checkout(ctx));
            checkout.put("reset", true); // a new action starts from the PR's current head
            if (Prompts.isOwn(ctx, login)) checkout.put("own", true); // your PR: reviews can only be COMMENT
            if (checkoutExtras != null) checkout.setAll(checkoutExtras);
            return newJob(t, action, prompt, checkout.toString(), untrusted.isBlank() ? null : untrusted).id;
        });
        dispatch(jobId);
        live.changed(itemKey);
        return view(itemKey);
    }

    /**
     * The thread as it is, if starting {@code action} again would only repeat work: an agent is already working on
     * this item, or the last finished run was the same action about the same {@code head} commit. Null when a new
     * run makes sense.
     */
    public ThreadView existing(String itemKey, String action, String head) {
        boolean repeat = QuarkusTransaction.requiringNew().call(() -> {
            DeskThread t = DeskThread.byKey(itemKey);
            if (t == null) return false;
            if (Job.openFor(t.id) != null) return true;
            Job last = Job.find("threadId = ?1 and state = ?2 order by id desc", t.id, Job.DONE).firstResult();
            if (last == null || !last.action.equals(action) || head == null) return false;
            return head.equals(mapper.readTree(last.checkout).path("head").asText(null));
        });
        return repeat ? view(itemKey) : null;
    }

    /** Your follow-up in the thread. Continues the agent's session. */
    public ThreadView reply(String itemKey, String text, String login, String token) {
        if (text == null || text.isBlank()) throw new IllegalArgumentException("Type a reply first");
        DeskThread existing = QuarkusTransaction.requiringNew().call(() -> DeskThread.byKey(itemKey));
        if (existing == null || existing.claudeSession == null) {
            // Nothing to continue (no thread, or its first run failed): start over with the question.
            return start(itemKey, Prompts.ASK, text, login, token);
        }
        long jobId = QuarkusTransaction.requiringNew().call(() -> {
            DeskThread t = DeskThread.byKey(itemKey);
            requireIdle(t);
            // What you did in the desk since the agent last answered (posted, pushed, squashed, merged...): the
            // session only knows what it was told, so without this it offers a push that already went out.
            List<Turn> turns = Turn.of(t.id);
            List<String> since = new ArrayList<>();
            for (int i = turns.size() - 1; i >= 0 && !turns.get(i).role.equals(Turn.AGENT); i--) {
                if (turns.get(i).role.equals(Turn.SYSTEM)) since.addFirst(turns.get(i).text);
            }
            Turn.add(t.id, Turn.YOU, Prompts.REPLY, text);
            Job last = Job.find("threadId = ?1 order by id desc", t.id).firstResult();
            // A follow-up keeps the worktree as the agent left it (its commits included).
            ObjectNode checkout = (ObjectNode) mapper.readTree(last.checkout);
            checkout.remove("reset");
            return newJob(t, Prompts.REPLY, Prompts.reply(text, since), checkout.toString(), null).id;
        });
        dispatch(jobId);
        live.changed(itemKey);
        return view(itemKey);
    }

    public ThreadView cancel(String itemKey) {
        QuarkusTransaction.requiringNew().run(() -> {
            DeskThread t = DeskThread.byKey(itemKey);
            Job j = t == null ? null : Job.openFor(t.id);
            if (j == null) return;
            hub.send(hub.message("job.cancel").put("job", j.id));
            j.state = Job.CANCELLED;
            j.finished = Instant.now();
            Turn.add(t.id, Turn.SYSTEM, null, "You stopped the agent.");
            live.finished(j.id);
        });
        live.changed(itemKey);
        return view(itemKey);
    }

    /** Your edits to the pending draft. */
    public ThreadView updateDraft(long draftId, AgentOutput.DraftOut edited) {
        String key = QuarkusTransaction.requiringNew().call(() -> {
            Draft d = pending(draftId);
            if (d.kind.equals(Draft.PUSH)) throw new IllegalArgumentException("Commits can't be edited here");
            if (d.kind.equals(Draft.PR)) {
                if (edited.title() == null || edited.title().isBlank()) throw new IllegalArgumentException("The PR needs a title");
                PrOut pr = mapper.readValue(d.payload, PrOut.class);
                d.payload = mapper.writeValueAsString(pr.withText(edited.title().trim(), edited.body() == null ? "" : edited.body()));
                return threadKey(d.threadId);
            }
            if (d.kind.equals(Draft.COMMENT) && !edited.comments().isEmpty()) {
                throw new IllegalArgumentException("A comment can't have inline comments");
            }
            if (d.kind.equals(Draft.ISSUE)) {
                String repo = edited.repo() == null ? "" : edited.repo().trim();
                if (!REPO.matcher(repo).matches()) throw new IllegalArgumentException("The repo is owner/name, like eclipse-vertx/vert.x");
                if (edited.title() == null || edited.title().isBlank()) throw new IllegalArgumentException("The issue needs a title");
                d.payload = write(new AgentOutput.DraftOut(d.kind, null, edited.body(), List.of(), repo,
                        edited.title().trim(), blankToNull(edited.then())));
                return threadKey(d.threadId);
            }
            boolean own = isOwn(d.threadId);
            d.payload = write(new AgentOutput.DraftOut(d.kind, own && edited.event() != null ? "COMMENT" : edited.event(),
                    edited.body(), edited.comments()));
            return threadKey(d.threadId);
        });
        live.changed(key);
        return view(key);
    }

    public ThreadView discardDraft(long draftId) {
        String key = QuarkusTransaction.requiringNew().call(() -> {
            Draft d = pending(draftId);
            d.state = Draft.DISCARDED;
            Turn.add(d.threadId, Turn.SYSTEM, null, "You discarded the draft.");
            return threadKey(d.threadId);
        });
        live.changed(key);
        return view(key);
    }

    // ---- the runner ----

    public void runnerConnected(Set<Long> runnerHas) {
        List<Long> resend = QuarkusTransaction.requiringNew().call(() -> {
            for (Job j : Job.inState(Job.RUNNING)) {
                if (runnerHas.contains(j.id)) continue;
                fail(j, "The runner restarted while this was running. Ask again to retry.");
            }
            return Job.inState(Job.QUEUED).stream().map(j -> j.id).filter(id -> !runnerHas.contains(id)).toList();
        });
        resend.forEach(this::dispatch);
    }

    public void jobAccepted(long jobId) {
        String key = QuarkusTransaction.requiringNew().call(() -> {
            Job j = Job.findById(jobId);
            if (j == null || !j.state.equals(Job.QUEUED)) return null;
            j.state = Job.RUNNING;
            j.started = Instant.now();
            return threadKey(j.threadId);
        });
        if (key != null) live.changed(key);
    }

    public void jobLine(long jobId, String text) {
        String key = jobKeys.computeIfAbsent(jobId,
                id -> QuarkusTransaction.requiringNew().call(() -> {
                    Job j = Job.findById(id);
                    return j == null ? null : threadKey(j.threadId);
                }));
        if (key != null) live.line(key, jobId, text);
    }

    public void jobDone(long jobId, String session, JsonNode output, BigDecimal cost) {
        jobDone(jobId, session, output, cost, null);
    }

    /** @param git what the runner saw in the worktree: {from, to, commits: [{sha, subject}], diffstat} */
    public void jobDone(long jobId, String session, JsonNode output, BigDecimal cost, JsonNode git) {
        AgentOutput out;
        try {
            out = mapper.treeToValue(output, AgentOutput.class);
        } catch (Exception e) {
            out = null;
        }
        if (out == null || out.reply() == null) {
            jobFailed(jobId, "The agent finished without a usable answer.");
            return;
        }
        AgentOutput result = out;
        String key = QuarkusTransaction.requiringNew().call(() -> {
            Job j = Job.findById(jobId);
            if (j == null || !j.open()) return null; // cancelled meanwhile
            j.state = Job.DONE;
            j.finished = Instant.now();
            j.costUsd = cost;
            DeskThread t = DeskThread.findById(j.threadId);
            if (session != null) t.claudeSession = session;
            t.updated = Instant.now();
            Turn.add(t.id, Turn.AGENT, j.action, result.reply());
            // A "pr" draft is the text for the agent's commit: it becomes a PR draft with the commit, below.
            if (result.draft() != null && result.draft().body() != null && !Draft.PR.equals(result.draft().kind())) {
                Draft.update("state = ?1 where threadId = ?2 and state = ?3", Draft.DISCARDED, t.id, Draft.PENDING);
                Draft d = new Draft();
                d.threadId = t.id;
                d.jobId = j.id;
                AgentOutput.DraftOut drafted = result.draft();
                if (Draft.ISSUE.equals(drafted.kind()) && drafted.repo() != null && REPO.matcher(drafted.repo().trim()).matches()
                        && drafted.title() != null && !drafted.title().isBlank()) {
                    d.kind = Draft.ISSUE;
                    d.payload = write(new AgentOutput.DraftOut(d.kind, null, drafted.body(), List.of(), drafted.repo().trim(),
                            drafted.title().trim(), blankToNull(drafted.then())));
                } else {
                    boolean review = Draft.REVIEW.equals(drafted.kind()) && t.itemKind.equals("PR");
                    d.kind = review ? Draft.REVIEW : Draft.COMMENT;
                    // GitHub refuses approvals and change requests from a PR's author.
                    boolean own = mapper.readTree(j.checkout).path("own").asBoolean();
                    d.payload = write(new AgentOutput.DraftOut(d.kind, review ? (own ? "COMMENT" : drafted.event()) : null,
                            drafted.body(), review ? drafted.comments() : List.of()));
                }
                d.persist();
            }
            if (t.itemKind.equals("PR")) pushDraft(t, j, git);
            else prDraft(t, j, git, result.draft());
            return t.itemKey;
        });
        finish(jobId, key);
    }

    /**
     * The agent committed in a PR's worktree: offer the commits as a push. Where the branch lives (and whether you
     * can push to it) is known up front for "Fix this", otherwise it's worked out when you click Push (see
     * {@link Poster#preview}), so commits from any action or reply can be pushed.
     */
    private void pushDraft(DeskThread t, Job j, JsonNode git) throws Exception {
        if (git != null && git.hasNonNull("error")) {
            Turn.add(t.id, Turn.SYSTEM, null, git.path("error").asText() + ". Nothing is offered to push.");
            return;
        }
        if (git == null || !git.path("commits").isArray() || git.path("commits").isEmpty()) return;
        JsonNode push = mapper.readTree(j.checkout).path("push");
        persistPush(t, j.id, pushOut(git, push.path("headRepo").asText(null), push.path("headRef").asText(null)));
    }

    /**
     * The agent committed in an issue's worktree: offer its commit (the runner makes it one, on the default branch)
     * as a new PR, with the title and body it drafted. Your fork and the branch are worked out when you preview.
     */
    private void prDraft(DeskThread t, Job j, JsonNode git, AgentOutput.DraftOut drafted) throws Exception {
        if (git != null && git.hasNonNull("error")) {
            Turn.add(t.id, Turn.SYSTEM, null, git.path("error").asText() + ". Nothing is offered as a PR.");
            return;
        }
        if (git == null || !git.path("commits").isArray() || git.path("commits").isEmpty()) return;
        if (git.path("commits").size() != 1 || git.path("base").asText("").isBlank()) {
            Turn.add(t.id, Turn.SYSTEM, null, "The agent's commits couldn't be made into one commit on the default branch,"
                    + " so nothing is offered as a PR.");
            return;
        }
        PushOut.Commit commit = new PushOut.Commit(git.path("commits").get(0).path("sha").asText(),
                git.path("commits").get(0).path("subject").asText());
        boolean texts = drafted != null && Draft.PR.equals(drafted.kind());
        String title = texts && drafted.title() != null && !drafted.title().isBlank() ? drafted.title().trim() : commit.subject();
        String fixes = "Fixes #" + t.number();
        String body = texts && drafted.body() != null && !drafted.body().isBlank() ? drafted.body().strip() : "";
        if (!body.contains(fixes)) body = body.isEmpty() ? fixes : body + "\n\n" + fixes;
        PrOut pr = new PrOut(t.repo(), git.path("base").asText(), null, null, title, body, git.path("from").asText(),
                git.path("to").asText(), List.of(commit), git.path("diffstat").asText(""), git.path("patch").asText(null),
                git.path("patchCut").asBoolean());
        Draft.update("state = ?1 where threadId = ?2 and state = ?3", Draft.DISCARDED, t.id, Draft.PENDING);
        Draft d = new Draft();
        d.threadId = t.id;
        d.jobId = j.id;
        d.kind = Draft.PR;
        d.payload = mapper.writeValueAsString(pr);
        d.persist();
    }

    private PushOut pushOut(JsonNode git, String headRepo, String headRef) {
        List<PushOut.Commit> commits = new ArrayList<>();
        git.path("commits").forEach(c -> commits.add(new PushOut.Commit(c.path("sha").asText(), c.path("subject").asText())));
        return new PushOut(headRepo, headRef, git.path("from").asText(), git.path("to").asText(), List.copyOf(commits),
                git.path("diffstat").asText(""), git.path("base").asText(null), git.path("squashed").asBoolean(),
                git.path("replaces").asInt(), git.path("patch").asText(null), git.path("patchCut").asBoolean());
    }

    private void persistPush(DeskThread t, Long jobId, PushOut out) throws Exception {
        Draft.update("state = ?1 where threadId = ?2 and state = ?3", Draft.DISCARDED, t.id, Draft.PENDING);
        Draft d = new Draft();
        d.threadId = t.id;
        d.jobId = jobId;
        d.kind = Draft.PUSH;
        d.payload = mapper.writeValueAsString(out);
        d.persist();
    }

    /**
     * "Squash into one commit": the runner squashes the PR in its worktree (no agent), and the result is offered
     * as a push you confirm, like the agent's commits.
     */
    public ThreadView squash(ItemContext ctx, String headRepo, String headRef) {
        if (ctx.kind() != Item.Kind.PR) throw new IllegalStateException("Only PRs can be squashed");
        String key = QuarkusTransaction.requiringNew().call(() -> {
            DeskThread t = upsert(ctx);
            requireIdle(t);
            if (Draft.count("threadId = ?1 and state = ?2 and kind = ?3", t.id, Draft.PENDING, Draft.PUSH) > 0) {
                throw new IllegalStateException("There are commits waiting to be pushed: push or discard them first.");
            }
            return t.itemKey;
        });
        JsonNode git;
        try {
            git = writes.squash((ObjectNode) mapper.readTree(checkout(ctx)));
        } catch (GitHubWriter.WriteFailed e) {
            throw new IllegalStateException(e.getMessage());
        } catch (Exception e) {
            throw new IllegalStateException(e.getMessage(), e);
        }
        if (git.path("already").asBoolean()) throw new IllegalStateException("It's already one commit on its base.");
        QuarkusTransaction.requiringNew().call(() -> {
            DeskThread t = DeskThread.byKey(key);
            PushOut out = pushOut(git, headRepo, headRef);
            persistPush(t, null, out);
            Turn.add(t.id, Turn.SYSTEM, null, "Squashed the PR's " + out.replaces() + " commits into one. Nothing is "
                    + "pushed until you confirm it below.");
            return null;
        });
        live.changed(key);
        return view(key);
    }

    public void jobFailed(long jobId, String error) {
        String key = QuarkusTransaction.requiringNew().call(() -> {
            Job j = Job.findById(jobId);
            if (j == null || !j.open()) return null;
            fail(j, error);
            return threadKey(j.threadId);
        });
        finish(jobId, key);
    }

    // ---- helpers ----

    private void finish(long jobId, String key) {
        jobKeys.remove(jobId);
        live.finished(jobId);
        if (key != null) live.changed(key);
    }

    private void fail(Job j, String error) {
        j.state = Job.FAILED;
        j.error = error;
        j.finished = Instant.now();
        Turn.add(j.threadId, Turn.SYSTEM, null, "The agent stopped: " + error);
    }

    private void dispatch(long jobId) {
        ObjectNode msg = QuarkusTransaction.requiringNew().call(() -> {
            Job j = Job.findById(jobId);
            if (j == null || !j.state.equals(Job.QUEUED)) return null;
            DeskThread t = DeskThread.findById(j.threadId);
            ObjectNode m = hub.message("job.start");
            m.put("job", j.id);
            m.put("thread", t.itemKey);
            m.set("checkout", mapper.readTree(j.checkout));
            m.put("session", t.claudeSession);
            m.put("prompt", j.prompt);
            m.put("schema", AgentOutput.SCHEMA);
            m.put("untrusted", j.untrusted);
            return m;
        });
        if (msg != null && !hub.send(msg)) {
            LOG.debugf("No runner connected; job %d waits for it", jobId);
        }
    }

    private Job newJob(DeskThread t, String action, String prompt, String checkout, String untrusted) {
        Job j = new Job();
        j.threadId = t.id;
        j.action = action;
        j.prompt = prompt;
        j.checkout = checkout;
        j.untrusted = untrusted;
        j.persist();
        t.updated = Instant.now();
        return j;
    }

    private static void requireIdle(DeskThread t) {
        if (Job.openFor(t.id) != null) {
            throw new IllegalStateException("The agent is still working on this. Wait for it or stop it first.");
        }
    }

    private static DeskThread upsert(ItemContext ctx) {
        DeskThread t = DeskThread.byKey(ctx.key());
        if (t == null) {
            t = new DeskThread();
            t.itemKey = ctx.key();
            t.itemKind = ctx.kind().name();
        }
        t.title = ctx.title();
        t.url = ctx.url();
        t.persist();
        return t;
    }

    private String checkout(ItemContext ctx) {
        ObjectNode c = mapper.createObjectNode().put("repo", ctx.repo()).put("number", ctx.number())
                .put("kind", ctx.kind().name());
        if (ctx.baseRef() != null) c.put("base", ctx.baseRef());
        c.put("title", ctx.title()); // the squashed commit's message when the PR has several commits
        return c.toString();
    }

    /** The thread's PR is yours (recorded on its jobs when they started). */
    private boolean isOwn(long threadId) {
        Job last = Job.find("threadId = ?1 order by id desc", threadId).firstResult();
        try {
            return last != null && mapper.readTree(last.checkout).path("own").asBoolean();
        } catch (Exception e) {
            return false;
        }
    }

    private static Draft pending(long draftId) {
        Draft d = Draft.findById(draftId);
        if (d == null || !d.state.equals(Draft.PENDING)) {
            throw new IllegalStateException("That draft was already posted or discarded");
        }
        return d;
    }

    /**
     * The open PRs GitHub links to an issue (fixing it, or mentioning it), for the agent's prompt: before proposing
     * a fix, it should know one may already be on the way. Empty when there are none or GitHub can't say.
     */
    private String openPrsFor(String token, String itemKey) {
        List<GitHub.LinkedPr> prs;
        try {
            prs = github.linkedPrs(token, List.of(itemKey)).getOrDefault(itemKey, List.of());
        } catch (Exception e) {
            LOG.debugf("Couldn't read the PRs linked to %s: %s", itemKey, e.getMessage());
            return null;
        }
        if (prs.isEmpty()) return null;
        StringBuilder sb = new StringBuilder("## Open PRs linked to this issue\n\n");
        for (GitHub.LinkedPr pr : prs) {
            sb.append("- ").append(pr.key()).append(pr.fixes() ? " (says it fixes this issue)" : " (mentions this issue)")
                    .append(pr.draft() ? ", draft" : "").append(", by @").append(pr.author()).append(": ")
                    .append(pr.title()).append(" ").append(pr.url()).append('\n');
        }
        return sb.toString();
    }

    private static String join(String a, String b) {
        if (a == null || a.isBlank()) return b;
        return b == null ? a : a + "\n\n" + b;
    }

    static final Pattern REPO = Pattern.compile("[A-Za-z0-9-]+/[A-Za-z0-9._-]+");

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }

    private static String threadKey(long threadId) {
        DeskThread t = DeskThread.findById(threadId);
        return t == null ? null : t.itemKey;
    }

    AgentOutput.DraftOut readDraft(String json) {
        try {
            return mapper.readValue(json, AgentOutput.DraftOut.class);
        } catch (Exception e) {
            throw new IllegalStateException("Unreadable draft", e);
        }
    }

    String write(AgentOutput.DraftOut d) {
        try {
            return mapper.writeValueAsString(d);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
