package io.github.houseelves.desk.scope;

import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;

/** Reads and edits what the board watches. Names are stored lower-case. */
@ApplicationScoped
public class ScopeService {

    // GitHub's own rules: owners are alphanumeric with single hyphens; repo names add dots and underscores.
    private static final Pattern OWNER = Pattern.compile("[a-z0-9](?:[a-z0-9]|-(?=[a-z0-9])){0,38}");
    private static final Pattern REPO = Pattern.compile(OWNER.pattern() + "/[a-z0-9._-]{1,100}");

    @Transactional
    public Scope scope() {
        return Scope.of(WatchScope.<WatchScope>listAll());
    }

    @Transactional
    public List<WatchScope> rows() {
        return WatchScope.list("order by kind, name");
    }

    /**
     * Watches an owner ("quarkusio") or a single repo ("mvnpm/mvnpm"). Watching a
     * repo that a watched owner excludes lifts the exclusion instead.
     */
    @Transactional
    public void watch(String nameOrRepo) {
        String name = normalise(nameOrRepo);
        if (OWNER.matcher(name).matches()) {
            if (find(WatchScope.Kind.owner, name) == null) add(WatchScope.Kind.owner, name, WatchScope.Mode.include);
            return;
        }
        if (!REPO.matcher(name).matches()) {
            throw new IllegalArgumentException("'" + nameOrRepo + "' is not an org, a user, or an owner/repo");
        }
        WatchScope existing = find(WatchScope.Kind.repo, name);
        if (existing != null && existing.mode == WatchScope.Mode.exclude) {
            existing.delete();
            return;
        }
        if (existing != null) return;
        if (find(WatchScope.Kind.owner, owner(name)) != null) {
            throw new IllegalArgumentException(name + " is already covered by " + owner(name));
        }
        add(WatchScope.Kind.repo, name, WatchScope.Mode.include);
    }

    /** First-run save: watch each of these owners. */
    @Transactional
    public void watchAll(List<String> owners) {
        owners.forEach(this::watch);
    }

    /** Stops showing one repo of a watched owner. */
    @Transactional
    public void exclude(String repo) {
        String name = normalise(repo);
        if (!REPO.matcher(name).matches()) throw new IllegalArgumentException("'" + repo + "' is not an owner/repo");
        if (find(WatchScope.Kind.owner, owner(name)) == null) {
            throw new IllegalArgumentException(owner(name) + " isn't watched, so there's nothing to exclude");
        }
        WatchScope existing = find(WatchScope.Kind.repo, name);
        if (existing != null) existing.delete();
        add(WatchScope.Kind.repo, name, WatchScope.Mode.exclude);
    }

    /** Removes a row. Removing an owner also drops its exclusions, which mean nothing without it. */
    @Transactional
    public void remove(long id) {
        WatchScope row = WatchScope.findById(id);
        if (row == null) return;
        if (row.kind == WatchScope.Kind.owner) {
            WatchScope.delete("kind = ?1 and mode = ?2 and name like ?3", WatchScope.Kind.repo,
                    WatchScope.Mode.exclude, row.name + "/%");
        }
        row.delete();
    }

    private static WatchScope find(WatchScope.Kind kind, String name) {
        return WatchScope.find("kind = ?1 and name = ?2", kind, name).firstResult();
    }

    private static void add(WatchScope.Kind kind, String name, WatchScope.Mode mode) {
        WatchScope w = new WatchScope();
        w.kind = kind;
        w.name = name;
        w.mode = mode;
        w.persist();
    }

    private static String owner(String repo) {
        return repo.substring(0, repo.indexOf('/'));
    }

    /** An owner/repo as typed or pasted (a GitHub URL works too), lower case; refuses anything else. */
    public static String repoName(String raw) {
        String name = normalise(raw);
        if (!REPO.matcher(name).matches()) throw new IllegalArgumentException("'" + raw + "' is not an owner/repo");
        return name;
    }

    private static String normalise(String raw) {
        if (raw == null) throw new IllegalArgumentException("Enter an org, a user, or an owner/repo");
        String s = raw.trim().toLowerCase(Locale.ROOT);
        // Accept a pasted URL too: https://github.com/quarkusio/quarkus
        s = s.replaceFirst("^https?://github\\.com/", "").replaceFirst("/+$", "");
        return s;
    }
}
