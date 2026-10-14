package io.github.houseelves.desk.triage;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import io.github.houseelves.desk.runner.Live;
import io.github.houseelves.desk.runner.RunnerHub;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * Done on the Email tab: the runner has daily-triage (which holds the Gmail credentials) mark the email's thread
 * read, and the desk remembers it, so the triage lists it as done.
 */
@ApplicationScoped
public class EmailDoneService {

    static final String MARK_READ_SINCE = "0.22.0";

    @Inject
    RunnerHub hub;

    @Inject
    TriageElf triage;

    @Inject
    ObjectMapper mapper;

    @Inject
    Live live;

    /** Marks the email's thread read in Gmail. Only an email the newest triage listed for that account. */
    public void done(String account, String messageId) {
        if (!listed(account, messageId)) throw new IllegalArgumentException("That email isn't in the latest triage");
        JsonNode result;
        try {
            result = hub.request(hub.message("triage.markRead").put("account", account).put("messageId", messageId),
                    MARK_READ_SINCE).get(150, TimeUnit.SECONDS);
        } catch (Exception e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            throw new IllegalStateException(cause.getMessage());
        }
        if (result.path("status").asInt() != 200) {
            throw new IllegalStateException("Gmail wasn't changed: " + result.path("error").asText("the runner couldn't mark it read"));
        }
        int marked;
        try {
            marked = mapper.readTree(result.path("body").asText("{}")).path("marked").asInt();
        } catch (Exception e) {
            marked = 0;
        }
        int n = marked;
        QuarkusTransaction.requiringNew().run(() -> {
            EmailDone d = EmailDone.findById(new EmailDone.Key(account, messageId));
            if (d == null) {
                d = new EmailDone();
                d.account = account;
                d.messageId = messageId;
            }
            d.marked = n;
            d.done = java.time.Instant.now();
            d.persist();
        });
        live.triage(TriageRuns.EMAIL);
    }

    private boolean listed(String account, String messageId) {
        TriageElf.Email latest = triage.email(null);
        if (latest == null || account == null || messageId == null || messageId.isBlank()) return false;
        for (JsonNode a : latest.accounts()) {
            if (!a.path("email").asText().equals(account)) continue;
            for (JsonNode d : a.path("decisions")) {
                if (d.path("messageId").asText().equals(messageId)) return true;
            }
        }
        return false;
    }

    /** The run with "done" (and "doneAt") on each decision you marked Done. */
    public TriageElf.Email withDone(TriageElf.Email run) {
        if (run == null) return null;
        Map<String, EmailDone> done = new HashMap<>();
        QuarkusTransaction.requiringNew().run(() -> EmailDone.<EmailDone>listAll()
                .forEach(d -> done.put(d.account + "\n" + d.messageId, d)));
        JsonNode accounts = run.accounts().deepCopy();
        for (JsonNode a : accounts) {
            for (JsonNode d : a.path("decisions")) {
                EmailDone hit = done.get(a.path("email").asText() + "\n" + d.path("messageId").asText());
                if (hit != null) ((ObjectNode) d).put("done", true).put("doneAt", hit.done.toString());
            }
        }
        return new TriageElf.Email(run.run(), accounts, run.emailed(), run.durationMs());
    }
}
