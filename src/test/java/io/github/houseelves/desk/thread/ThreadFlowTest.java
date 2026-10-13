package io.github.houseelves.desk.thread;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.github.houseelves.desk.Contexts;
import io.github.houseelves.desk.agent.AgentOutput;
import io.github.houseelves.desk.agent.Prompts;
import io.github.houseelves.desk.github.GitHub;
import io.github.houseelves.desk.runner.GitHubWriter;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;

/** Start a review, let the "runner" answer, edit the draft, post it: no real runner or GitHub. */
@QuarkusTest
class ThreadFlowTest {

    static final String KEY = "quarkusio/quarkus#50087";

    @Inject
    ThreadService threads;

    @Inject
    Poster poster;

    @Inject
    ObjectMapper mapper;

    @InjectMock
    GitHub github;

    @InjectMock
    GitHubWriter writes;

    @BeforeEach
    void clean() {
        QuarkusTransaction.requiringNew().run(() -> {
            GitHubAction.deleteAll();
            Draft.deleteAll();
            Job.deleteAll();
            Turn.deleteAll();
            DeskThread.deleteAll();
        });
        when(github.context(any(), eq("quarkusio/quarkus"), eq(50087))).thenReturn(Contexts.pr("quarkusio/quarkus", 50087,
                "CONTRIBUTOR"));
    }

    JsonNode output(String reply, String draftJson) throws Exception {
        return mapper.readTree("{\"reply\": " + mapper.writeValueAsString(reply) + ", \"draft\": " + draftJson + "}");
    }

    long openJob() {
        return threads.view(KEY).job().id();
    }

    @Test
    void reviewRunsToADraftAndPostsOnlyWithTheConfirmation() throws Exception {
        ThreadView v = threads.start(KEY, Prompts.REVIEW, null, "phillip-kruger", "gho_t");
        assertEquals("queued", v.job().state()); // no runner connected in tests
        assertEquals("Review this PR.", v.turns().get(0).text());
        Job job = QuarkusTransaction.requiringNew().call(() -> Job.findById(openJob()));
        assertTrue(job.untrusted.contains("Updates Vert.x")); // a CONTRIBUTOR's PR gets triaged
        assertTrue(job.checkout.contains("\"base\":\"main\""));

        assertThrows(IllegalStateException.class, () -> threads.reply(KEY, "and?", "phillip-kruger", "gho_t"));

        threads.jobAccepted(job.id);
        assertEquals("running", threads.view(KEY).job().state());
        threads.jobDone(job.id, "sess-1", output("One real problem.", """
                {"kind": "review", "event": "REQUEST_CHANGES", "body": "The test hard-codes the old default.",
                 "comments": [{"path": "core/Foo.java", "line": 12, "body": "Use the config property."}]}
                """), new BigDecimal("0.42"));

        v = threads.view(KEY);
        assertNull(v.job());
        assertEquals("One real problem.", v.turns().get(1).text());
        long draftId = v.draft().id();
        assertEquals(1, v.draft().draft().comments().size());
        assertEquals("draft", threads.summaries().get(KEY).state());

        // Edit after preview: the old confirmation no longer works.
        Poster.Preview first = poster.preview(draftId);
        threads.updateDraft(draftId, new AgentOutput.DraftOut("review", "COMMENT", "Edited.", java.util.List.of()));
        assertThrows(IllegalStateException.class, () -> poster.post(draftId, first.confirm(), "phillip-kruger"));
        verify(writes, never()).createReview(any(), anyInt(), any());

        // A fresh preview, a wrong token, then the right one.
        Poster.Preview second = poster.preview(draftId);
        assertEquals("Review on " + KEY, second.target());
        assertThrows(IllegalStateException.class, () -> poster.post(draftId, "guess", "phillip-kruger"));
        second = poster.preview(draftId);
        when(writes.createReview(eq("quarkusio/quarkus"), eq(50087), any()))
                .thenReturn(mapper.readTree("{\"html_url\": \"https://github.com/quarkusio/quarkus/pull/50087#pullrequestreview-1\"}"));
        Poster.Posted posted = poster.post(draftId, second.confirm(), "phillip-kruger");
        assertEquals("https://github.com/quarkusio/quarkus/pull/50087#pullrequestreview-1", posted.url());

        v = threads.view(KEY);
        assertNull(v.draft());
        assertEquals(1, v.posted().size());
        assertTrue(v.turns().get(v.turns().size() - 1).text().startsWith("You posted the review"));
        GitHubAction audit = QuarkusTransaction.requiringNew().call(() -> GitHubAction.<GitHubAction>findAll().firstResult());
        assertEquals("phillip-kruger", audit.login);
        assertTrue(audit.request.contains("\"event\":\"COMMENT\""));
        assertTrue(audit.request.contains("Edited."));

        // Posting the same draft twice is refused.
        assertThrows(IllegalStateException.class, () -> poster.preview(draftId));

        // The follow-up resumes the session, carries no triage text, and tells the agent what you did since.
        threads.reply(KEY, "Thanks", "phillip-kruger", "gho_t");
        Job follow = QuarkusTransaction.requiringNew().call(() -> Job.findById(openJob()));
        assertEquals(Prompts.REPLY, follow.action);
        assertNull(follow.untrusted);
        assertTrue(follow.prompt.startsWith("Since your last answer, in the desk:\n- You posted the review"));
    }

