package io.github.houseelves.desk.thread;

import java.time.Duration;
import java.util.ArrayList;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;


import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.github.houseelves.desk.agent.AgentOutput;
import io.github.houseelves.desk.github.GitHub;
import io.github.houseelves.desk.runner.GitHubWriter;
import io.github.houseelves.desk.runner.Live;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * The only code that writes to GitHub. Posting takes two calls: {@link #preview}
 * shows exactly what will be sent and returns a one-time confirmation, and
 * {@link #post} sends it, through the runner and its {@code gh} login, only if
 * that confirmation matches the draft as it was previewed.
 */
@ApplicationScoped
public class Poster {

    static final Duration CONFIRM_FOR = Duration.ofMinutes(10);

    @Inject
    GitHubWriter writes;

    @Inject
    GitHub github;

    @Inject
    ObjectMapper mapper;

    @Inject
    Live live;


    private record Pending(String token, String payload, Instant expires) {
    }

    private final Map<Long, Pending> confirmations = new ConcurrentHashMap<>();

    /**
     * @param target  in words, where this goes: "Review on quarkusio/quarkus#50087"
     * @param confirm pass this to {@link #post} to send it
     */
    public record Preview(long draftId, String target, String kind, AgentOutput.DraftOut draft, PushOut push,
            String url, String confirm) {
    }

    /** @param pushedTo the item whose PR branch was pushed to (CI starts again), or null for a review or comment */
    public record Posted(String url, String pushedTo) {
    }

    public Preview preview(long draftId) {
        return preview(draftId, null);
    }

    /**
     * @param githubToken your read token, to work out where a push goes when the draft doesn't say yet (commits
     *                    from a reply rather than "Fix this")
     */
    public Preview preview(long draftId, String githubToken) {
        resolvePushTarget(draftId, githubToken);
        checkIssueRepo(draftId, githubToken);
        return QuarkusTransaction.requiringNew().call(() -> {
            Draft d = pendingDraft(draftId);
            DeskThread t = DeskThread.findById(d.threadId);
            String token = UUID.randomUUID().toString();
            confirmations.put(draftId, new Pending(token, d.payload, Instant.now().plus(CONFIRM_FOR)));
            if (d.kind.equals(Draft.PUSH)) {
                PushOut push = mapper.readValue(d.payload, PushOut.class);
                String target = push.squashed()
                        ? "Replace " + push.headRepo() + ":" + push.headRef() + " (" + push.replaces()
                                + (push.replaces() == 1 ? " commit" : " commits") + ") with 1 squashed commit on "
                                + push.base() + ". A force push, refused if the branch changed since."
                        : "Push " + push.commits().size() + (push.commits().size() == 1 ? " commit" : " commits")
                                + " to " + push.headRepo() + ":" + push.headRef();
                return new Preview(draftId, target, d.kind, null, push, t.url, token);
            }
            AgentOutput.DraftOut out = mapper.readValue(d.payload, AgentOutput.DraftOut.class);
            if (d.kind.equals(Draft.ISSUE)) {
                return new Preview(draftId, "New issue in " + out.repo(), d.kind, out, null,
                        "https://github.com/" + out.repo() + "/issues", token);
            }
            String target = (d.kind.equals(Draft.REVIEW) ? "Review on " : "Comment on ") + t.itemKey;
            return new Preview(draftId, target, d.kind, out, null, t.url, token);
        });
    }

    public Posted post(long draftId, String confirm, String login) {
        Pending p = confirmations.remove(draftId);
        Draft draft = QuarkusTransaction.requiringNew().call(() -> pendingDraft(draftId));
        if (p == null || !p.token().equals(confirm) || Instant.now().isAfter(p.expires())
                || !p.payload().equals(draft.payload)) {
            throw new IllegalStateException("Preview it again before posting: the draft changed or the preview expired");
        }
        DeskThread t = QuarkusTransaction.requiringNew().call(() -> DeskThread.findById(draft.threadId));
        if (draft.kind.equals(Draft.PUSH)) return push(draft, t, login);
        AgentOutput.DraftOut out = read(draft.payload);
        if (draft.kind.equals(Draft.ISSUE)) return openIssue(draft, out, t, login);

        Map<String, Object> request = new LinkedHashMap<>();
        JsonNode response;
        try {
            if (draft.kind.equals(Draft.REVIEW)) {
                request.put("event", out.event() == null ? "COMMENT" : out.event());
                request.put("body", out.body());
                request.put("comments", out.comments().stream()
                        .map(c -> Map.of("path", c.path(), "line", c.line(), "side", "RIGHT", "body", c.body()))
                        .toList());
                response = writes.createReview(t.repo(), t.number(), request);
            } else {
                request.put("body", out.body());
                response = writes.createComment(t.repo(), t.number(), request);
            }
        } catch (GitHubWriter.WriteFailed e) {
            throw new IllegalStateException("GitHub refused it: " + githubMessage(e));
        }
        String url = response.path("html_url").asText(t.url);
        String requestJson = mapper.valueToTree(request).toString();

        QuarkusTransaction.requiringNew().run(() -> {
            Draft d = Draft.findById(draftId);
            d.state = Draft.POSTED;
            d.postedUrl = url;
            d.postedAt = Instant.now();
            GitHubAction a = new GitHubAction();
            a.threadId = t.id;
            a.draftId = draftId;
            a.kind = draft.kind.equals(Draft.REVIEW) ? "review" : "comment";
            a.request = requestJson;
            a.resultUrl = url;
            a.login = login;
            a.persist();
            Turn.add(t.id, Turn.SYSTEM, null,
                    "You posted the " + (draft.kind.equals(Draft.REVIEW) ? "review" : "comment") + ": " + url);
        });
        live.changed(t.itemKey);
        return new Posted(url, null);
    }

    /** The desk only writes to public repos: an issue draft's repo is checked with your read token before preview. */
    private void checkIssueRepo(long draftId, String githubToken) {
        Draft d = QuarkusTransaction.requiringNew().call(() -> pendingDraft(draftId));
        if (!d.kind.equals(Draft.ISSUE)) return;
        String repo = read(d.payload).repo();
        if (githubToken == null || !github.isPublic(githubToken, repo)) {
            throw new IllegalStateException(repo + " isn't a public repo on GitHub, so the desk won't open an issue there."
                    + " Check the repo name in the draft.");
        }
    }

    /**
     * Opens the drafted issue in its repo. If the draft has a follow-up comment for this thread's item, that becomes
     * the next draft, with the new issue's link filled in, for you to post with its own click.
     */
    private Posted openIssue(Draft draft, AgentOutput.DraftOut out, DeskThread t, String login) {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("title", out.title());
        request.put("body", out.body());
        JsonNode response;
        try {
            response = writes.createIssue(out.repo(), out.title(), out.body());
        } catch (GitHubWriter.WriteFailed e) {
            throw new IllegalStateException("GitHub refused it: " + githubMessage(e));
        }
        String url = response.path("html_url").asText(null);
        if (url == null) throw new IllegalStateException("GitHub didn't say where the issue is. Check " + out.repo());
        String requestJson = mapper.valueToTree(Map.of("repo", out.repo(), "issue", request)).toString();
        String followUp = out.then() == null || out.then().isBlank() ? null
                : mapper.valueToTree(new AgentOutput.DraftOut(Draft.COMMENT, null,
                        out.then().replace(AgentOutput.DraftOut.ISSUE_LINK, url), List.of())).toString();

        QuarkusTransaction.requiringNew().run(() -> {
            Draft d = Draft.findById(draft.id);
            d.state = Draft.POSTED;
            d.postedUrl = url;
            d.postedAt = Instant.now();
            GitHubAction a = new GitHubAction();
            a.threadId = t.id;
            a.draftId = draft.id;
            a.kind = "issue";
            a.request = requestJson;
            a.resultUrl = url;
            a.login = login;
            a.persist();
            Turn.add(t.id, Turn.SYSTEM, null, "You opened the issue in " + out.repo() + ": " + url);
            if (followUp != null) {
                Draft next = new Draft();
                next.threadId = t.id;
                next.jobId = draft.jobId;
                next.kind = Draft.COMMENT;
                next.payload = followUp;
                next.persist();
            }
        });
        live.changed(t.itemKey);
        return new Posted(url, null);
    }

    /**
     * Closes an issue or PR on GitHub as you. The page asks you to confirm first; there is no draft to preview,
     * the request is just {state: closed}.
     */
    public String close(String itemKey, String login) {
        if (!itemKey.matches("[A-Za-z0-9-]+/[A-Za-z0-9._-]+#\\d+")) {
            throw new IllegalArgumentException("Not an issue or PR: " + itemKey);
        }
        String repo = itemKey.substring(0, itemKey.indexOf('#'));
        int number = Integer.parseInt(itemKey.substring(itemKey.indexOf('#') + 1));
        JsonNode response;
        try {
            response = writes.close(repo, number);
        } catch (GitHubWriter.WriteFailed e) {
            throw new IllegalStateException("GitHub refused it: " + githubMessage(e));
        }
        String url = response.path("html_url").asText("https://github.com/" + repo + "/issues/" + number);
        boolean pr = response.path("pull_request").isObject();
        QuarkusTransaction.requiringNew().run(() -> {
            DeskThread t = DeskThread.byKey(itemKey);
            GitHubAction a = new GitHubAction();
            a.threadId = t == null ? null : t.id;
            a.kind = "close";
            a.request = "{\"item\":\"" + itemKey + "\",\"state\":\"closed\"}";
            a.resultUrl = url;
            a.login = login;
            a.persist();
            if (t != null) Turn.add(t.id, Turn.SYSTEM, null, "You closed this " + (pr ? "PR" : "issue") + " on GitHub.");
        });
        live.changed(itemKey);
        return url;
    }

    /**
     * Assigns you to an issue or PR on GitHub. GitHub quietly ignores assignees without write access to the repo,
     * so the answer is checked.
     */
    public void assign(String itemKey, String login) {
        if (!itemKey.matches("[A-Za-z0-9-]+/[A-Za-z0-9._-]+#\\d+")) {
            throw new IllegalArgumentException("Not an issue or PR: " + itemKey);
        }
        String repo = itemKey.substring(0, itemKey.indexOf('#'));
        int number = Integer.parseInt(itemKey.substring(itemKey.indexOf('#') + 1));
        JsonNode response;
        try {
            response = writes.assign(repo, number, login);
        } catch (GitHubWriter.WriteFailed e) {
            throw new IllegalStateException("GitHub refused it: " + githubMessage(e));
        }
        boolean assigned = false;
        for (JsonNode a : response.path("assignees")) assigned |= a.path("login").asText().equalsIgnoreCase(login);
        if (!assigned) {
            throw new IllegalStateException("GitHub didn't assign you. It only assigns people with write access to "
                    + repo + ".");
        }
        QuarkusTransaction.requiringNew().run(() -> {
            DeskThread t = DeskThread.byKey(itemKey);
            GitHubAction a = new GitHubAction();
            a.threadId = t == null ? null : t.id;
            a.kind = "assign";
            a.request = "{\"item\":\"" + itemKey + "\",\"assignee\":\"" + login + "\"}";
            a.resultUrl = response.path("html_url").asText(null);
            a.login = login;
            a.persist();
            if (t != null) Turn.add(t.id, Turn.SYSTEM, null, "You assigned yourself on GitHub.");
        });
        live.changed(itemKey);
    }

    /** Fills in a push draft's target branch, and refuses when it can't be pushed to. */
    private void resolvePushTarget(long draftId, String githubToken) {
        Draft d = QuarkusTransaction.requiringNew().call(() -> pendingDraft(draftId));
        if (!d.kind.equals(Draft.PUSH)) return;
        PushOut p = readPush(d.payload);
        if (p.headRepo() != null && p.headRef() != null) return;
        if (githubToken == null) throw new IllegalStateException("Sign in again to push");
        DeskThread t = QuarkusTransaction.requiringNew().call(() -> DeskThread.findById(d.threadId));
        GitHub.CiReport target = github.ci(githubToken, t.repo(), t.number());
        if (!target.canPush()) {
            throw new IllegalStateException("These commits can't be pushed: the author of " + t.itemKey
                    + " didn't allow edits from maintainers.");
        }
        PushOut resolved = p.withTarget(target.headRepo(), target.headRef());
        QuarkusTransaction.requiringNew().run(() -> {
            Draft x = Draft.findById(draftId);
            x.payload = mapper.valueToTree(resolved).toString();
        });
    }

    private PushOut readPush(String json) {
        try {
            return mapper.readValue(json, PushOut.class);
        } catch (Exception e) {
            throw new IllegalStateException("Unreadable push draft", e);
        }
    }

    private Posted push(Draft draft, DeskThread t, String login) {
        PushOut p;
        try {
            p = mapper.readValue(draft.payload, PushOut.class);
        } catch (Exception e) {
            throw new IllegalStateException("Unreadable push draft", e);
        }
        try {
            writes.push(t.repo(), t.number(), p.headRepo(), p.headRef(), p.from(), p.to(), p.squashed() ? p.base() : null);
        } catch (GitHubWriter.WriteFailed e) {
            throw new IllegalStateException("The push didn't go through: " + e.getMessage());
        }
        String url = t.url + "/commits/" + p.to();
        QuarkusTransaction.requiringNew().run(() -> {
            Draft d = Draft.findById(draft.id);
            d.state = Draft.POSTED;
            d.postedUrl = url;
            d.postedAt = Instant.now();
            GitHubAction a = new GitHubAction();
            a.threadId = t.id;
            a.draftId = draft.id;
            a.kind = "push";
            a.request = draft.payload;
            a.resultUrl = url;
            a.login = login;
            a.persist();
            Turn.add(t.id, Turn.SYSTEM, null, p.squashed()
                    ? "You replaced " + p.headRepo() + ":" + p.headRef() + " with one squashed commit. CI will run again."
                    : "You pushed " + p.commits().size() + (p.commits().size() == 1 ? " commit" : " commits") + " to "
                            + p.headRepo() + ":" + p.headRef() + ". CI will run again.");
        });
        live.changed(t.itemKey);
        return new Posted(url, t.itemKey);
    }

    private static Draft pendingDraft(long draftId) {
        Draft d = Draft.findById(draftId);
        if (d == null || !d.state.equals(Draft.PENDING)) {
            throw new IllegalStateException("That draft was already posted or discarded");
        }
        return d;
    }

    private AgentOutput.DraftOut read(String json) {
        try {
            return mapper.readValue(json, AgentOutput.DraftOut.class);
        } catch (Exception e) {
            throw new IllegalStateException("Unreadable draft", e);
        }
    }

    /** GitHub's own explanation, e.g. a 422 for an inline comment on a line outside the diff. */
    String githubMessage(GitHubWriter.WriteFailed e) {
        if (e.body == null || e.body.isBlank()) return e.getMessage();
        try {
            JsonNode body = mapper.readTree(e.body);
            StringBuilder sb = new StringBuilder(body.path("message").asText(e.getMessage()));
            List<String> details = new ArrayList<>();
            body.path("errors").forEach(x -> details.add(x.isTextual() ? x.asText() : x.path("message").asText(x.toString())));
            if (!details.isEmpty()) sb.append(" (").append(String.join("; ", details)).append(')');
            return sb.toString();
        } catch (Exception ignored) {
            return e.getMessage();
        }
    }
}
