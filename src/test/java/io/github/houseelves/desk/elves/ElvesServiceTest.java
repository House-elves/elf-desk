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
            tabs:
              - {name: status, label: Status, page: ui/status.js, card: true}
            actions:
              send: {label: Send it, args: [--action=send], confirm: "Send the status to {target}?"}
              sent-yourself: {label: Mark it sent, args: [--action=sent-yourself], ask: false}
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
        assertEquals("Status", ws.path("manifest").path("tabs").get(0).asText());
        assertEquals("Send it", ws.path("manifest").path("askingActions").get(0).asText());
        assertEquals("Mark it sent", ws.path("manifest").path("quietActions").get(0).asText());

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
        assertThrows(IllegalArgumentException.class, () -> elves.run("../x", "draft", null));
        assertThrows(IllegalArgumentException.class, () -> elves.run("weekly-status", "--send=1", null));
        assertThrows(IllegalArgumentException.class, () -> elves.update("weekly-status", "HEAD"));
        verify(elves.hub, never()).request(any(), any());

        when(elves.hub.request(any(), eq(RunnerHub.ELVES_SINCE))).thenReturn(CompletableFuture.completedFuture(
                mapper.readTree("{\"status\": 0, \"error\": \"weekly-status isn't installed\"}")));
        var e = assertThrows(IllegalStateException.class, () -> elves.run("weekly-status", "draft", null));
        assertEquals("weekly-status isn't installed", e.getMessage());
    }

    @Test
    void aTabsReadsAndActionsGoToTheRunnerForThatElfOnly() throws Exception {
        assertThrows(IllegalArgumentException.class, () -> elves.read("../x", "runs", List.of("a.json"), null));
        assertThrows(IllegalArgumentException.class, () -> elves.data("weekly-status", "../config"));
        assertThrows(IllegalArgumentException.class, () -> elves.read("weekly-status", "runs", List.of("../config"), null));
        assertThrows(IllegalArgumentException.class, () -> elves.action("weekly-status", "Send!", null));
        verify(elves.hub, never()).request(any(), any());

        when(elves.hub.request(any(), eq(RunnerHub.ELF_TABS_SINCE))).thenReturn(CompletableFuture.completedFuture(
                mapper.readTree("{\"status\": 200, \"body\": \"{\\\"result\\\": {\\\"sent\\\": true}}\"}")));
        var r = elves.action("weekly-status", "send", mapper.readTree("{\"target\": \"team\"}"));
        assertTrue(r.path("result").path("sent").asBoolean());
        elves.read("weekly-status", "runs", List.of("2026-10-19_10-52-40.json"), List.of("subject"));
        ArgumentCaptor<ObjectNode> sent = ArgumentCaptor.forClass(ObjectNode.class);
        verify(elves.hub, org.mockito.Mockito.times(2)).request(sent.capture(), eq(RunnerHub.ELF_TABS_SINCE));
        assertEquals("elf.action", sent.getAllValues().get(0).path("type").asText());
        assertEquals("team", sent.getAllValues().get(0).path("params").path("target").asText());
        assertEquals("elf.read", sent.getAllValues().get(1).path("type").asText());
        assertEquals("subject", sent.getAllValues().get(1).path("fields").get(0).asText());
    }
}
