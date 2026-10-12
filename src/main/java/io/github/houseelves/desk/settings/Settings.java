package io.github.houseelves.desk.settings;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;

/** Typed access to the settings table, with defaults for anything never saved. */
@ApplicationScoped
public class Settings {

    static final String REFRESH_MINUTES = "board.refresh-minutes";
    static final int DEFAULT_REFRESH_MINUTES = 5;

    @Transactional
    public int refreshMinutes() {
        Setting s = Setting.findById(REFRESH_MINUTES);
        if (s == null) return DEFAULT_REFRESH_MINUTES;
        try {
            return Integer.parseInt(s.value);
        } catch (NumberFormatException e) {
            return DEFAULT_REFRESH_MINUTES;
        }
    }

    @Transactional
    public void setRefreshMinutes(int minutes) {
        if (minutes < 1 || minutes > 60) {
            throw new IllegalArgumentException("Refresh must be between 1 and 60 minutes");
        }
        Setting s = Setting.findById(REFRESH_MINUTES);
        if (s == null) {
            s = new Setting();
            s.key = REFRESH_MINUTES;
        }
        s.value = Integer.toString(minutes);
        s.persist();
    }
}
