package io.github.houseelves.desk.triage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.databind.ObjectMapper;

class TriageElfTest {

    @TempDir
    Path dir;

    TriageElf elf() {
        return new TriageElf(dir, new ObjectMapper());
    }

    void run(String id, String json) throws Exception {
        Files.writeString(dir.resolve(id + ".json"), json);
    }

    @Test
    void noRunsYet() {
        assertNull(elf().email(null));
        assertNull(elf().zulip(null));
        assertEquals(List.of(), new TriageElf(dir.resolve("missing"), new ObjectMapper()).runs(TriageElf.Kind.EMAIL));
    }

    @Test
    void newestEmailTriage() throws Exception {
        // The morning timer's run: emailed, from before the desk ran it (no "emailed").
        run("2026-10-12_08-00-10", """
                {"mode":"full","dryRun":false,"status":"success","triage":{"accounts":[]}}""");
        run("2026-10-13_08-03-24", """
                {"mode":"full","dryRun":false,"emailed":false,"status":"partial","durationMs":180000,
                 "triage":{"accounts":[{"email":"me@example.com","decisions":[{"subject":"Hi","decision":"KEEP_UNREAD"}]}]}}""");
        run("2026-10-13_10-12-31", """
                {"mode":"zulip","status":"success","zulipHours":24,"zulip":{"messages":[],"digests":{}}}""");
        // A briefing without triage has nothing for the Email tab.
        run("2026-10-13_11-00-00", """
                {"mode":"briefing-only","status":"success","triage":{"accounts":[]}}""");

        TriageElf.Email e = elf().email(null);
        assertEquals("2026-10-13_08-03-24", e.run().id());
        assertEquals(LocalDateTime.of(2026, 10, 13, 8, 3, 24), e.run().at());
        assertEquals("partial", e.run().status());
        assertFalse(e.emailed());
        assertEquals("Hi", e.accounts().get(0).path("decisions").get(0).path("subject").asText());

        assertTrue(elf().email("2026-10-12_08-00-10").emailed());

        assertEquals(List.of("2026-10-13_08-03-24", "2026-10-12_08-00-10"),
                elf().runs(TriageElf.Kind.EMAIL).stream().map(TriageElf.RunInfo::id).toList());
        assertEquals(List.of("2026-10-13_10-12-31"),
                elf().runs(TriageElf.Kind.ZULIP).stream().map(TriageElf.RunInfo::id).toList());
    }

    @Test
    void zulipRunAndKindsDontMix() throws Exception {
        run("2026-10-13_10-12-31", """
                {"mode":"zulip","status":"success","zulipHours":24,
                 "zulip":{"messages":[{"stream":"dev","topic":"Beta1","unread":true}],"digests":{"dev > Beta1":"Out today."}}}""");
        TriageElf.Zulip z = elf().zulip(null);
        assertEquals(24, z.hours());
        assertEquals("Out today.", z.digests().path("dev > Beta1").asText());
        assertNull(elf().email("2026-10-13_10-12-31"));
    }

    @Test
    void runIdsAreCheckedSoNoOtherFileCanBeRead() {
        assertThrows(IllegalArgumentException.class, () -> elf().email("../../.ssh/id_rsa"));
        assertThrows(IllegalArgumentException.class, () -> elf().zulip("config"));
    }
}