    @Test
    void anAnswerStaysNewOnTheBoardUntilTheThreadPageShowsIt() throws Exception {
        threads.start(KEY, Prompts.REVIEW, null, "phillip-kruger", "gho_t");
        assertEquals("working", threads.summaries().get(KEY).state());
        threads.jobDone(openJob(), "s", output("Looks fine.", "null"), null);
        assertEquals("answered", threads.summaries().get(KEY).state());

        threads.seen(KEY);
        assertEquals("idle", threads.summaries().get(KEY).state());
    }

    @Test
    void closingCallsGitHubAndIsAudited() throws Exception {
        threads.start(KEY, Prompts.REVIEW, null, "me", "t"); // a thread exists, so it gets a note
        when(writes.close("quarkusio/quarkus", 50087))
                .thenReturn(mapper.readTree("{\"html_url\": \"https://github.com/quarkusio/quarkus/pull/50087\", \"pull_request\": {}}"));

        assertEquals("https://github.com/quarkusio/quarkus/pull/50087", poster.close(KEY, "phillip-kruger"));

        GitHubAction audit = QuarkusTransaction.requiringNew().call(() -> GitHubAction.<GitHubAction>findAll().firstResult());
        assertEquals("close", audit.kind);
        assertEquals("phillip-kruger", audit.login);
        assertTrue(threads.view(KEY).turns().stream().anyMatch(t -> t.text().equals("You closed this PR on GitHub.")));
        assertThrows(IllegalArgumentException.class, () -> poster.close("not a key", "me"));

        // GitHub's refusal comes back through the runner with its own explanation.
        when(writes.close("quarkusio/quarkus", 50087)).thenThrow(new GitHubWriter.WriteFailed(403,
                "{\"message\": \"Resource not accessible\"}", "HTTP 403"));
        var e = assertThrows(IllegalStateException.class, () -> poster.close(KEY, "phillip-kruger"));
        assertEquals("GitHub refused it: Resource not accessible", e.getMessage());
    }

    @Test
    void reviewsOfYourOwnPrCanOnlyComment() throws Exception {
        when(github.context(any(), eq("quarkusio/quarkus"), eq(50031))).thenReturn(new io.github.houseelves.desk.github.ItemContext(
                "quarkusio/quarkus", 50031, io.github.houseelves.desk.github.Item.Kind.PR, "Mine", "u", "phillip-kruger",
                "MEMBER", "b", "main", "x", java.util.List.of()));
        String key = "quarkusio/quarkus#50031";
        threads.start(key, Prompts.REVIEW, null, "phillip-kruger", "t");
        threads.jobDone(threads.view(key).job().id(), "s", output("Fix the null check.", """
                {"kind": "review", "event": "APPROVE", "body": "Looks fine."}"""), null);
        ThreadView v = threads.view(key);
        assertTrue(v.own());
        assertEquals("COMMENT", v.draft().draft().event()); // GitHub refuses an author's approval
        threads.updateDraft(v.draft().id(), new AgentOutput.DraftOut("review", "REQUEST_CHANGES", "Edited", java.util.List.of()));
        assertEquals("COMMENT", threads.view(key).draft().draft().event());
    }

