package io.github.houseelves.desk.security;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import org.eclipse.microprofile.config.inject.ConfigProperty;

import io.quarkus.security.UnauthorizedException;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * The GitHub account the desk works as: one of the accounts {@code gh} is logged in to on this machine, named by
 * {@code elfdesk.github-user} (gh's active account when that's empty). Its token comes from {@code gh auth token}
 * and is only used server-side.
 */
@ApplicationScoped
public class GhAccount {

    /** gh tokens don't expire, but asking again now and then picks up a new login. */
    private static final Duration KEEP = Duration.ofMinutes(5);

    @ConfigProperty(name = "elfdesk.github-user")
    Optional<String> configured;

    private String login;
    private String token;
    private Instant fetched = Instant.EPOCH;

    public synchronized String login() {
        if (login == null) {
            login = configured.map(String::strip).filter(s -> !s.isEmpty())
                    .orElseGet(() -> gh("api", "user", "--jq", ".login"));
        }
        return login;
    }

    public synchronized String token() {
        if (token == null || Instant.now().isAfter(fetched.plus(KEEP))) {
            token = gh("auth", "token", "-u", login());
            fetched = Instant.now();
        }
        return token;
    }

    private static String gh(String... args) {
        List<String> cmd = new ArrayList<>(List.of("gh"));
        cmd.addAll(List.of(args));
        try {
            ProcessBuilder pb = new ProcessBuilder(cmd).redirectErrorStream(true);
            // A GH_TOKEN in the desk's environment would win over the account we ask for.
            pb.environment().remove("GH_TOKEN");
            pb.environment().remove("GITHUB_TOKEN");
            Process p = pb.start();
            String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip();
            if (!p.waitFor(30, TimeUnit.SECONDS) || p.exitValue() != 0 || out.isEmpty()) {
                throw new UnauthorizedException("gh couldn't give the desk a GitHub login (" + out
                        + "). Run gh auth login, then house-elves doctor.");
            }
            return out;
        } catch (IOException e) {
            throw new UnauthorizedException("gh isn't installed or isn't on the desk's PATH. Run house-elves doctor.");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new UnauthorizedException("Interrupted while asking gh for a token");
        }
    }
}
