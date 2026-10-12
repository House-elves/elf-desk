package io.github.houseelves.desk.conflicts;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.github.houseelves.desk.agent.Prompts;
import io.github.houseelves.desk.github.GitHub;
import io.github.houseelves.desk.github.Item;
import io.github.houseelves.desk.github.ItemContext;
import io.github.houseelves.desk.runner.GitHubWriter;
import io.github.houseelves.desk.thread.DeskThread;
import io.github.houseelves.desk.thread.Draft;
import io.github.houseelves.desk.thread.GitHubAction;
import io.github.houseelves.desk.thread.Job;
import io.github.houseelves.desk.thread.Poster;
import io.github.houseelves.desk.thread.ThreadService;
import io.github.houseelves.desk.thread.Turn;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;

@QuarkusTest
class ConflictServiceTest {

    static final String BUMP = "quarkusio/quarkus#57300";
    static final String PR = "quarkusio/quarkus#57301";

    @Inject
    ConflictService conflicts;

    @Inject
    ThreadService threads;

    @Inject
    Poster poster;

    @InjectMock
    GitHub github;

    @InjectMock
    GitHubWriter writer;

    final ObjectMapper mapper = new ObjectMapper();

    static ItemContext ctx(int number, String author) {
        return new ItemContext("quarkusio/quarkus", number, Item.Kind.PR, "Bump org.apache.maven.plugins:maven-compiler-plugin",
                "https://github.com/quarkusio/quarkus/pull/" + number, author, "NONE", "Bumps it.", "main",
                "dependabot/maven/x", List.of());
    }

    @BeforeEach
    void setUp() {
        QuarkusTransaction.requiringNew().run(() -> {
            GitHubAction.deleteAll();
            Draft.deleteAll();
            Job.deleteAll();
            Turn.deleteAll();
            DeskThread.deleteAll();
        });
        when(github.context(anyString(), eq("quarkusio/quarkus"), eq(57300))).thenReturn(ctx(57300, "dependabot"));
        when(github.context(anyString(), eq("quarkusio/quarkus"), eq(57301))).thenReturn(ctx(57301, "someone"));
        when(github.ci(anyString(), eq("quarkusio/quarkus"), anyInt())).thenReturn(new GitHub.CiReport("a".repeat(40), "SUCCESS",
                List.of(), List.of(), "someone/quarkus", "feature", true));
    }

    @Test
    void dependabotPrsGetARebaseRequest() throws Exception {
        ConflictService.Options o = conflicts.options(BUMP, "t");
        assertTrue(o.dependabot());
        assertEquals("main", o.base());

        when(writer.createComment(eq("quarkusio/quarkus"), eq(57300), eq(Map.of("body", "@dependabot rebase"))))
                .thenReturn(mapper.readTree("{\"html_url\": \"https://github.com/quarkusio/quarkus/pull/57300#issuecomment-1\"}"));
        assertEquals("https://github.com/quarkusio/quarkus/pull/57300#issuecomment-1", conflicts.askDependabotToRebase(BUMP, "phillip-kruger", "t"));
        GitHubAction a = QuarkusTransaction.requiringNew().call(() -> GitHubAction.<GitHubAction>findAll().firstResult());
        assertTrue(a.request.contains("@dependabot rebase"));

        assertThrows(IllegalStateException.class, () -> conflicts.askDependabotToRebase(PR, "me", "t"));
    }

    @Test
    void otherPrsGetTheAgentWithThePushTarget() {
        conflicts.resolve(PR, "phillip-kruger", "t");
        Job j = QuarkusTransaction.requiringNew().call(() -> Job.<Job>findAll().firstResult());
        assertEquals(Prompts.CONFLICTS, j.action);
        assertTrue(j.prompt.contains("`git merge origin/main`"));
        assertTrue(j.prompt.contains("Don't rebase"));
        assertTrue(j.checkout.contains("\"headRepo\":\"someone/quarkus\""));
    }

