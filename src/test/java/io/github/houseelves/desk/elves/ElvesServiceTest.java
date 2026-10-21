package io.github.houseelves.desk.elves;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.concurrent.CompletableFuture;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import io.github.houseelves.desk.github.GitHub;
import io.github.houseelves.desk.runner.RunnerHub;

class ElvesServiceTest {

    static final String COMMIT = "0123456789abcdef0123456789abcdef01234567";
    static final String YAML = """
            name: weekly-status
            description: Drafts your weekly status.
            main: WeeklyStatus.java
            config: ~/.config/weekly-status/config
            tools: [gh, claude]
            reads: [Your GitHub activity.]
            writes:
              - An email, only when you click Send.
            settings:
              - {key: DISPLAY_NAME, label: Your name, kind: text}
              - {key: GMAIL_APP_PASSWORD, label: Gmail app password, kind: secret}
              - {key: SCHEDULE, label: Draft it on, kind: calendar, default: "Mon *-*-* 08:00:00"}
            commands:
              draft: {label: Draft it, args: [--save]}
            schedule: {command: draft, setting: SCHEDULE}
            """;

    final ObjectMapper mapper = new ObjectMapper();
    ElvesService elves;

    @BeforeEach
    void setUp() {
        elves = new ElvesService();
        elves.mapper = mapper;
        elves.hub = mock(RunnerHub.class);
        elves.github = mock(GitHub.class);
        when(elves.hub.message(any())).thenAnswer(i -> mapper.createObjectNode().put("type", (String) i.getArgument(0)));
    }

    @Test
    void theCatalogSaysWhatEachElfDoesAndWhichAreInstalled() throws Exception {
        when(elves.github.elfRepos("token", "me")).thenReturn(List.of(
                new GitHub.ElfRepo("House-elves/weekly-status", "", false, "https://github.com/House-elves/weekly-status", COMMIT, YAML),
                new GitHub.ElfRepo("me/expenses", "Mine", true, "https://github.com/me/expenses", COMMIT, null)));
        when(elves.hub.connected()).thenReturn(true);
        when(elves.hub.request(any(), eq(RunnerHub.ELVES_SINCE))).thenReturn(CompletableFuture.completedFuture(mapper.readTree(
                "{\"status\": 200, \"body\": \"{\\\"elves\\\": [{\\\"name\\\": \\\"weekly-status\\\", \\\"repo\\\": \\\"House-elves/weekly-status\\\", \\\"commit\\\": \\\"abc\\\"}]}\"}")));

        var list = elves.catalog("me", "token").path("elves");
        var ws = list.get(0);
        assertTrue(ws.path("official").asBoolean());
        assertEquals("weekly-status", ws.path("manifest").path("name").asText());
        assertEquals("Mon *-*-* 08:00:00", ws.path("manifest").path("schedule").asText());
        assertEquals("An email, only when you click Send.", ws.path("manifest").path("writes").get(0).asText());
        assertTrue(ws.path("manifest").path("settings").get(1).path("secret").asBoolean());
        assertEquals("abc", ws.path("installed").path("commit").asText());

        var mine = list.get(1);
        assertFalse(mine.path("official").asBoolean());
        assertTrue(mine.path("private").asBoolean());
        assertEquals("It has no elf.yaml", mine.path("problem").asText());
    }

    @Test
    void onlyAnElfFromTheCatalogInstalls() throws Exception {
        when(elves.github.elfRepos("token", "me")).thenReturn(List.of(
                new GitHub.ElfRepo("House-elves/weekly-status", "", false, "", COMMIT, YAML)));
        assertThrows(IllegalArgumentException.class, () -> elves.install("me", "token", "someone/evil", COMMIT));
        assertThrows(IllegalArgumentException.class, () -> elves.install("me", "token", "House-elves/weekly-status", "main"));
        verify(elves.hub, never()).request(any(), any());

        when(elves.hub.request(any(), eq(RunnerHub.ELVES_SINCE))).thenReturn(CompletableFuture.completedFuture(
                mapper.readTree("{\"status\": 200, \"body\": \"{\\\"elves\\\": []}\"}")));
        elves.install("me", "token", "House-elves/weekly-status", COMMIT);
        ArgumentCaptor<ObjectNode> sent = ArgumentCaptor.forClass(ObjectNode.class);
        verify(elves.hub).request(sent.capture(), eq(RunnerHub.ELVES_SINCE));
        assertEquals("elf.install", sent.getValue().path("type").asText());
        assertEquals(COMMIT, sent.getValue().path("commit").asText());
    }

    @Test
    void namesAndCommitsAreCheckedAndTheRunnersRefusalComesBack() throws Exception {
        assertThrows(IllegalArgumentException.class, () -> elves.run("../x", "draft"));
        assertThrows(IllegalArgumentException.class, () -> elves.run("weekly-status", "--send=1"));
        assertThrows(IllegalArgumentException.class, () -> elves.update("weekly-status", "HEAD"));
        verify(elves.hub, never()).request(any(), any());

        when(elves.hub.request(any(), eq(RunnerHub.ELVES_SINCE))).thenReturn(CompletableFuture.completedFuture(
                mapper.readTree("{\"status\": 0, \"error\": \"weekly-status isn't installed\"}")));
        var e = assertThrows(IllegalStateException.class, () -> elves.run("weekly-status", "draft"));
        assertEquals("weekly-status isn't installed", e.getMessage());
    }
}
