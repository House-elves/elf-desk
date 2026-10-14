package io.github.houseelves.desk.triage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.util.concurrent.CompletableFuture;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import io.github.houseelves.desk.runner.RunnerHub;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;

/** Done on a Zulip topic: the runner marks it read, and the run carries when. No real runner or Zulip. */
@QuarkusTest
class ZulipDoneServiceTest {

    static final String SERVER = "quarkusio.zulipchat.com";

    @Inject
    ZulipDoneService service;

    @Inject
    ObjectMapper mapper;

    @InjectMock
    RunnerHub hub;

    @InjectMock
    TriageElf triage;

    TriageElf.Zulip run;

    @BeforeEach
    void setUp() throws Exception {
        QuarkusTransaction.requiringNew().run(() -> ZulipDone.deleteAll());
        run = new TriageElf.Zulip(new TriageElf.RunInfo("2026-10-15_08-07-15", LocalDateTime.now(), "success"), 24,
                mapper.readTree("""
                        [{"server": "%1$s", "stream": "dev", "topic": "Dev UI", "url": "https://%1$s/#narrow/stream/dev/topic/Dev.UI/near/11", "time": 1},
                         {"server": "%1$s", "stream": "DM", "topic": "", "url": "https://%1$s/#narrow/stream/DM/topic//near/21", "time": 2},
                         {"server": "%1$s", "stream": "DM", "topic": "", "url": "https://%1$s/#narrow/stream/DM/topic//near/22", "time": 3}]
                        """.formatted(SERVER)), mapper.createObjectNode(), 1000);
        when(triage.zulip(null)).thenReturn(run);
        when(hub.message(any())).thenAnswer(i -> mapper.createObjectNode().put("type", (String) i.getArgument(0)));
        when(hub.request(any(), eq(ZulipDoneService.ZULIP_READ_SINCE)))
                .thenReturn(CompletableFuture.completedFuture(mapper.readTree("{\"status\": 200, \"body\": \"{\\\"marked\\\": 3}\"}")));
    }

    @Test
    void aStreamTopicIsMarkedReadByStreamAndTopic() {
        service.done(SERVER, "dev", "Dev UI");
        ArgumentCaptor<ObjectNode> sent = ArgumentCaptor.forClass(ObjectNode.class);
        verify(hub).request(sent.capture(), eq(ZulipDoneService.ZULIP_READ_SINCE));
        assertEquals("triage.zulipRead", sent.getValue().path("type").asText());
        assertEquals("dev", sent.getValue().path("stream").asText());
        assertEquals("Dev UI", sent.getValue().path("topic").asText());
        assertFalse(sent.getValue().has("messages"));
        var withDone = service.withDone(run);
        assertTrue(withDone.done().containsKey("dev > Dev UI"));
        assertFalse(withDone.done().containsKey("DM > "));
    }

    @Test
    void directMessagesAreMarkedReadByTheirIds() {
        service.done(SERVER, "DM", "");
        ArgumentCaptor<ObjectNode> sent = ArgumentCaptor.forClass(ObjectNode.class);
        verify(hub).request(sent.capture(), eq(ZulipDoneService.ZULIP_READ_SINCE));
        assertFalse(sent.getValue().has("stream"));
        assertEquals("[21,22]", sent.getValue().path("messages").toString());
    }

    @Test
    void onlyATopicTheLatestRunListed() {
        assertThrows(IllegalArgumentException.class, () -> service.done(SERVER, "dev", "Other"));
        assertThrows(IllegalArgumentException.class, () -> service.done("evil.example.com", "dev", "Dev UI"));
        verify(hub, never()).request(any(), any());
    }
}
