package io.github.houseelves.desk.runner;

import io.quarkus.websockets.next.OnClose;
import io.quarkus.websockets.next.OnTextMessage;
import io.quarkus.websockets.next.WebSocket;
import io.quarkus.websockets.next.WebSocketConnection;
import jakarta.inject.Inject;

/** Where the runner connects. Authenticated by its hello token, not by the browser sign-in. */
@WebSocket(path = "/runner")
public class RunnerSocket {

    @Inject
    RunnerHub hub;

    @OnTextMessage
    void message(String text, WebSocketConnection connection) {
        hub.onMessage(connection, text);
    }

    @OnClose
    void close(WebSocketConnection connection) {
        hub.onClose(connection);
    }
}
