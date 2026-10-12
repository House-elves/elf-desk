package io.github.houseelves.desk.thread;

import java.time.Instant;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** Audit row for one write to GitHub. Never updated or deleted. */
@Entity
@Table(name = "github_action")
public class GitHubAction extends PanacheEntityBase {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    @Column(name = "thread_id")
    public Long threadId;

    @Column(name = "draft_id")
    public Long draftId;

    @Column(nullable = false)
    public String kind;

    /** What was sent, as JSON. */
    @Column(nullable = false)
    public String request;

    @Column(name = "result_url")
    public String resultUrl;

    @Column(nullable = false)
    public String login;

    @Column(nullable = false)
    public Instant at = Instant.now();
}
