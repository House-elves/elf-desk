package io.github.houseelves.desk.board;

import static io.github.houseelves.desk.Items.pr;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.after;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.github.houseelves.desk.github.GitHub;
import io.github.houseelves.desk.github.Item;
import io.github.houseelves.desk.ignore.IgnoreService;
import io.github.houseelves.desk.mvnpm.MvnpmElf;
import io.github.houseelves.desk.runner.Live;
import io.github.houseelves.desk.scope.Scope;
import io.github.houseelves.desk.scope.ScopeService;
import io.github.houseelves.desk.settings.Settings;

class BoardServiceTest {

    GitHub github = mock(GitHub.class);
    MvnpmElf mvnpm = mock(MvnpmElf.class);
    ScopeService scopes = mock(ScopeService.class);
    Settings settings = mock(Settings.class);
    Live live = mock(Live.class);
    IgnoreService ignores = mock(IgnoreService.class);
    AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-10-07T07:00:00Z"));
    Clock clock = new Clock() {
        public java.time.ZoneId getZone() { return ZoneOffset.UTC; }
        public Clock withZone(java.time.ZoneId z) { return this; }
        public Instant instant() { return now.get(); }
    };
    BoardService service;

    @BeforeEach
    void setUp() {
        when(scopes.scope()).thenReturn(new Scope(Set.of("quarkusio"), Set.of(), Set.of()));
        when(mvnpm.repos()).thenReturn(List.of());
        when(mvnpm.state()).thenReturn(new ObjectMapper().createObjectNode());
        when(settings.refreshMinutes()).thenReturn(5);
        when(github.search(anyString(), contains("review-requested"), anyInt()))
                .thenReturn(new GitHub.SearchResult(List.of(), 0));
        when(github.search(anyString(), contains("reviewed-by"), anyInt()))
                .thenReturn(new GitHub.SearchResult(List.of(), 0));
        when(ignores.keys()).thenReturn(Set.of());
        service = new BoardService(github, mvnpm, scopes, settings, live, ignores, clock);
    }

    void involvedReturns(Item... items) {
        when(github.search(anyString(), contains("involves:@me"), anyInt()))
                .thenReturn(new GitHub.SearchResult(List.of(items), items.length));
    }

    List<String> keys(Board b) {
        return b.lanes().stream().flatMap(l -> l.items().stream()).map(Item::key).toList();
    }

    @Test
    void firstLoadWaitsThenFreshCacheAnswersWithoutGitHub() {
        involvedReturns(pr("quarkusio/quarkus", 1, "x"));
        Board first = service.board("me", "t", false);
        assertEquals(List.of("quarkusio/quarkus#1"), keys(first));
        assertFalse(first.refreshing());

        now.set(now.get().plusSeconds(60));
        Board again = service.board("me", "t", false);
        assertFalse(again.refreshing());
        verify(github, times(1)).search(eq("t"), contains("involves:@me"), anyInt());
    }

    @Test
    void staleCacheAnswersAtOnceAndRefreshesInTheBackgroundOnce() throws Exception {
        involvedReturns(pr("quarkusio/quarkus", 1, "x"));
        service.board("me", "t", false);

        CountDownLatch release = new CountDownLatch(1);
        when(github.search(anyString(), contains("involves:@me"), anyInt())).thenAnswer(inv -> {
            release.await();
            return new GitHub.SearchResult(List.of(pr("quarkusio/quarkus", 2, "x")), 1);
        });
        now.set(now.get().plus(BoardService.CACHE_FOR).plusSeconds(1));

        Board stale = service.board("me", "t", false); // must not block on the slow search
        assertTrue(stale.refreshing());
        assertEquals(List.of("quarkusio/quarkus#1"), keys(stale));
        service.board("me", "t", false); // a second stale read doesn't start another fetch

        release.countDown();
        verify(live, timeout(5000).times(2)).boardUpdated(); // the first load, then the background refresh
        verify(github, after(200).times(2)).search(eq("t"), contains("involves:@me"), anyInt());
        assertEquals(List.of("quarkusio/quarkus#2"), keys(service.board("me", "t", false)));
    }

    @Test
    void markCiShowsARerunAsInProgressWithoutWaitingForGitHub() {
        involvedReturns(pr("quarkusio/quarkus", 1, "x").withCi("FAILURE"));
        service.board("me", "t", false);
        service.markCi("me", "quarkusio/quarkus#1", "PENDING");
        Item item = service.board("me", "t", false).lanes().stream().flatMap(l -> l.items().stream()).findFirst().orElseThrow();
        assertEquals("PENDING", item.ci());
        verify(live, timeout(1000).atLeast(2)).boardUpdated();
    }

    @Test
    void prsThatMovedOnSinceYourReviewAreFlagged() {
        Item moved = pr("quarkusio/quarkus", 1, "someone"); // head "sha1"
        Item talkedAbout = pr("quarkusio/quarkus", 2, "someone"); // head "sha2", updated 2026-10-05
        Item quiet = pr("quarkusio/quarkus", 3, "someone");
        Item mine = pr("quarkusio/quarkus", 4, "me");
        Instant before = Instant.parse("2026-10-04T00:00:00Z");
        Map<String, GitHub.PrDetails> d = Map.of(
                moved.key(), new GitHub.PrDetails("CLEAN", false, before, "old-sha"),
                talkedAbout.key(), new GitHub.PrDetails("CLEAN", false, before, "sha2"),
                quiet.key(), new GitHub.PrDetails("CLEAN", false, Instant.parse("2026-10-06T00:00:00Z"), "sha3"),
                mine.key(), new GitHub.PrDetails("CLEAN", false, before, "old"));
        Map<String, String> since = BoardService.sinceYourReview("me", d, List.of(moved, talkedAbout, quiet, mine));
        assertEquals(Map.of("quarkusio/quarkus#1", "New commits since your review",
                "quarkusio/quarkus#2", "Updated since your review"), since);
    }

    @Test
    void forgetDropsAnItemFromTheCache() {
        involvedReturns(pr("quarkusio/quarkus", 1, "x"), pr("quarkusio/quarkus", 2, "x"));
        service.board("me", "t", false);
        service.forget("me", "quarkusio/quarkus#1");
        assertEquals(List.of("quarkusio/quarkus#2"), keys(service.board("me", "t", false)));
    }
}
