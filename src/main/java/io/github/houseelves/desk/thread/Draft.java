package io.github.houseelves.desk.thread;

import java.time.Instant;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * Something postable the agent produced, held until you post or discard it.
 * {@code payload} is the JSON of {@link io.github.houseelves.desk.agent.AgentOutput.DraftOut}, or for a
 * push, of {@link PushOut}.
 */
@Entity
@Table(name = "draft")
public class Draft extends PanacheEntityBase {

    public static final String REVIEW = "review", COMMENT = "comment", PUSH = "push", ISSUE = "issue", PR = "pr";
    public static final String PENDING = "draft", POSTED = "posted", DISCARDED = "discarded";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    @Column(name = "thread_id", nullable = false)
    public Long threadId;

    @Column(name = "job_id")
    public Long jobId;

    @Column(nullable = false)
    public String kind;

    @Column(nullable = false)
    public String payload;

    @Column(nullable = false)
    public String state = PENDING;

    @Column(name = "posted_url")
    public String postedUrl;

    @Column(name = "posted_at")
    public Instant postedAt;

    @Column(nullable = false)
    public Instant created = Instant.now();

    /** The draft waiting for you in a thread, if any (only the newest one is kept pending). */
    public static Draft pendingFor(long threadId) {
        return find("threadId = ?1 and state = ?2 order by id desc", threadId, PENDING).firstResult();
    }
}
