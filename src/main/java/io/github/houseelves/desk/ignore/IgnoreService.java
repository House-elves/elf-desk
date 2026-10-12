package io.github.houseelves.desk.ignore;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import io.github.houseelves.desk.thread.DeskThread;
import io.github.houseelves.desk.thread.Turn;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;

/**
 * The items you chose to ignore. An ignored item comes back to its lane when, after you ignored it, someone
 * requests your review or mentions you ({@link #bringBack}); the board shows why for a day.
 */
@ApplicationScoped
public class IgnoreService {

    /** How long the board shows why an item came back. */
    static final Duration SHOW_RETURN_FOR = Duration.ofDays(1);

    @Transactional
    public Set<String> keys() {
        return IgnoredItem.active().stream().map(i -> i.itemKey).collect(Collectors.toUnmodifiableSet());
    }

    /** Still-ignored items and when you ignored them: what to check for new activity. */
    @Transactional
    public Map<String, Instant> ignoredSince() {
        return IgnoredItem.active().stream().collect(Collectors.toUnmodifiableMap(i -> i.itemKey, i -> i.ignored));
    }

    /** Items that came back in the last day, with why ("Back: you were mentioned"). */
    @Transactional
    public Map<String, String> recentlyReturned() {
        return IgnoredItem.returnedSince(Instant.now().minus(SHOW_RETURN_FOR)).stream()
                .collect(Collectors.toUnmodifiableMap(i -> i.itemKey, i -> i.returnedReason));
    }

    public boolean isIgnored(String itemKey) {
        return keys().contains(itemKey);
    }

    @Transactional
    public void ignore(String itemKey, String login) {
        IgnoredItem i = IgnoredItem.findById(itemKey);
        boolean fresh = i == null;
        if (fresh) {
            i = new IgnoredItem();
            i.itemKey = itemKey;
        }
        // Ignoring again (also after it came back) starts the clock again.
        i.login = login;
        i.ignored = Instant.now();
        i.returnedAt = null;
        i.returnedReason = null;
        if (fresh) i.persist();
    }

    @Transactional
    public void unignore(String itemKey) {
        IgnoredItem.deleteById(itemKey);
    }

    /** New activity aimed at you: back to its lane, with the reason noted. */
    @Transactional
    public void bringBack(String itemKey, String reason) {
        IgnoredItem i = IgnoredItem.findById(itemKey);
        if (i == null || i.returnedAt != null) return;
        i.returnedAt = Instant.now();
        i.returnedReason = reason;
        DeskThread t = DeskThread.byKey(itemKey);
        if (t != null) Turn.add(t.id, Turn.SYSTEM, null, "No longer ignored: " + reason.toLowerCase() + ".");
    }
}
