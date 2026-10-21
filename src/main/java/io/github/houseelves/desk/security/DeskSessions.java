package io.github.houseelves.desk.security;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;

import org.eclipse.microprofile.config.inject.ConfigProperty;

import io.quarkus.runtime.Startup;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * Who may use the desk: a browser holding one of its sessions. {@code house-elves open} reads the one-time code in
 * {@code open-code} and opens {@code /open?code=...}, which swaps it for a new session; each code works once.
 *
 * <p>
 * A session is two secrets. The cookie goes with every request, but browsers keep cookies per host name, not per
 * port, so a server on another port of the same name could be sent it too. The page key is kept in the page's own
 * storage, which browsers keep per origin, port included, and the page adds it to everything that carries data
 * (see {@link DeskSignIn}).
 *
 * <p>
 * The sessions are in {@code sessions} in the data directory, as hashes with an expiry, so the secrets themselves
 * are nowhere on disk. Deleting the file ({@code house-elves sign-out}) signs every browser out at once.
 */
@Startup
@ApplicationScoped
public class DeskSessions {

    static final Duration LIFETIME = Duration.ofDays(30);
    static final int MAX = 20;

    private static final SecureRandom RANDOM = new SecureRandom();

    @ConfigProperty(name = "elfdesk.data-dir")
    Path dataDir;

    private Path file;
    private FileTime readAt;
    private Map<String, Session> sessions = Map.of();

    /** A session as stored: its page key's hash, and when it ends. */
    record Session(String pageHash, Instant expires) {
    }

    /** What a browser gets for a one-time code: the cookie's value and the page key. */
    public record Opened(String token, String pageKey) {
    }

    @PostConstruct
    void init() {
        try {
            Files.createDirectories(dataDir);
            // The database and the sessions live here: only you can look inside.
            try {
                Files.setPosixFilePermissions(dataDir, PosixFilePermissions.fromString("rwx------"));
            } catch (UnsupportedOperationException e) {
                // Not a POSIX file system: its own permissions apply.
            }
            // Before sessions, the cookie was this key itself, and browsers send localhost's cookies to every port.
            Files.deleteIfExists(dataDir.resolve("desk.key"));
            file = dataDir.resolve("sessions");
            newCode();
        } catch (IOException e) {
            throw new UncheckedIOException("Couldn't set up the desk's sessions in " + dataDir, e);
        }
    }

    /**
     * The cookie is a live session's, and, when the request carries data ({@code needsPageKey}), the page key is
     * that session's too.
     */
    public boolean isSession(String token, boolean needsPageKey, String pageKey) {
        if (token == null || token.isEmpty()) return false;
        Session s = current().get(hash(token));
        if (s == null || !Instant.now().isBefore(s.expires())) return false;
        if (!needsPageKey) return true;
        return pageKey != null && MessageDigest.isEqual(hash(pageKey).getBytes(StandardCharsets.UTF_8),
                s.pageHash().getBytes(StandardCharsets.UTF_8));
    }

    /** A new session for a valid one-time code (which is then used up), or null. */
    public synchronized Opened redeem(String code) {
        try {
            Path codeFile = dataDir.resolve("open-code");
            String current = Files.isRegularFile(codeFile) ? Files.readString(codeFile).strip() : "";
            if (code == null || current.isEmpty() || !MessageDigest.isEqual(code.getBytes(StandardCharsets.UTF_8),
                    current.getBytes(StandardCharsets.UTF_8))) {
                return null;
            }
            newCode();
            Opened opened = new Opened(random(), random());
            Instant now = Instant.now();
            Map<String, Session> kept = new LinkedHashMap<>();
            current().entrySet().stream().filter(e -> e.getValue().expires().isAfter(now))
                    .sorted(Comparator.comparing((Map.Entry<String, Session> e) -> e.getValue().expires()).reversed())
                    .limit(MAX - 1).forEach(e -> kept.put(e.getKey(), e.getValue()));
            kept.put(hash(opened.token()), new Session(hash(opened.pageKey()), now.plus(LIFETIME)));
            writePrivate(file, kept.entrySet().stream()
                    .map(e -> e.getKey() + " " + e.getValue().pageHash() + " " + e.getValue().expires().getEpochSecond())
                    .collect(Collectors.joining("\n")));
            // Remembered here too: a write within the file system's timestamp resolution looks unchanged.
            sessions = kept;
            readAt = Files.getLastModifiedTime(file);
            return opened;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** The sessions file as it is now: read again whenever it changes, and empty once it's deleted. */
    private synchronized Map<String, Session> current() {
        try {
            FileTime modified = Files.getLastModifiedTime(file);
            if (!modified.equals(readAt)) {
                Map<String, Session> read = new LinkedHashMap<>();
                for (String line : Files.readAllLines(file)) {
                    String[] parts = line.strip().split(" ");
                    if (parts.length == 3) {
                        read.put(parts[0], new Session(parts[1], Instant.ofEpochSecond(Long.parseLong(parts[2]))));
                    }
                }
                sessions = read;
                readAt = modified;
            }
        } catch (IOException | NumberFormatException e) {
            // Gone (signed out everywhere) or unreadable: no sessions.
            sessions = Map.of();
            readAt = null;
        }
        return sessions;
    }

    private void newCode() throws IOException {
        writePrivate(dataDir.resolve("open-code"), random());
    }

    private static String random() {
        byte[] b = new byte[32];
        RANDOM.nextBytes(b);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }

    static String hash(String token) {
        try {
            byte[] h = MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(h);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Written next to the target and moved over it, readable only by you from the start. */
    private static void writePrivate(Path file, String content) throws IOException {
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.deleteIfExists(tmp);
        try {
            Files.createFile(tmp, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
        } catch (UnsupportedOperationException e) {
            Files.createFile(tmp);
        }
        Files.writeString(tmp, content + "\n");
        Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }
}
