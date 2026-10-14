package io.github.houseelves.desk.status;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import io.github.houseelves.desk.runner.Live;
import io.github.houseelves.desk.runner.RunnerHub;

class StatusElfTest {

    final ObjectMapper mapper = new ObjectMapper();
    StatusElf elf;

    @TempDir
    Path dir;

    @BeforeEach
    void setUp() throws Exception {
        elf = new StatusElf();
        elf.dir = dir;
        elf.mapper = mapper;
        elf.hub = mock(RunnerHub.class);
        elf.live = mock(Live.class);
        when(elf.hub.message(any())).thenAnswer(i -> mapper.createObjectNode().put("type", (String) i.getArgument(0)));
        Files.writeString(dir.resolve("2026-10-12_08-00-31.json"), """
                {"id": "2026-10-12_08-00-31", "generatedAt": "2026-10-11T21:00:31Z", "weekStart": "October 05",
                 "weekEnd": "October 12, 2026", "subject": "Status - October 05 to October 12, 2026", "to": "me@work",
                 "body": "Hi,", "sentAt": "2026-10-11T22:00:00Z", "sentTo": "me@work", "sentBody": "Hi there,"}""");
        Files.writeString(dir.resolve("2026-10-19_08-00-12.json"), """
                {"id": "2026-10-19_08-00-12", "generatedAt": "2026-10-18T21:00:12Z", "weekStart": "October 12",
                 "weekEnd": "October 19, 2026", "subject": "Status - October 12 to October 19, 2026", "to": "me@work",
                 "teamTo": "team@work", "body": "Hi,\\n\\nStatus.",
                 "sends": [{"target": "me", "to": "me@work", "at": "2026-10-18T22:00:00Z", "body": "Hi,"}]}""");
        Files.writeString(dir.resolve("notes.txt"), "not a draft");
    }

    @Test
    void readsTheNewestDraftAndTheHistory() {
        StatusElf.Status s = elf.status(null);
        assertEquals("2026-10-19_08-00-12", s.id());
        assertNull(s.sentAt());
        assertEquals("me@work", s.to());
        assertEquals("Hi there,", elf.status("2026-10-12_08-00-31").sentBody());
        assertEquals(2, elf.runs().size());
        assertEquals("sent to you", elf.runs().get(0).status());
        assertEquals("sent to you", elf.runs().get(1).status()); // sent before "sends" existed
        assertEquals("team@work", s.teamTo());
        assertEquals(true, s.sentTo(StatusElf.ME));
        assertEquals(false, s.sentTo(StatusElf.TEAM));
        assertThrows(IllegalArgumentException.class, () -> elf.status("../../etc/passwd"));
    }

    @Test
    void sendGoesThroughTheRunnerWithYourEdits() throws Exception {
        when(elf.hub.request(any(), eq(StatusElf.SEND_SINCE)))
                .thenReturn(CompletableFuture.completedFuture(mapper.readTree("{\"status\": 200, \"body\": \"{}\"}")));
        elf.send("2026-10-19_08-00-12", "Hi team,", StatusElf.TEAM);
        ArgumentCaptor<ObjectNode> sent = ArgumentCaptor.forClass(ObjectNode.class);
        verify(elf.hub).request(sent.capture(), eq(StatusElf.SEND_SINCE));
        assertEquals("status.send", sent.getValue().path("type").asText());
        assertEquals("2026-10-19_08-00-12", sent.getValue().path("draft").asText());
        assertEquals("Hi team,", sent.getValue().path("body").asText());
        assertEquals("team", sent.getValue().path("target").asText());
    }

    @Test
    void aSentStatusOrAnEmptyOneIsNotSent() {
        assertThrows(IllegalStateException.class, () -> elf.send("2026-10-12_08-00-31", "again", StatusElf.ME));
        assertThrows(IllegalStateException.class, () -> elf.send("2026-10-19_08-00-12", "again", StatusElf.ME));
        assertThrows(IllegalStateException.class, () -> elf.send("2026-10-12_08-00-31", "no team list", StatusElf.TEAM));
        assertThrows(IllegalArgumentException.class, () -> elf.send("2026-10-19_08-00-12", "  ", StatusElf.TEAM));
        assertThrows(IllegalArgumentException.class, () -> elf.send("2026-10-19_08-00-12", "Hi", "everyone"));
        verify(elf.hub, never()).request(any(), any());
    }
}
