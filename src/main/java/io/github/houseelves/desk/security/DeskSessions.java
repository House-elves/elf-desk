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
 * {@code open-code} and opens {@code /open?code=...}, which swaps it for a new session cookie; each code works once.
 *
 * <p>
 * The sessions are in {@code sessions} in the data directory, as hashes with an expiry, so the cookies themselves
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
    private Map<String, Instant> sessions = Map.of();

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

    public boolean isSession(String token) {
        if (token == null || token.isEmpty()) return false;
        Instant expires = current().get(hash(token));
        return expires != null && Instant.now().isBefore(expires);
    }

    /** A new session for a valid one-time code (which is then used up), or null. */
    public synchronized String redeem(String code) {
        try {
            Path codeFile = dataDir.resolve("open-code");
            String current = Files.isRegularFile(codeFile) ? Files.readString(codeFile).strip() : "";
            if (code == null || current.isEmpty() || !MessageDigest.isEqual(code.getBytes(StandardCharsets.UTF_8),
                    current.getBytes(StandardCharsets.UTF_8))) {
                return null;
            }
            newCode();
            String token = random();
            Instant now = Instant.now();
            Map<String, Instant> kept = new LinkedHashMap<>();
            current().entrySet().stream().filter(e -> e.getValue().isAfter(now))
                    .sorted(Map.Entry.<String, Instant> comparingByValue(Comparator.reverseOrder()))
                    .limit(MAX - 1).forEach(e -> kept.put(e.getKey(), e.getValue()));
            kept.put(hash(token), now.plus(LIFETIME));
            writePrivate(file, kept.entrySet().stream().map(e -> e.getKey() + " " + e.getValue().getEpochSecond())
                    .collect(Collectors.joining("\n")));
            // Remembered here too: a write within the file system's timestamp resolution looks unchanged.
            sessions = kept;
            readAt = Files.getLastModifiedTime(file);
            return token;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** The sessions file as it is now: read again whenever it changes, and empty once it's deleted. */
    private synchronized Map<String, Instant> current() {
        try {
            FileTime modified = Files.getLastModifiedTime(file);
            if (!modified.equals(readAt)) {
                Map<String, Instant> read = new LinkedHashMap<>();
                for (String line : Files.readAllLines(file)) {
                    String[] parts = line.strip().split(" ");
                    if (parts.length == 2) read.put(parts[0], Instant.ofEpochSecond(Long.parseLong(parts[1])));
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