    @Test
    void behindPrsAreUpdatedByGitHubPinnedToTheHead() throws Exception {
        when(writer.updateBranch("quarkusio/quarkus", 57301, "a".repeat(40), "REBASE")).thenReturn(mapper.createObjectNode());
        assertEquals("rebased", conflicts.updateBranch(PR, "REBASE", "phillip-kruger", "t"));
        verify(writer).updateBranch("quarkusio/quarkus", 57301, "a".repeat(40), "REBASE");
        GitHubAction a = QuarkusTransaction.requiringNew().call(() -> GitHubAction.<GitHubAction>findAll().firstResult());
        assertEquals("update-branch", a.kind);

        assertThrows(IllegalArgumentException.class, () -> conflicts.updateBranch(PR, "SQUASH", "me", "t"));
        assertThrows(IllegalArgumentException.class, () -> conflicts.updateBranch(PR, "MERGE", "me", "t"));

        // Dependabot: ask it to rebase instead of updating its branch ourselves.
        when(writer.createComment(eq("quarkusio/quarkus"), eq(57300), any())).thenReturn(mapper.readTree("{\"html_url\": \"u\"}"));
        assertEquals("rebase-requested", conflicts.updateBranch(BUMP, "REBASE", "me", "t"));
        verify(writer, never()).updateBranch(eq("quarkusio/quarkus"), eq(57300), any(), any());
    }

    @Test
    void noPushNoResolve() {
        when(github.ci(anyString(), eq("quarkusio/quarkus"), anyInt())).thenReturn(new GitHub.CiReport("a".repeat(40), "SUCCESS",
                List.of(), List.of(), "someone/quarkus", "feature", false));
        assertThrows(IllegalStateException.class, () -> conflicts.resolve(PR, "me", "t"));
        assertThrows(IllegalStateException.class, () -> conflicts.updateBranch(PR, "REBASE", "me", "t"));
        verify(writer, never()).push(any(), anyInt(), any(), any(), any(), any(), any());
    }

    static final String OLD = "a".repeat(40), SQUASHED = "c".repeat(40);

    static String squashedGit() {
        return """
                {"from": "%s", "to": "%s", "base": "main", "squashed": true, "replaces": 3,
                 "commits": [{"sha": "%s", "subject": "Bump org.apache.maven.plugins:maven-compiler-plugin"}],
                 "diffstat": " pom.xml | 2 +-"}""".formatted(OLD, SQUASHED, SQUASHED);
    }

    @Test
    void resolvedConflictsArePushedAsOneSquashedCommit() throws Exception {
        conflicts.resolve(PR, "phillip-kruger", "t");
        long job = threads.view(PR).job().id();
        threads.jobDone(job, "s", mapper.readTree("{\"reply\": \"Merged main in.\", \"draft\": null}"), null,
                mapper.readTree(squashedGit()));

        var draft = threads.view(PR).draft();
        assertTrue(draft.push().squashed());
        Poster.Preview p = poster.preview(draft.id());
        assertTrue(p.target().startsWith("Replace someone/quarkus:feature (3 commits) with 1 squashed commit on main"), p.target());
        when(writer.push("quarkusio/quarkus", 57301, "someone/quarkus", "feature", OLD, SQUASHED, "main"))
                .thenReturn(mapper.createObjectNode());
        poster.post(draft.id(), p.confirm(), "phillip-kruger");
        verify(writer).push("quarkusio/quarkus", 57301, "someone/quarkus", "feature", OLD, SQUASHED, "main");
        assertTrue(threads.view(PR).turns().stream().anyMatch(t -> t.text().startsWith("You replaced someone/quarkus:feature")));
    }

    @Test
    void squashWithoutAnAgentIsOfferedAsAPush() throws Exception {
        when(writer.squash(any())).thenReturn((com.fasterxml.jackson.databind.node.ObjectNode) mapper.readTree(squashedGit()));
        var view = conflicts.squash(PR, "t");
        assertTrue(view.draft().push().squashed());
        assertEquals("feature", view.draft().push().headRef());
        verify(writer, never()).push(any(), anyInt(), any(), any(), any(), any(), any());
        // Only one push waiting at a time.
        assertThrows(IllegalStateException.class, () -> conflicts.squash(PR, "t"));
    }

    @Test
    void aSquashThatFailsOffersNothing() throws Exception {
        conflicts.resolve(PR, "phillip-kruger", "t");
        long job = threads.view(PR).job().id();
        threads.jobDone(job, "s", mapper.readTree("{\"reply\": \"Done.\", \"draft\": null}"), null,
                mapper.readTree("{\"error\": \"Couldn't squash the PR into one commit: boom\"}"));
        assertEquals(null, threads.view(PR).draft());
        assertTrue(threads.view(PR).turns().stream().anyMatch(t -> t.text().contains("boom")));
    }
}
