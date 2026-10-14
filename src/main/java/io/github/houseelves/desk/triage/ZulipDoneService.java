package io.github.houseelves.desk.triage;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import io.github.houseelves.desk.runner.Live;
import io.github.houseelves.desk.runner.RunnerHub;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * Done on the Zulip tab: the runner has daily-triage (which holds the Zulip API key) mark the topic read, and the
 * desk remembers it, so the tab folds the topic away until it has newer messages.
 */
@ApplicationScoped
public class ZulipDoneService {

    static final String ZULIP_READ_SINCE = "0.23.0";
    /** How daily-triage names direct messages: all of them are one "topic" on the tab. */
    static final String DM = "DM";

    @Inject
    RunnerHub hub;

    @Inject
    TriageElf triage;

    @Inject
    ObjectMapper mapper;

    @Inject
    Live live;

    /**
     * Marks the topic read on Zulip: a stream topic's unread messages (newer ones included), or the direct messages
     * the newest run listed. Only a topic the newest run listed.
     */
    public void done(String server, String stream, String topic) {
        TriageElf.Zulip latest = triage.zulip(null);
        List<Long> ids = new ArrayList<>();
        boolean listed = false;
        if (latest != null && server != null && stream != null && topic != null) {
            for (JsonNode m : latest.messages()) {
                if (!m.path("server").asText().equals(server) || !m.path("stream").asText().equals(stream)
                        || !m.path("topic").asText().equals(topic)) continue;
                listed = true;
                long id = messageId(m.path("url").asText());
                if (id > 0) ids.add(id);
            }
        }
        if (!listed) throw new IllegalArgumentException("That topic isn't in the latest Zulip run");
        ObjectNode msg = hub.message("triage.zulipRead").put("server", server);
        if (stream.equals(DM)) {
            ArrayNode messages = msg.putArray("messages");
            ids.forEach(messages::add);
        } else {
            msg.put("stream", stream).put("topic", topic);
        }
        JsonNode result;
        try {
            result = hub.request(msg, ZULIP_READ_SINCE).get(150, TimeUnit.SECONDS);
        } catch (Exception e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            throw new IllegalStateException(cause.getMessage());
        }
        if (result.path("status").asInt() != 200) {
            throw new IllegalStateException("Zulip wasn't changed: " + result.path("error").asText("the runner couldn't mark it read"));
        }
        int marked;
        try {
            marked = mapper.readTree(result.path("body").asText("{}")).path("marked").asInt();
        } catch (Exception e) {
            marked = 0;
        }
        int n = marked;
        QuarkusTransaction.requiringNew().run(() -> {
            ZulipDone d = ZulipDone.findById(new ZulipDone.Key(server, stream, topic));
            if (d == null) {
                d = new ZulipDone();
                d.server = server;
                d.stream = stream;
                d.topic = topic;
            }
            d.marked = n;
            d.done = java.time.Instant.now();
            d.persist();
        });
        live.triage(TriageRuns.ZULIP);
    }

    /** The id at the end of a message's link (…/near/631450774), or -1. */
    static long messageId(String url) {
        int at = url.lastIndexOf("/near/");
        if (at < 0) return -1;
        try {
            return Long.parseLong(url.substring(at + "/near/".length()));
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /** The run with when you marked each of its topics Done ("stream > topic" to the time), in {@code done}. */
    public TriageElf.Zulip withDone(TriageElf.Zulip run) {
        if (run == null) return null;
        Map<String, String> done = new HashMap<>();
        QuarkusTransaction.requiringNew().run(() -> ZulipDone.<ZulipDone>listAll()
                .forEach(d -> done.put(d.server + "\n" + d.stream + " > " + d.topic, d.done.toString())));
        Map<String, String> out = new HashMap<>();
        for (JsonNode m : run.messages()) {
            String key = m.path("stream").asText() + " > " + m.path("topic").asText();
            String at = done.get(m.path("server").asText() + "\n" + key);
            if (at != null) out.put(key, at);
        }
        return new TriageElf.Zulip(run.run(), run.hours(), run.messages(), run.digests(), run.durationMs(), Map.copyOf(out));
    }
}
