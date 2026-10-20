package io.github.houseelves.desk.settings;

import java.util.Arrays;
import java.util.List;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;

/** Typed access to the settings table, with defaults for anything never saved. */
@ApplicationScoped
public class Settings {

    static final String REFRESH_MINUTES = "board.refresh-minutes";
    static final int DEFAULT_REFRESH_MINUTES = 5;
    static final String MVNPM_ENABLED = "mvnpm.enabled";
    static final String ALWAYS_INVOLVED = "board.always-involved";

    /** Repos you maintain (owner/repo, lower case): every open PR and issue in them is on the board. */
    @Transactional
    public List<String> alwaysInvolved() {
        Setting s = Setting.findById(ALWAYS_INVOLVED);
        if (s == null) return List.of();
        return Arrays.stream(s.value.split("\n")).map(String::strip).filter(r -> !r.isEmpty()).toList();
    }

    @Transactional
    public void setAlwaysInvolved(List<String> repos) {
        Setting s = Setting.findById(ALWAYS_INVOLVED);
        if (repos.isEmpty()) {
            if (s != null) s.delete();
            return;
        }
        if (s == null) {
            s = new Setting();
            s.key = ALWAYS_INVOLVED;
        }
        s.value = String.join("\n", repos);
        s.persist();
    }

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

    /** The mvnpm lane and checks on the desk (on unless turned off; they also need mvnpm-dependabot set up). */
    @Transactional
    public boolean mvnpmEnabled() {
        Setting s = Setting.findById(MVNPM_ENABLED);
        return s == null || !s.value.equals("false");
    }

    @Transactional
    public void setMvnpmEnabled(boolean on) {
        Setting s = Setting.findById(MVNPM_ENABLED);
        if (s == null) {
            s = new Setting();
            s.key = MVNPM_ENABLED;
        }
        s.value = Boolean.toString(on);
        s.persist();
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
