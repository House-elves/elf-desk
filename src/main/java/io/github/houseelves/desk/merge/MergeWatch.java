package io.github.houseelves.desk.merge;

import java.time.Instant;
import java.util.List;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** A PR to merge once CI is green. See V3__merge_watch.sql. */
@Entity
@Table(name = "merge_watch")
public class MergeWatch extends PanacheEntityBase {

    public static final String WAITING = "waiting", MERGED = "merged", FAILED = "failed", CANCELLED = "cancelled";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    @Column(name = "item_key", nullable = false)
    public String itemKey;

    /** merge, squash or rebase. */
    @Column(nullable = false)
    public String method;

    /** The head you saw when you clicked; the merge only goes through for this commit. */
    @Column(name = "head_sha", nullable = false)
    public String headSha;

    @Column(nullable = false)
    public String state = WAITING;

    /** False for "Merge without approval": you merged without an approval, so a missing approval doesn't stop it. */
    @Column(name = "require_approval", nullable = false)
    public boolean requireApproval = true;

    /** Why it ended, in words, once it has. */
    public String outcome;

    @Column(nullable = false)
    public String login;

    @Column(nullable = false)
    public Instant created = Instant.now();

    public Instant checked;

    public Instant finished;

    public static MergeWatch waitingFor(String itemKey) {
        return find("itemKey = ?1 and state = ?2", itemKey, WAITING).firstResult();
    }

    public static List<MergeWatch> waiting() {
        return list("state = ?1 order by id", WAITING);
    }

    /** The newest watch for a PR, whatever its state. */
    public static MergeWatch latestFor(String itemKey) {
        return find("itemKey = ?1 order by id desc", itemKey).firstResult();
    }
}
