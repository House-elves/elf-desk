package io.github.houseelves.desk.quarterly;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.github.houseelves.desk.runner.Live;
import io.github.houseelves.desk.runner.RunnerHub;
import io.github.houseelves.desk.triage.TriageRuns;

class QuarterlyElfTest {

    final ObjectMapper mapper = new ObjectMapper();
    QuarterlyElf elf;

    @TempDir
    Path dir;

    @BeforeEach
    void setUp() throws Exception {
        elf = new QuarterlyElf();
        elf.dir = dir;
        elf.mapper = mapper;
        Files.writeString(dir.resolve("2026-07-02_09-00-00.json"), """
                {"id": "2026-07-02_09-00-00", "generatedAt": "2026-07-01T23:00:00Z", "quarter": "2026-Q2",
                 "title": "Q2 2026", "start": "2026-04-01", "end": "2026-06-30", "until": "2026-06-30",
                 "prs": 40, "issues": 12, "weeklyStatuses": 0, "body": "# Q2 2026"}""");
        Files.writeString(dir.resolve("2026-10-15_18-28-06.json"), """
                {"id": "2026-10-15_18-28-06", "generatedAt": "2026-10-15T07:28:06Z", "quarter": "2026-Q3",
                 "title": "Q3 2026", "start": "2026-07-01", "end": "2026-09-30", "until": "2026-09-30",
                 "prs": 93, "issues": 69, "weeklyStatuses": 2, "body": "# Q3 2026\\n\\n## Quarkus\\n- Dev UI: modules"}""");
        Files.writeString(dir.resolve("notes.txt"), "not a review");
    }

    @Test
    void readsTheNewestReviewAndTheHistory() {
        QuarterlyElf.Review r = elf.review(null);
        assertEquals("2026-Q3", r.quarter());
        assertEquals("Q3 2026", r.title());
        assertEquals(93, r.prs());
        assertEquals(69, r.issues());
        assertEquals("# Q3 2026\n\n## Quarkus\n- Dev UI: modules", r.body());
        assertEquals(2, elf.runs().size());
        assertEquals("Q3 2026", elf.runs().get(0).status());
        assertEquals("Q2 2026", elf.review("2026-07-02_09-00-00").title());
        assertNull(elf.review("2020-01-01_00-00-00"));
        assertThrows(IllegalArgumentException.class, () -> elf.review("../../etc/passwd"));
    }

    @Test
    void onlyAQuarterGoesToTheRunner() {
        TriageRuns runs = new TriageRuns();
        RunnerHub hub = mock(RunnerHub.class);
        when(hub.message(any())).thenAnswer(i -> mapper.createObjectNode().put("type", (String) i.getArgument(0)));
        setField(runs, "hub", hub);
        setField(runs, "live", mock(Live.class));
        assertThrows(IllegalArgumentException.class, () -> runs.run(TriageRuns.QUARTERLY, "2026-Q5"));
        assertThrows(IllegalArgumentException.class, () -> runs.run(TriageRuns.QUARTERLY, "2026-Q3 --help"));
        assertThrows(IllegalArgumentException.class, () -> runs.run(TriageRuns.EMAIL, "2026-Q3"));
        verify(hub, never()).request(any(), any());
    }

    static void setField(Object target, String name, Object value) {
        try {
            var f = target.getClass().getDeclaredField(name);
            f.setAccessible(true);
            f.set(target, value);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }
}