    @Test
    void wrongActionForTheKindIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> threads.start(KEY, Prompts.INVESTIGATE, null, "me", "t"));
        assertThrows(IllegalArgumentException.class, () -> threads.start(KEY, Prompts.ASK, "  ", "me", "t"));
        assertThrows(IllegalArgumentException.class, () -> threads.start(KEY, "merge", null, "me", "t"));
    }

    @Test
    void failuresCancelsAndRunnerRestartsAreRecorded() throws Exception {
        threads.start(KEY, Prompts.REVIEW, null, "me", "t");
        long first = openJob();
        threads.jobFailed(first, "git fetch failed");
        ThreadView v = threads.view(KEY);
        assertNull(v.job());
        assertTrue(v.turns().get(1).text().contains("git fetch failed"));

        threads.start(KEY, Prompts.REVIEW, null, "me", "t");
        long second = openJob();
        threads.jobAccepted(second);
        threads.runnerConnected(Set.of()); // the runner came back without it
        assertTrue(threads.view(KEY).turns().stream().anyMatch(t -> t.text().contains("runner restarted")));

        threads.start(KEY, Prompts.REVIEW, null, "me", "t");
        long third = openJob();
        threads.cancel(KEY);
        threads.jobDone(third, "s", output("late", "null"), null); // arrives after the cancel: ignored
        v = threads.view(KEY);
        assertNull(v.job());
        assertTrue(v.turns().stream().noneMatch(t -> t.text().equals("late")));
    }

    @Test
    void anAnswerWithoutAReplyFailsTheJob() throws Exception {
        threads.start(KEY, Prompts.REVIEW, null, "me", "t");
        long job = openJob();
        threads.jobDone(job, "s", mapper.valueToTree(Map.of("nope", 1)), null);
        assertTrue(threads.view(KEY).turns().get(1).text().contains("without a usable answer"));
    }

    @Test
    void anIssueDraftOpensInAPublicRepoThenDraftsTheComment() throws Exception {
        threads.start(KEY, Prompts.REVIEW, null, "phillip-kruger", "gho_t");
        threads.jobDone(openJob(), "s", output("Reproduced in plain Vert.x.", """
                {"kind": "issue", "repo": "eclipse-vertx/vert.x", "title": "HTTP/2 over TLS stalls",
                 "body": "Steps to reproduce...", "then": "Filed upstream as {issue}."}
                """), null);
        ThreadView v = threads.view(KEY);
        assertEquals("issue", v.draft().kind());
        long draftId = v.draft().id();

        // An edit can't point it at something that isn't owner/name, or drop the title.
        assertThrows(IllegalArgumentException.class, () -> threads.updateDraft(draftId, new AgentOutput.DraftOut(
                "issue", null, "b", java.util.List.of(), "https://github.com/x", "t", null)));
        assertThrows(IllegalArgumentException.class, () -> threads.updateDraft(draftId, new AgentOutput.DraftOut(
                "issue", null, "b", java.util.List.of(), "eclipse-vertx/vert.x", " ", null)));

        // Private (or missing) repos are refused before anything is sent.
        when(github.isPublic("gho_t", "eclipse-vertx/vert.x")).thenReturn(false);
        assertThrows(IllegalStateException.class, () -> poster.preview(draftId, "gho_t"));

        when(github.isPublic("gho_t", "eclipse-vertx/vert.x")).thenReturn(true);
        Poster.Preview p = poster.preview(draftId, "gho_t");
        assertEquals("New issue in eclipse-vertx/vert.x", p.target());
        when(writes.createIssue("eclipse-vertx/vert.x", "HTTP/2 over TLS stalls", "Steps to reproduce..."))
                .thenReturn(mapper.readTree("{\"html_url\": \"https://github.com/eclipse-vertx/vert.x/issues/6001\"}"));
        Poster.Posted posted = poster.post(draftId, p.confirm(), "phillip-kruger");
        assertEquals("https://github.com/eclipse-vertx/vert.x/issues/6001", posted.url());

        // The follow-up comment on this PR is the next draft, with the link in it; nothing posted it yet.
        v = threads.view(KEY);
        assertEquals("comment", v.draft().kind());
        assertEquals("Filed upstream as https://github.com/eclipse-vertx/vert.x/issues/6001.", v.draft().draft().body());
        verify(writes, never()).createComment(any(), anyInt(), any());
        GitHubAction audit = QuarkusTransaction.requiringNew().call(() -> GitHubAction.<GitHubAction>findAll().firstResult());
        assertEquals("issue", audit.kind);

    }

    @Test
    void fixingAnIssueDraftsAPrFromYourForkAndOpensItOnlyWithTheConfirmation() throws Exception {
        String issue = "quarkusio/quarkus#32850";
        when(github.context(any(), eq("quarkusio/quarkus"), eq(32850)))
                .thenReturn(Contexts.issue("quarkusio/quarkus", 32850, "MEMBER"));
        assertThrows(IllegalArgumentException.class, () -> threads.start(KEY, Prompts.FIX, null, "phillip-kruger", "gho_t"));

        threads.start(issue, Prompts.FIX, null, "phillip-kruger", "gho_t");
        String fix = "a".repeat(40), base = "b".repeat(40);
        threads.jobDone(threads.view(issue).job().id(), "s", output("Mapped the root logger.", """
                {"kind": "pr", "title": "Let the Dev UI set the root log level", "body": "Lists the root logger."}
                """), null, mapper.readTree("""
                {"from": "%s", "to": "%s", "base": "main", "diffstat": " 1 file changed",
                 "commits": [{"sha": "%s", "subject": "Dev UI: root logger level"}]}
                """.formatted(base, fix, fix)));
        ThreadView v = threads.view(issue);
        assertEquals("pr", v.draft().kind());
        assertEquals("Lists the root logger.\n\nFixes #32850", v.draft().pr().body());
        long draftId = v.draft().id();

        // No fork: refused before anything is sent.
        when(github.isPublic("gho_t", "quarkusio/quarkus")).thenReturn(true);
        when(github.isForkOf("gho_t", "phillip-kruger/quarkus", "quarkusio/quarkus")).thenReturn(false);
        assertThrows(IllegalStateException.class, () -> poster.preview(draftId, "gho_t", "phillip-kruger"));

        when(github.isForkOf("gho_t", "phillip-kruger/quarkus", "quarkusio/quarkus")).thenReturn(true);
        when(github.linkedPrs(any(), eq(java.util.List.of(issue)))).thenReturn(Map.of(issue, java.util.List.of(
                new GitHub.LinkedPr("quarkusio/quarkus#33000", "Root logger", "https://github.com/quarkusio/quarkus/pull/33000",
                        "jdoe", false, false))));
        Poster.Preview p = poster.preview(draftId, "gho_t", "phillip-kruger");
        assertEquals("New PR on quarkusio/quarkus from phillip-kruger/quarkus:fix-32850-aaaaaaa", p.target());
        assertTrue(p.warning().contains("quarkusio/quarkus#33000"));
        verify(writes, never()).push(any(), anyInt(), any(), any(), any(), any(), any());

        when(writes.createPull("quarkusio/quarkus", "Let the Dev UI set the root log level", "phillip-kruger:fix-32850-aaaaaaa",
                "main", "Lists the root logger.\n\nFixes #32850"))
                .thenReturn(mapper.readTree("{\"html_url\": \"https://github.com/quarkusio/quarkus/pull/57300\"}"));
        Poster.Posted posted = poster.post(draftId, p.confirm(), "phillip-kruger");
        assertEquals("https://github.com/quarkusio/quarkus/pull/57300", posted.url());
        verify(writes).syncFork("phillip-kruger/quarkus", "main");
        verify(writes).push("quarkusio/quarkus", 32850, "phillip-kruger/quarkus", "fix-32850-aaaaaaa", base, fix, null);
        assertNull(threads.view(issue).draft());

        // A draft PR: ready for review is its own click, once you've read the change on GitHub.
        poster.ready("quarkusio/quarkus#57300", "phillip-kruger");
        verify(writes).readyForReview("quarkusio/quarkus", 57300);
    }

    @Test
    void investigatingAnIssueListsTheOpenPrsAlreadyOnIt() {
        String issue = "quarkusio/quarkus#49877";
        when(github.context(any(), eq("quarkusio/quarkus"), eq(49877)))
                .thenReturn(Contexts.issue("quarkusio/quarkus", 49877, "MEMBER"));
        when(github.linkedPrs(any(), eq(java.util.List.of(issue)))).thenReturn(Map.of(issue, java.util.List.of(
                new GitHub.LinkedPr("quarkusio/quarkus#49901", "Honour X-Forwarded-Prefix", "https://github.com/quarkusio/quarkus/pull/49901",
                        "jdoe", false, true))));
        threads.start(issue, Prompts.INVESTIGATE, null, "phillip-kruger", "gho_t");
        Job job = QuarkusTransaction.requiringNew().call(() -> Job.findById(threads.view(issue).job().id()));
        assertTrue(job.prompt.contains("## Open PRs linked to this issue"));
        assertTrue(job.prompt.contains("quarkusio/quarkus#49901 (says it fixes this issue), by @jdoe"));
    }
}
