package io.github.houseelves.desk.status;

import java.time.Instant;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** A weekly status draft you sent to the team yourself: "I sent it myself" on the Status tab. See V14. */
@Entity
@Table(name = "status_sent_yourself")
public class SentYourself extends PanacheEntityBase {

    /** The draft's id (yyyy-MM-dd_HH-mm-ss). */
    @Id
    public String draft;

    /** When you marked it. */
    @Column(nullable = false)
    public Instant at = Instant.now();
}
