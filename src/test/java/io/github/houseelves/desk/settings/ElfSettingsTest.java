package io.github.houseelves.desk.settings;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import io.github.houseelves.desk.runner.RunnerHub;

class ElfSettingsTest {

    final ObjectMapper mapper = new ObjectMapper();
    ElfSettings settings;

    @BeforeEach
    void setUp() {
        settings = new ElfSettings();
        settings.mapper = mapper;
        settings.hub = mock(RunnerHub.class);
        when(settings.hub.message(any())).thenAnswer(i -> mapper.createObjectNode().put("type", (String) i.getArgument(0)));
    }

    @Test
    void onlyKnownElvesAndTheRunnersAnswerComesBack() throws Exception {
        assertThrows(IllegalArgumentException.class, () -> settings.get("../../.ssh"));
        verify(settings.hub, never()).request(any(), any());

        when(settings.hub.request(any(), eq(RunnerHub.ELF_CONFIG_SINCE))).thenReturn(CompletableFuture.completedFuture(
                mapper.readTree("{\"status\": 422, \"body\": \"{\\\"wrong\\\": {\\\"MAX_EMAILS\\\": \\\"A number from 1 to 500\\\"}}\"}")));
        var r = settings.set("daily-triage", Map.of("MAX_EMAILS", "9000"));
        assertEquals("A number from 1 to 500", r.path("wrong").path("MAX_EMAILS").asText());
        ArgumentCaptor<ObjectNode> sent = ArgumentCaptor.forClass(ObjectNode.class);
        verify(settings.hub).request(sent.capture(), eq(RunnerHub.ELF_CONFIG_SINCE));
        assertEquals("elf.configSet", sent.getValue().path("type").asText());
        assertEquals("9000", sent.getValue().path("values").path("MAX_EMAILS").asText());

        when(settings.hub.request(any(), eq(RunnerHub.ELF_CONFIG_SINCE))).thenReturn(CompletableFuture.completedFuture(
                mapper.readTree("{\"status\": 0, \"error\": \"weekly-status isn't set up on this machine\"}")));
        assertThrows(IllegalStateException.class, () -> settings.get("weekly-status"));
    }
}
