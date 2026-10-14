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

/** Done on an email: the runner marks it read, and the triage shows it as done. No real runner or Gmail. */
@QuarkusTest
class EmailDoneServiceTest {

    static final String ME = "phillip.kruger@gmail.com", ID = "<abc@mail.example>";

    @Inject
    EmailDoneService service;

    @Inject
    ObjectMapper mapper;

    @InjectMock
    RunnerHub hub;

    @InjectMock
    TriageElf triage;

    TriageElf.Email run;

    @BeforeEach
    void setUp() throws Exception {
        QuarkusTransaction.requiringNew().run(() -> EmailDone.deleteAll());
        run = new TriageElf.Email(new TriageElf.RunInfo("2026-10-15_09-09-44", LocalDateTime.now(), "success"),
                mapper.readTree("""
                        [{"email": "%s", "decisions": [
                          {"subject": "Talk?", "decision": "KEEP_UNREAD", "messageId": "%s"},
                          {"subject": "Noise", "decision": "MARK_READ", "messageId": "<n@x>"}]}]""".formatted(ME, ID)),
                false, 1000);
        when(triage.email(null)).thenReturn(run);
        when(hub.message(any())).thenAnswer(i -> mapper.createObjectNode().put("type", (String) i.getArgument(0)));
    }

    @Test
    void doneMarksTheThreadReadThroughTheRunnerAndShowsIt() throws Exception {
        when(hub.request(any(), eq(EmailDoneService.MARK_READ_SINCE)))
                .thenReturn(CompletableFuture.completedFuture(mapper.readTree("{\"status\": 200, \"body\": \"{\\\"marked\\\": 2}\"}")));
        assertFalse(service.withDone(run).accounts().get(0).path("decisions").get(0).has("done"));

        service.done(ME, ID);

        ArgumentCaptor<ObjectNode> sent = ArgumentCaptor.forClass(ObjectNode.class);
        verify(hub).request(sent.capture(), eq(EmailDoneService.MARK_READ_SINCE));
        assertEquals("triage.markRead", sent.getValue().path("type").asText());
        assertEquals(ME, sent.getValue().path("account").asText());
        assertEquals(ID, sent.getValue().path("messageId").asText());
        var decisions = service.withDone(run).accounts().get(0).path("decisions");
        assertTrue(decisions.get(0).path("done").asBoolean());
        assertFalse(decisions.get(1).has("done"));
        assertEquals(2, QuarkusTransaction.requiringNew().call(() -> EmailDone.<EmailDone>findAll().firstResult().marked));
    }

    @Test
    void onlyAnEmailTheLatestTriageListed() {
        assertThrows(IllegalArgumentException.class, () -> service.done(ME, "<other@x>"));
        assertThrows(IllegalArgumentException.class, () -> service.done("someone@else.com", ID));
        verify(hub, never()).request(any(), any());
    }

    @Test
    void aRunnerFailureLeavesNothingDone() throws Exception {
        when(hub.request(any(), eq(EmailDoneService.MARK_READ_SINCE)))
                .thenReturn(CompletableFuture.completedFuture(mapper.readTree("{\"status\": 0, \"error\": \"IMAP down\"}")));
        var e = assertThrows(IllegalStateException.class, () -> service.done(ME, ID));
        assertTrue(e.getMessage().contains("IMAP down"));
        assertEquals(0, QuarkusTransaction.requiringNew().call(() -> EmailDone.count()));
    }
}
