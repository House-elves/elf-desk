package io.github.houseelves.desk.board;

import static io.github.houseelves.desk.Items.issue;
import static io.github.houseelves.desk.Items.pr;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;

import io.github.houseelves.desk.github.GitHub;
import io.github.houseelves.desk.github.Item;
import io.github.houseelves.desk.merge.MergeService;
import io.github.houseelves.desk.thread.ThreadView;

class FlowClassifierTest {

    static final String ME = "phillip-kruger";
    static final Instant NOW = Instant.parse("2026-10-14T00:00:00Z");

    /** A PR with the given GitHub state; everything else as {@code Items.pr}. */
    static Item pr2(String author, String ci, String mergeState, String mergeable, boolean approved, boolean draft,
            String reviewDecision) {
        Item p = pr("quarkusio/quarkus", 7, author);
        return new Item(p.key(), p.repo(), p.number(), p.kind(), p.title(), p.url(), p.author(), p.assignees(),
                p.createdAt(), p.updatedAt(), 0, draft, p.headRef(), p.headSha(), ci, reviewDecision, mergeable, mergeState,
                approved);
    }

    static FlowClassifier.Ctx ctx() {
        return ctx(null, null, null, null, List.of());
    }

    static FlowClassifier.Ctx ctx(ThreadView.Summary t, MergeService.View m, String sinceReview, Instant reviewedAt,
            List<GitHub.LinkedPr> links) {
        return new FlowClassifier.Ctx(ME, t, m, sinceReview, reviewedAt, links, null, false, NOW);
    }

    static ThreadView.Summary thread(String state, String action, String draftKind) {
        return new ThreadView.Summary("k", state, action, draftKind, NOW, false, null, null);
    }

    static void is(Flow f, String waiting, int step, String next) {
        assertEquals(waiting, f.waiting(), f.say());
        assertEquals(step, f.step(), f.say());
        if (next == null) assertNull(f.next(), f.say());
        else assertEquals(next, f.next().id(), f.say());
    }

    // ---- my PRs ----

    @Test
    void myPrMovesThroughCiReviewsAndMerge() {
        is(FlowClassifier.flow("mine", pr2(ME, "PENDING", "BLOCKED", "MERGEABLE", false, false, null), ctx()), Flow.CI, 1, null);
        is(FlowClassifier.flow("mine", pr2(ME, "FAILURE", "UNSTABLE", "MERGEABLE", false, false, null), ctx()), Flow.YOU, 1, "ci-why");
        is(FlowClassifier.flow("mine", pr2(ME, "SUCCESS", "CLEAN", "MERGEABLE", false, false, null), ctx()), Flow.OTHERS, 2, null);
        is(FlowClassifier.flow("mine", pr2(ME, "SUCCESS", "CLEAN", "MERGEABLE", false, false, "CHANGES_REQUESTED"), ctx()),
                Flow.YOU, 2, "read-review");
        is(FlowClassifier.flow("mine", pr2(ME, "SUCCESS", "CLEAN", "MERGEABLE", true, false, "APPROVED"), ctx()), Flow.YOU, 3, "merge");
        is(FlowClassifier.flow("mine", pr2(ME, "SUCCESS", "DRAFT", "MERGEABLE", false, true, null), ctx()), Flow.YOU, 0, "ready");
        is(FlowClassifier.flow("mine", pr2(ME, "SUCCESS", "DIRTY", "CONFLICTING", false, false, null), ctx()), Flow.YOU, 1, "conflicts");
        is(FlowClassifier.flow("mine", pr2(ME, "SUCCESS", "BEHIND", "MERGEABLE", true, false, null), ctx()), Flow.YOU, 1, "update-branch");
    }

    @Test
    void aReviewerSpeakingAfterYouMakesItYourMove() {
        Item waiting = pr2(ME, "SUCCESS", "BLOCKED", "MERGEABLE", false, false, "REVIEW_REQUIRED");
        Instant at = NOW.minusSeconds(3600);
        Flow f = FlowClassifier.flow("mine", waiting, new FlowClassifier.Ctx(ME, null, null, null, null, List.of(), null, false, NOW,
                new GitHub.Feedback("brunobat", at, "COMMENTED", true, 2)));
        is(f, Flow.YOU, 2, "read-review");
        assertEquals("address", f.more().get(0).id());
        assertEquals("brunobat reviewed, 2 threads open.", f.say());
        assertEquals(at, f.since());
        // You answered since: back to waiting on them, with the threads still mentioned.
        f = FlowClassifier.flow("mine", waiting, new FlowClassifier.Ctx(ME, null, null, null, null, List.of(), null, false, NOW,
                new GitHub.Feedback("brunobat", at, "COMMENTED", false, 1)));
        is(f, Flow.OTHERS, 2, null);
        org.junit.jupiter.api.Assertions.assertTrue(f.say().endsWith(", 1 thread open."), f.say());
    }

