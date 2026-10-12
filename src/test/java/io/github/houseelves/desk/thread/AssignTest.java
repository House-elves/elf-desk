package io.github.houseelves.desk.thread;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.github.houseelves.desk.runner.GitHubWriter;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;

@QuarkusTest
class AssignTest {

    @Inject
    Poster poster;

    @InjectMock
    GitHubWriter writer;

    final ObjectMapper mapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        QuarkusTransaction.requiringNew().run(() -> GitHubAction.deleteAll());
    }

    @Test
    void assignsYouAndRecordsIt() throws Exception {
        when(writer.assign("quarkusio/quarkus", 501, "phillip-kruger")).thenReturn(mapper.readTree("""
                {"html_url": "https://github.com/quarkusio/quarkus/issues/501", "assignees": [{"login": "phillip-kruger"}]}"""));
        poster.assign("quarkusio/quarkus#501", "phillip-kruger");
        GitHubAction a = QuarkusTransaction.requiringNew().call(() -> GitHubAction.<GitHubAction>findAll().firstResult());
        assertEquals("assign", a.kind);
    }

    @Test
    void gitHubIgnoringTheAssigneeIsAnError() throws Exception {
        // No write access: GitHub answers 201 but leaves you out.
        when(writer.assign("someone/thing", 7, "phillip-kruger")).thenReturn(mapper.readTree("{\"assignees\": []}"));
        var e = assertThrows(IllegalStateException.class, () -> poster.assign("someone/thing#7", "phillip-kruger"));
        assertTrue(e.getMessage().contains("write access"));
        assertEquals(0L, QuarkusTransaction.requiringNew().call(() -> GitHubAction.count()));
    }
}
