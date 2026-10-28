package io.github.houseelves.desk.api;

import static io.github.houseelves.desk.Items.issue;
import static io.github.houseelves.desk.Items.mvnpmBump;
import static io.github.houseelves.desk.Items.pr;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.github.houseelves.desk.board.Board;
import io.github.houseelves.desk.board.BoardClassifier;
import io.github.houseelves.desk.board.BoardService;
import io.github.houseelves.desk.github.GitHub;
import io.github.houseelves.desk.github.Item;
import io.github.houseelves.desk.scope.WatchScope;
import io.github.houseelves.desk.security.CurrentUser;
import io.quarkus.security.ForbiddenException;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

@QuarkusTest
class DeskApiTest {

    @Inject
    DeskApi api;

    @InjectMock
    GitHub github;

    @InjectMock
    CurrentUser user;

    @BeforeEach
    @Transactional
    void setUp() {
        WatchScope.deleteAll();
        io.github.houseelves.desk.ignore.IgnoredItem.deleteAll();
        when(user.login()).thenReturn("phillip-kruger");
        when(user.githubToken()).thenReturn("gho_test");
        when(github.search(eq("gho_test"), contains("involves:@me"), anyInt())).thenReturn(new GitHub.SearchResult(
                List.of(pr("quarkusio/quarkus", 2, "phillip-kruger"), issue("quarkusio/quarkus", 3, "x", "phillip-kruger"),
                        issue("phillip-kruger/qwaak", 4, "phillip-kruger")), 3));
        when(github.search(eq("gho_test"), contains("review-requested:@me"), anyInt())).thenReturn(
                new GitHub.SearchResult(List.of(pr("smallrye/smallrye-open-api", 1, "someone")), 1));
        when(github.search(eq("gho_test"), contains("reviewed-by:@me"), anyInt())).thenReturn(
                new GitHub.SearchResult(List.of(), 0));
        // The mvnpm search names the elf's repos from the test config.
        when(github.search(eq("gho_test"),
                eq("is:open is:pr author:app/dependabot sort:created-desc repo:quarkusio/quarkus repo:smallrye/smallrye-open-api"),
                anyInt())).thenReturn(new GitHub.SearchResult(List.of(mvnpmBump("smallrye/smallrye-open-api", 2696, "abc")), 1));
        when(github.viewerOrgs("gho_test")).thenReturn(List.of("quarkusio", "smallrye", "eclipse"));
    }

    static List<String> lane(Board b, String id) {
        return b.lanes().stream().filter(l -> l.id().equals(id)).findFirst().orElseThrow()
                .items().stream().map(Item::key).toList();
    }

    @Test
    @TestSecurity(user = "phillip-kruger", roles = "owner")
    void firstRunSuggestsOrgsThenTheBoardFollowsTheScope() {
        Board before = api.board(true);
        assertTrue(before.scopeEmpty());
        assertEquals(4, before.hidden().size());
        assertEquals("SAFE", before.mvnpm().rows().get(0).check().verdict()); // from the test state.json

        List<BoardService.Suggestion> sug = api.suggestions();
        assertEquals("quarkusio", sug.get(0).owner());
        assertTrue(sug.get(0).preselect()); // member with items
        assertTrue(sug.stream().filter(s -> s.owner().equals("phillip-kruger")).allMatch(s -> !s.preselect()));
        assertTrue(sug.stream().filter(s -> s.owner().equals("eclipse")).allMatch(s -> s.items() == 0 && !s.preselect()));

        api.watchAll(List.of("quarkusio", "smallrye"));
        Board after = api.board(false);
        assertFalse(after.scopeEmpty());
        assertEquals(List.of("smallrye/smallrye-open-api#1"), lane(after, BoardClassifier.REVIEW));
        assertEquals(List.of("quarkusio/quarkus#2"), lane(after, BoardClassifier.MINE));
        assertEquals(List.of("quarkusio/quarkus#3"), lane(after, BoardClassifier.ASSIGNED));
        assertEquals(List.of("phillip-kruger/qwaak#4"), after.hidden().stream().map(Item::key).toList());
    }

    @Test
    @TestSecurity(user = "phillip-kruger", roles = "owner")
    void ignoreMovesAnItemAndStopIgnoringBringsItBack() {
        api.watchAll(List.of("quarkusio", "smallrye"));
        api.ignore("quarkusio/quarkus#2");
        Board b = api.board(true);
        assertTrue(api.isIgnored("quarkusio/quarkus#2"));
        assertEquals(List.of(), lane(b, BoardClassifier.MINE));
        assertEquals(List.of("quarkusio/quarkus#2"), b.ignored().stream().map(Item::key).toList());

        api.unignore("quarkusio/quarkus#2");
        assertEquals(List.of("quarkusio/quarkus#2"), lane(api.board(false), BoardClassifier.MINE));
    }

    @Test
    @TestSecurity(user = "phillip-kruger", roles = "owner")
    void settingsRoundTrip() {
        var s = api.watch("quarkusio");
        s = api.exclude("quarkusio/quarkus-quickstarts");
        assertEquals(2, s.scope().size());
        assertTrue(s.mvnpmInstalled());
        assertEquals(List.of("quarkusio/quarkus", "smallrye/smallrye-open-api"), s.mvnpmRepos());

        s = api.setRefreshMinutes(10);
        assertEquals(10, s.refreshMinutes());
        assertThrows(IllegalArgumentException.class, () -> api.setRefreshMinutes(0));
    }

    @Test
    @TestSecurity(user = "phillip-kruger", roles = "owner")
    void tabsKeepTheOrderYouDraggedThemTo() {
        api.setTabOrder(List.of());
        assertEquals(List.of(), api.tabOrder());
        assertEquals(List.of("mail-triage", "calendar", "github"), api.setTabOrder(List.of("mail-triage", "calendar", "github", "calendar")));
        assertEquals(List.of("mail-triage", "calendar", "github"), api.tabOrder());
        assertThrows(IllegalArgumentException.class, () -> api.setTabOrder(List.of("mail-triage", "../etc")));
        assertThrows(IllegalArgumentException.class, () -> api.setTabOrder(List.of("Mail\nTriage")));
        assertEquals(List.of("mail-triage", "calendar", "github"), api.tabOrder());
        api.setTabOrder(List.of());
        assertEquals(List.of(), api.tabOrder());
    }

    @Test
    @TestSecurity(user = "phillipkruger", roles = "user")
    void otherAccountsAreRefused() {
        assertThrows(ForbiddenException.class, () -> api.board(false));
        assertThrows(ForbiddenException.class, () -> api.watch("quarkusio"));
        assertThrows(ForbiddenException.class, () -> api.setTabOrder(List.of("github")));
    }
}
