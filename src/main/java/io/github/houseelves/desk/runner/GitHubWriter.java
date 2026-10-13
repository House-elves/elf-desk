package io.github.houseelves.desk.runner;

import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * Writes to GitHub through the runner, which sends them with the machine's
 * {@code gh} login (the "GitHub CLI" app orgs have already approved, the same
 * as Claude Code). The desk itself holds no GitHub write token. Only
 * {@link io.github.houseelves.desk.thread.Poster} calls this, after you
 * confirm in the page; the runner accepts only these requests.
 */
@ApplicationScoped
public class GitHubWriter {

    static final long TIMEOUT_SECONDS = 60;

    @Inject
    RunnerHub hub;

    @Inject
    ObjectMapper mapper;

    /** GitHub said no (or the runner couldn't send it). {@code body} is GitHub's JSON response when there is one. */
    public static class WriteFailed extends RuntimeException {
        public final int status;
        public final String body;

        public WriteFailed(int status, String body, String message) {
            super(message);
            this.status = status;
            this.body = body;
        }
    }

    /** body: {event, body, comments: [{path, line, side, body}]} */
    public JsonNode createReview(String repo, int number, Map<String, Object> body) {
        return write("POST", "/repos/" + repo + "/pulls/" + number + "/reviews", body);
    }

    /** Works for issues and PRs. body: {body} */
    public JsonNode createComment(String repo, int number, Map<String, Object> body) {
        return write("POST", "/repos/" + repo + "/issues/" + number + "/comments", body);
    }

    /** Opens a new issue. body: {title, body} */
    public JsonNode createIssue(String repo, String title, String body) {
        return write("POST", "/repos/" + repo + "/issues", Map.of("title", title, "body", body), RunnerHub.ISSUE_SINCE);
    }

    /**
     * Opens a PR from {@code head} ("you:branch", the runner refuses anyone else's fork) onto {@code base}, as a draft
     * ({@link #readyForReview} later) or ready for review.
     */
    public JsonNode createPull(String repo, String title, String head, String base, String body, boolean draft) {
        return write("POST", "/repos/" + repo + "/pulls",
                Map.of("title", title, "head", head, "base", base, "body", body, "draft", draft),
                draft ? RunnerHub.PR_SINCE : RunnerHub.READY_PR_SINCE);
    }

    /** Marks your own draft PR ready for review (the runner refuses anyone else's). */
    public JsonNode readyForReview(String repo, int number) {
        return send(hub.message("github.ready").put("repo", repo).put("number", number), RunnerHub.PR_SINCE);
    }

    /** Brings a branch of your fork up to date with its upstream, as GitHub's "Sync fork" does. */
    public JsonNode syncFork(String fork, String branch) {
        return write("POST", "/repos/" + fork + "/merge-upstream", Map.of("branch", branch), RunnerHub.PR_SINCE);
    }

    /** Closes an issue or PR. */
    public JsonNode close(String repo, int number) {
        return write("PATCH", "/repos/" + repo + "/issues/" + number, Map.of("state", "closed"), RunnerHub.WRITES_SINCE);
    }

    /** Adds {@code login} (you: the runner refuses anyone else) to an issue's or PR's assignees. */
    public JsonNode assign(String repo, int number, String login) {
        return write("POST", "/repos/" + repo + "/issues/" + number + "/assignees", Map.of("assignees", List.of(login)),
                RunnerHub.ASSIGN_SINCE);
    }

    /** Reopens an issue or PR (with close, this re-runs a PR's CI when you can't re-run its workflows). */
    public JsonNode reopen(String repo, int number) {
        return write("PATCH", "/repos/" + repo + "/issues/" + number, Map.of("state", "open"), RunnerHub.REOPEN_SINCE);
    }

    /** Merges a PR, but only if its head is still {@code sha}. method: merge, squash or rebase. */
    public JsonNode merge(String repo, int number, String method, String sha) {
        return write("PUT", "/repos/" + repo + "/pulls/" + number + "/merge", Map.of("merge_method", method, "sha", sha),
                RunnerHub.MERGE_SINCE);
    }

