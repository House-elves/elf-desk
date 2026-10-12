package io.github.houseelves.desk.ignore;

import java.time.Instant;
import java.util.List;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * An issue or PR you've chosen to ignore: someone else is handling it. It comes back (returned_at set) when
 * someone requests your review or mentions you after {@code ignored}. See V5 and V6.
 */
@Entity
@Table(name = "ignored_item")
public class IgnoredItem extends PanacheEntityBase {

    @Id
    @Column(name = "item_key")
    public String itemKey;

    @Column(nullable = false)
    public String login;

    @Column(nullable = false)
    public Instant ignored = Instant.now();

    /** When new activity brought it back; null while it's still ignored. */
    @Column(name = "returned_at")
    public Instant returnedAt;

    @Column(name = "returned_reason")
    public String returnedReason;

    public static List<IgnoredItem> active() {
        return list("returnedAt is null");
    }

    public static List<IgnoredItem> returnedSince(Instant since) {
        return list("returnedAt > ?1", since);
    }
}
