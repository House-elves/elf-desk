package io.github.houseelves.desk.thread;

import java.time.Instant;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** The thread for one issue or PR: one resumable Claude session. */
@Entity
@Table(name = "thread")
public class DeskThread extends PanacheEntityBase {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    @Column(name = "item_key", nullable = false, unique = true)
    public String itemKey;

    /** PR or ISSUE. */
    @Column(name = "item_kind", nullable = false)
    public String itemKind;

    @Column(nullable = false)
    public String title;

    @Column(nullable = false)
    public String url;

    /** Set from the first finished job; every later job resumes it. */
    @Column(name = "claude_session")
    public String claudeSession;

    @Column(nullable = false)
    public Instant created = Instant.now();

    @Column(nullable = false)
    public Instant updated = Instant.now();

    public static DeskThread byKey(String itemKey) {
        return find("itemKey", itemKey).firstResult();
    }

    public String repo() {
        return itemKey.substring(0, itemKey.indexOf('#'));
    }

    public int number() {
        return Integer.parseInt(itemKey.substring(itemKey.indexOf('#') + 1));
    }
}
