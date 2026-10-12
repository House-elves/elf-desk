package io.github.houseelves.desk.mvnpm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.concurrent.CompletableFuture;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import io.github.houseelves.desk.board.BoardService;
import io.github.houseelves.desk.runner.Live;
import io.github.houseelves.desk.runner.RunnerHub;

class MvnpmRunsTest {

    final ObjectMapper mapper = new ObjectMapper();
    MvnpmRuns runs;

    @BeforeEach
    void setUp() {
        runs = new MvnpmRuns();
        runs.hub = mock(RunnerHub.class);
        runs.live = mock(Live.class);
        runs.boards = mock(BoardService.class);
        when(runs.hub.message(any())).thenAnswer(i -> mapper.createObjectNode().put("type", (String) i.getArgument(0)));
    }

    @Test
    void startsAFullRoundOrOnePrThroughTheRunner() throws Exception {
        when(runs.hub.request(any(), eq(RunnerHub.MVNPM_SINCE)))
                .thenReturn(CompletableFuture.completedFuture(mapper.readTree("{\"status\": 200}")));
        runs.run(null, null);
        runs.run("smallrye/smallrye-open-api", 2696);
        ArgumentCaptor<ObjectNode> sent = ArgumentCaptor.forClass(ObjectNode.class);
        verify(runs.hub, times(2)).request(sent.capture(), eq(RunnerHub.MVNPM_SINCE));
        assertEquals(false, sent.getAllValues().get(0).has("repo"));
        assertEquals("smallrye/smallrye-open-api", sent.getAllValues().get(1).path("repo").asText());
        assertEquals(2696, sent.getAllValues().get(1).path("pr").asInt());

        assertThrows(IllegalArgumentException.class, () -> runs.run("smallrye/smallrye-open-api", null));
        assertThrows(IllegalArgumentException.class, () -> runs.run("not a repo; rm -rf", 1));
    }

    @Test
    void theRunnersRefusalIsShown() throws Exception {
        when(runs.hub.request(any(), any())).thenReturn(CompletableFuture.completedFuture(
                mapper.readTree("{\"status\": 0, \"error\": \"mvnpm-dependabot is already running; wait for it to finish\"}")));
        var e = assertThrows(IllegalStateException.class, () -> runs.run(null, null));
        assertEquals("mvnpm-dependabot is already running; wait for it to finish", e.getMessage());
    }

    @Test
    void whenTheLastCheckFinishesTheBoardRefreshes() {
        runs.onState(List.of("mvnpm-dependabot.service"));
        assertNotNull(runs.state().since());
        verify(runs.boards, never()).expire();

        runs.onState(List.of());
        assertNull(runs.state().since());
        verify(runs.boards).expire();
        verify(runs.live, times(2)).boardUpdated();
    }
}
