package io.github.houseelves.desk.triage;

import java.io.Serializable;
import java.time.Instant;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;

/** An email you marked Done on the Email tab: its Gmail thread was marked read. See V12. */
@Entity
@Table(name = "email_done")
@IdClass(EmailDone.Key.class)
public class EmailDone extends PanacheEntityBase {

    @Id
    public String account;

    @Id
    @Column(name = "message_id")
    public String messageId;

    @Column(nullable = false)
    public Instant done = Instant.now();

    /** How many unread messages of the thread Gmail marked read (0: someone already read it). */
    @Column(nullable = false)
    public int marked;

    public record Key(String account, String messageId) implements Serializable {
        public Key() {
            this(null, null);
        }
    }
}
