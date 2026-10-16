package io.github.houseelves.desk.runner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.websockets.next.BasicWebSocketConnector;
import io.quarkus.websockets.next.WebSocketClientConnection;
import jakarta.inject.Inject;
import java.net.URI;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.junit.jupiter.api.Test;

/**
 * The socket keeps its default message limit (it takes connections before their hello), so a big message from the
 * runner comes as parts, which are only put together once the runner has said hello.
 */
@QuarkusTest
class RunnerSocketTest {

    @TestHTTPResource("/runner")
    URI uri;

    @Inject
    RunnerHub hub;

    @ConfigProperty(name = "elfdesk.runner.token")
    String token;

    final ObjectMapper mapper = new ObjectMapper();

    final LinkedBlockingQueue<String> received = new LinkedBlockingQueue<>();

    WebSocketClientConnection connect() {
        return BasicWebSocketConnector.create().baseUri(uri).onTextMessage((c, m) -> received.add(m)).connectAndAwait();
    }

    void send(WebSocketClientConnection c, ObjectNode msg) throws Exception {
        c.sendTextAndAwait(mapper.writeValueAsString(msg));
    }

    /** Splits a message the way the runner does: pieces of its JSON text. */
    void sendInParts(WebSocketClientConnection c, ObjectNode msg, int chars) throws Exception {
        String text = mapper.writeValueAsString(msg);
        int parts = (text.length() + chars - 1) / chars;
        for (int i = 0; i < parts; i++) {
            send(c, mapper.createObjectNode().put("type", "part").put("part", i).put("parts", parts)
                    .put("data", text.substring(i * chars, Math.min(text.length(), (i + 1) * chars))));
        }
    }

    static void waitClosed(WebSocketClientConnection c) throws InterruptedException {
        for (int i = 0; i < 100 && !c.isClosed(); i++) Thread.sleep(50);
    }

    @Test
    void aBigAnswerArrivesInParts() throws Exception {
        WebSocketClientConnection c = connect();
        try {
            send(c, mapper.createObjectNode().put("type", "hello").put("token", token).put("version", "0.29.2"));
            for (int i = 0; i < 100 && !hub.connected(); i++) Thread.sleep(50);
            CompletableFuture<JsonNode> answer = hub.request(mapper.createObjectNode().put("type", "github.write"), "0.0.0");
            String requestId = mapper.readTree(received.poll(10, TimeUnit.SECONDS)).path("id").asText();
            // Answer it with a message far over one message's limit.
            String patch = "line \"quoted\"\n".repeat(60_000); // about 900 KB, and more once escaped
            ObjectNode result = mapper.createObjectNode().put("type", "github.result").put("id", requestId).put("patch", patch);
            sendInParts(c, result, 32 * 1024);
            JsonNode got = answer.get(10, TimeUnit.SECONDS);
            assertEquals(patch, got.path("patch").asText());
            assertTrue(c.isOpen(), "the connection stays up");
        } finally {
            c.closeAndAwait();
        }
    }

    @Test
    void partsBeforeHelloCloseTheConnection() throws Exception {
        WebSocketClientConnection c = connect();
        send(c, mapper.createObjectNode().put("type", "part").put("part", 0).put("parts", 2).put("data", "{\"type\":"));
        waitClosed(c);
        assertTrue(c.isClosed());
    }

    @Test
    void oneMessageOverTheLimitClosesTheConnection() throws Exception {
        WebSocketClientConnection c = connect();
        send(c, mapper.createObjectNode().put("type", "hello").put("pad", "x".repeat(300 * 1024)));
        waitClosed(c);
        assertTrue(c.isClosed());
    }
}
