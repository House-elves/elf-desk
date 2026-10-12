package io.github.houseelves.desk.scope;

import java.time.Instant;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** One row of what the board watches. See V1__scope_and_settings.sql. */
@Entity
@Table(name = "watch_scope")
public class WatchScope extends PanacheEntityBase {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    public enum Kind { owner, repo }

    public enum Mode { include, exclude }

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    public Kind kind;

    @Column(nullable = false)
    public String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    public Mode mode;

    @Column(nullable = false)
    public Instant added = Instant.now();
}
