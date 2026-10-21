package io.github.houseelves.desk.security;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

import org.eclipse.microprofile.config.inject.ConfigProperty;

import io.quarkus.runtime.Startup;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * Who may use the desk: whoever holds its key, a random secret in {@code desk.key} in the data directory that only
 * you can read. The browser gets it as a cookie by opening a one-time link: {@code house-elves open} reads the code
 * in {@code open-code} and opens {@code /open?code=...}, and each code works once. The key itself never goes in a
 * URL, so it stays out of browser history.
 */
@Startup
@ApplicationScoped
public class DeskKey {

    private static final SecureRandom RANDOM = new SecureRandom();

    @ConfigProperty(name = "elfdesk.data-dir")
    Path dataDir;

    private String key;

    @PostConstruct
    void init() {
        try {
            Files.createDirectories(dataDir);
            // The database and the key live here: only you can look inside.
            try {
                Files.setPosixFilePermissions(dataDir, PosixFilePermissions.fromString("rwx------"));
            } catch (UnsupportedOperationException e) {
                // Not a POSIX file system: its own permissions apply.
            }
            Path file = dataDir.resolve("desk.key");
            if (!Files.isRegularFile(file)) writePrivate(file, random());
            key = Files.readString(file).strip();
            newCode();
        } catch (IOException e) {
            throw new UncheckedIOException("Couldn't set up the desk key in " + dataDir, e);
        }
    }

    public boolean isKey(String candidate) {
        return candidate != null && MessageDigest.isEqual(candidate.getBytes(StandardCharsets.UTF_8),
                key.getBytes(StandardCharsets.UTF_8));
    }

    /** The key for a valid one-time code (which is then used up), or null. */
    public synchronized String redeem(String code) {
        try {
            Path file = dataDir.resolve("open-code");
            String current = Files.isRegularFile(file) ? Files.readString(file).strip() : "";
            if (code == null || current.isEmpty() || !MessageDigest.isEqual(code.getBytes(StandardCharsets.UTF_8),
                    current.getBytes(StandardCharsets.UTF_8))) {
                return null;
            }
            newCode();
            return key;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private void newCode() throws IOException {
        writePrivate(dataDir.resolve("open-code"), random());
    }

    private static String random() {
        byte[] b = new byte[32];
        RANDOM.nextBytes(b);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
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