    /** Re-runs the failed jobs of one GitHub Actions workflow run. */
    public JsonNode rerunFailedJobs(String repo, long runId) {
        return write("POST", "/repos/" + repo + "/actions/runs/" + runId + "/rerun-failed-jobs", Map.of(),
                RunnerHub.CI_SINCE);
    }

    /**
     * GitHub's "Update branch" for a PR behind its base: REBASE or MERGE, done by GitHub, and only if the PR's head
     * is still {@code expectedHead}.
     */
    public JsonNode updateBranch(String repo, int number, String expectedHead, String method) {
        ObjectNode msg = hub.message("github.updateBranch").put("repo", repo).put("number", number)
                .put("expectedHead", expectedHead).put("method", method);
        return send(msg, RunnerHub.UPDATE_BRANCH_SINCE);
    }

    /** Approves a workflow run waiting for a maintainer (a fork PR's CI): GitHub's "Approve and run". */
    public JsonNode approveRun(String repo, long runId) {
        return write("POST", "/repos/" + repo + "/actions/runs/" + runId + "/approve", Map.of(), RunnerHub.APPROVE_SINCE);
    }

    /**
     * Pushes the agent's commits from the PR's worktree on the runner to the PR's branch, only if the branch is
     * still at {@code from}. Fast-forward, or with {@code squashBase}: {@code to} is one commit on that base and
     * replaces the branch (a force push with a lease on {@code from}).
     */
    public JsonNode push(String repo, int number, String headRepo, String headRef, String from, String to,
            String squashBase) {
        ObjectNode msg = hub.message("git.push").put("repo", repo).put("number", number).put("headRepo", headRepo)
                .put("headRef", headRef).put("from", from).put("to", to);
        if (squashBase != null) msg.put("squash", true).put("base", squashBase);
        return send(msg, squashBase != null ? RunnerHub.SQUASH_SINCE : RunnerHub.CI_SINCE);
    }

    /**
     * Has the runner squash a PR into one commit on its base, in the PR's worktree (nothing is pushed). Returns
     * what {@link #push} needs: {from, to, base, commits, diffstat, replaces}, or {already: true}.
     */
    public JsonNode squash(ObjectNode checkout) {
        ObjectNode msg = hub.message("git.squash");
        msg.set("checkout", checkout);
        return send(msg, RunnerHub.SQUASH_SINCE);
    }

    JsonNode write(String method, String path, Map<String, Object> body) {
        return write(method, path, body, RunnerHub.WRITES_SINCE);
    }

    JsonNode write(String method, String path, Map<String, Object> body, String runnerVersion) {
        ObjectNode msg = hub.message("github.write").put("method", method).put("path", path);
        msg.set("body", mapper.valueToTree(body));
        return send(msg, runnerVersion);
    }

    private JsonNode send(ObjectNode msg, String runnerVersion) {
        JsonNode result;
        try {
            result = hub.request(msg, runnerVersion).get(
                    msg.path("type").asText().startsWith("git.") ? 3 * TIMEOUT_SECONDS : TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            throw new WriteFailed(0, null, "The runner didn't answer within " + TIMEOUT_SECONDS
                    + " seconds. Check GitHub before trying again: it may have gone through.");
        } catch (Exception e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            throw new WriteFailed(0, null, cause.getMessage());
        }
        int status = result.path("status").asInt();
        String responseBody = result.path("body").asText("");
        if (status < 200 || status >= 300) {
            String message = result.path("error").asText(null);
            throw new WriteFailed(status, responseBody, message != null ? message : "HTTP " + status);
        }
        try {
            return responseBody.isBlank() ? mapper.createObjectNode() : mapper.readTree(responseBody);
        } catch (Exception e) {
            return mapper.createObjectNode();
        }
    }
}
