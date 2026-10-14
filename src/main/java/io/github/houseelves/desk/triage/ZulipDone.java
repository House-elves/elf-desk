package io.github.houseelves.desk.triage;

import java.io.Serializable;
import java.time.Instant;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;

/** A Zulip topic you marked Done on the Zulip tab: its messages were marked read. See V13. */
@Entity
@Table(name = "zulip_done")
@IdClass(ZulipDone.Key.class)
public class ZulipDone extends PanacheEntityBase {

    @Id
    public String server;

    /** The stream, or "DM" for direct messages (the Zulip tab shows them as one topic). */
    @Id
    public String stream;

    @Id
    public String topic;

    @Column(nullable = false)
    public Instant done = Instant.now();

    /** How many messages were marked read. */
    @Column(nullable = false)
    public int marked;

    public record Key(String server, String stream, String topic) implements Serializable {
        public Key() {
            this(null, null, null);
        }
    }
}
