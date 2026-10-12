package io.github.houseelves.desk.runner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

class GitHubWriterTest {

    final ObjectMapper mapper = new ObjectMapper();

    /** A hub that records what it was asked to send and answers with a canned github.result. */
    GitHubWriter writer(ObjectNode[] sent, JsonNode answer, RuntimeException failure) {
        GitHubWriter w = new GitHubWriter();
        w.mapper = mapper;
        w.hub = new RunnerHub() {
            @Override
            public ObjectNode message(String type) {
                return GitHubWriterTest.this.mapper.createObjectNode().put("type", type);
            }

            @Override
            public CompletableFuture<JsonNode> request(ObjectNode msg, String minVersion) {
                sent[0] = msg;
                return failure != null ? CompletableFuture.failedFuture(failure) : CompletableFuture.completedFuture(answer);
            }
        };
        return w;
    }

    @Test
    void sendsTheExactRequestAndReadsGitHubsAnswer() throws Exception {
        ObjectNode[] sent = new ObjectNode[1];
        JsonNode ok = mapper.readTree("{\"status\": 200, \"body\": \"{\\\"html_url\\\": \\\"https://github.com/a/b/issues/7\\\"}\"}");
        JsonNode r = writer(sent, ok, null).close("a/b", 7);
        assertEquals("https://github.com/a/b/issues/7", r.path("html_url").asText());
        assertEquals("github.write", sent[0].path("type").asText());
        assertEquals("PATCH", sent[0].path("method").asText());
        assertEquals("/repos/a/b/issues/7", sent[0].path("path").asText());
        assertEquals("closed", sent[0].path("body").path("state").asText());

        writer(sent, ok, null).createReview("a/b", 7, Map.of("event", "COMMENT", "body", "x"));
        assertEquals("POST", sent[0].path("method").asText());
        assertEquals("/repos/a/b/pulls/7/reviews", sent[0].path("path").asText());
    }

    @Test
    void refusalsAndAnOfflineRunnerBecomeWriteFailed() throws Exception {
        JsonNode refused = mapper.readTree("{\"status\": 422, \"body\": \"{\\\"message\\\": \\\"Validation Failed\\\"}\"}");
        var e = assertThrows(GitHubWriter.WriteFailed.class,
                () -> writer(new ObjectNode[1], refused, null).createComment("a/b", 1, Map.of("body", "x")));
        assertEquals(422, e.status);
        assertTrue(e.body.contains("Validation Failed"));

        var offline = assertThrows(GitHubWriter.WriteFailed.class, () -> writer(new ObjectNode[1], null,
                new IllegalStateException("The runner is offline, so nothing was sent to GitHub.")).close("a/b", 1));
        assertTrue(offline.getMessage().startsWith("The runner is offline"));
    }
}