    @Test
    void afterTheAgentExplainedFailingCiTheNextStepIsTheFix() {
        Item red = pr2(ME, "FAILURE", "UNSTABLE", "MERGEABLE", false, false, null);
        is(FlowClassifier.flow("mine", red, ctx(thread("working", "ci-why", null), null, null, null, List.of())), Flow.AGENT, 1, "open");
        Flow answered = FlowClassifier.flow("mine", red, ctx(thread("answered", "ci-why", null), null, null, null, List.of()));
        is(answered, Flow.YOU, 1, "open");
        assertEquals("ci-fix", answered.more().get(0).id());
        is(FlowClassifier.flow("mine", red, ctx(thread("idle", "ci-why", null), null, null, null, List.of())), Flow.YOU, 1, "ci-fix");
        is(FlowClassifier.flow("mine", red, ctx(thread("draft", "ci-fix", "push"), null, null, null, List.of())), Flow.YOU, 1, "open");
    }

    @Test
    void aMergeTheDeskRunsDecidesFirst() {
        Item green = pr2(ME, "PENDING", "BLOCKED", "MERGEABLE", true, false, "APPROVED");
        is(FlowClassifier.flow("mine", green, ctx(null, new MergeService.View("k", "squash", "waiting", null, NOW, null), null, null,
                List.of())), Flow.CI, 3, "merge");
        is(FlowClassifier.flow("mine", green, ctx(null, new MergeService.View("k", "squash", "merged", "Merged.", NOW, NOW), null, null,
                List.of())), Flow.DONE, 3, null);
    }

    @Test
    void aSafeBumpWithFailingCiIsRebasedFirst() {
        Item bump = pr2("dependabot[bot]", "FAILURE", "UNSTABLE", "MERGEABLE", false, false, null);
        Board.MvnpmRow row = new Board.MvnpmRow(bump, new io.github.houseelves.desk.mvnpm.MvnpmElf.Check(bump.headSha(), "SAFE", true,
                null, NOW), true);
        Flow f = FlowClassifier.flow("mvnpm", bump, new FlowClassifier.Ctx(ME, null, null, null, null, List.of(), row, false, NOW));
        is(f, Flow.YOU, 1, "dependabot-rebase");
        assertEquals(List.of("ci-why", "ci-rerun", "close"), f.more().stream().map(Flow.Next::id).toList());
    }

    // ---- PRs to review ----

    @Test
    void reviewThenWaitForTheAuthorThenMerge() {
        Item open = pr2("someone", "SUCCESS", "BLOCKED", "MERGEABLE", false, false, "REVIEW_REQUIRED");
        is(FlowClassifier.flow("review", open, ctx()), Flow.YOU, 0, "review");
        is(FlowClassifier.flow("review", open, ctx(thread("draft", "review", "review"), null, null, null, List.of())), Flow.YOU, 0, "open");
        Instant reviewed = NOW.minusSeconds(3 * 86_400);
        Flow waiting = FlowClassifier.flow("review", open, ctx(null, null, null, reviewed, List.of()));
        is(waiting, Flow.OTHERS, 1, null);
        assertEquals("Reviewed. Waiting on the author for 3 days.", waiting.say());
        is(FlowClassifier.flow("review", open, ctx(null, null, "New commits since your review", reviewed, List.of())), Flow.YOU, 0,
                "review");
        is(FlowClassifier.flow("review", pr2("someone", "SUCCESS", "CLEAN", "MERGEABLE", true, false, "APPROVED"),
                ctx(null, null, null, reviewed, List.of())), Flow.YOU, 3, "merge");
        is(FlowClassifier.flow("review", pr2("someone", "FAILURE", "UNSTABLE", "MERGEABLE", true, false, "APPROVED"),
                ctx(null, null, null, reviewed, List.of())), Flow.YOU, 2, "ci-why");
    }

    // ---- issues ----

    @Test
    void issueGoesInvestigateFixPr() {
        Item i = issue("quarkusio/quarkus", 32850, "someone", ME);
        is(FlowClassifier.flow("assigned", i, ctx()), Flow.YOU, 0, "investigate");
        is(FlowClassifier.flow("assigned", i, ctx(thread("working", "investigate", null), null, null, null, List.of())), Flow.AGENT, 0,
                "open");
        is(FlowClassifier.flow("assigned", i, ctx(thread("idle", "investigate", null), null, null, null, List.of())), Flow.YOU, 1, "fix");
        is(FlowClassifier.flow("assigned", i, ctx(thread("draft", "fix", "pr"), null, null, null, List.of())), Flow.YOU, 1, "open");
        Flow mine = FlowClassifier.flow("assigned", i, ctx(null, null, null, null, List.of(new GitHub.LinkedPr(
                "quarkusio/quarkus#57300", "Fix", "u", ME, false, true))));
        is(mine, Flow.OTHERS, 2, "go");
        assertEquals("quarkusio/quarkus#57300", mine.next().target());
        assertEquals("Open #57300", mine.next().label());
    }

    @Test
    void involvedItemsAreJustWatched() {
        is(FlowClassifier.flow("involved", issue("quarkusio/quarkus", 5, "someone"), ctx()), Flow.WATCHING, 0, null);
        is(FlowClassifier.flow("involved", issue("quarkusio/quarkus", 5, "someone"),
                ctx(thread("answered", "ask", null), null, null, null, List.of())), Flow.YOU, 0, "open");
    }
}
