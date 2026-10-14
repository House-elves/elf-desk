package io.github.houseelves.desk.thread;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** One agent run on the runner. */
@Entity
@Table(name = "job")
public class Job extends PanacheEntityBase {

    public static final String QUEUED = "queued", RUNNING = "running", DONE = "done", FAILED = "failed",
            CANCELLED = "cancelled";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    @Column(name = "thread_id", nullable = false)
    public Long threadId;

    @Column(nullable = false)
    public String action;

    /** The full prompt sent to the agent, kept for the record. */
    @Column(nullable = false)
    public String prompt;

    /** JSON telling the runner what to check out: {repo, number, kind, base}. */
    @Column(nullable = false)
    public String checkout;

    /** Text from outsiders that the runner triages before the agent sees it; null when none. */
    public String untrusted;

    @Column(nullable = false)
    public String state = QUEUED;

    public String error;

    /** A self-review only: how many fixes it found worth making. */
    public Integer fixes;

    @Column(name = "cost_usd")
    public BigDecimal costUsd;

    @Column(nullable = false)
    public Instant created = Instant.now();

    public Instant started;

    public Instant finished;

    public boolean open() {
        return state.equals(QUEUED) || state.equals(RUNNING);
    }

    public static Job openFor(long threadId) {
        return find("threadId = ?1 and state in (?2, ?3)", threadId, QUEUED, RUNNING).firstResult();
    }

    /** The thread's most recent job, open or not, or null. */
    /** The last finished self-review or self-review fix: whether the review's fixes are still to be made. */
    public static Job lastReviewFor(long threadId) {
        return find("threadId = ?1 and state = ?2 and action in ?3 order by id desc", threadId, DONE,
                List.of("review", "self-fix")).firstResult();
    }

    public static Job latestFor(long threadId) {
        return find("threadId = ?1 order by id desc", threadId).firstResult();
    }

    public static List<Job> inState(String state) {
        return list("state = ?1 order by id", state);
    }
}
