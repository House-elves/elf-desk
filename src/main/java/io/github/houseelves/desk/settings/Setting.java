package io.github.houseelves.desk.settings;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** A key/value setting. Values are stored as text; {@link Settings} gives them types. */
@Entity
@Table(name = "setting")
public class Setting extends PanacheEntityBase {

    @Id
    @Column(name = "setting_key")
    public String key;

    @Column(name = "setting_value", nullable = false)
    public String value;
}
