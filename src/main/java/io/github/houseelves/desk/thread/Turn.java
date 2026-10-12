package io.github.houseelves.desk.thread;

import java.time.Instant;
import java.util.List;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** One message in a thread: yours, the agent's, or a system note. */
@Entity
@Table(name = "turn")
public class Turn extends PanacheEntityBase {

    public static final String YOU = "you", AGENT = "agent", SYSTEM = "system";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    @Column(name = "thread_id", nullable = false)
    public Long threadId;

    @Column(nullable = false)
    public String role;

    public String action;

    @Column(nullable = false)
    public String text;

    @Column(nullable = false)
    public Instant created = Instant.now();

    public static Turn add(long threadId, String role, String action, String text) {
        Turn t = new Turn();
        t.threadId = threadId;
        t.role = role;
        t.action = action;
        t.text = text;
        t.persist();
        return t;
    }

    public static List<Turn> of(long threadId) {
        return list("threadId = ?1 order by id", threadId);
    }
}
