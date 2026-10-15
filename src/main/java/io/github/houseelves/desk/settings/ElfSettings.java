package io.github.houseelves.desk.settings;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.github.houseelves.desk.runner.RunnerHub;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * The elves' own settings, shown under each tab's Settings: the runner reads and writes their config files (it
 * decides which keys may be changed and checks each value). Secrets come back only as set or not.
 */
@ApplicationScoped
public class ElfSettings {

    /** The elves whose settings the desk shows. */
    static final Set<String> ELVES = Set.of("daily-triage", "weekly-status", "quarterly-review", "mvnpm-dependabot", "elf-runner");

    @Inject
    RunnerHub hub;

    @Inject
    ObjectMapper mapper;

    /** An elf's settings: {elf, installed, file, settings: [{key, label, help, kind, value | set, min, max}]}. */
    public JsonNode get(String elf) {
        return ask(hub.message("elf.config").put("elf", known(elf)));
    }

    /**
     * Changes some of an elf's settings (an empty secret keeps the old one). A wrong value changes nothing and comes
     * back as {wrong: {key: why}}.
     */
    public JsonNode set(String elf, Map<String, String> values) {
        var msg = hub.message("elf.configSet").put("elf", known(elf));
        msg.set("values", mapper.valueToTree(values == null ? Map.of() : values));
        return ask(msg);
    }

    private static String known(String elf) {
        if (!ELVES.contains(elf)) throw new IllegalArgumentException("Not an elf: " + elf);
        return elf;
    }

    private JsonNode ask(com.fasterxml.jackson.databind.node.ObjectNode msg) {
        JsonNode result;
        try {
            result = hub.request(msg, RunnerHub.ELF_CONFIG_SINCE).get(60, TimeUnit.SECONDS);
        } catch (Exception e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            throw new IllegalStateException(cause.getMessage());
        }
        int status = result.path("status").asInt();
        if (status != 200 && status != 422) {
            throw new IllegalStateException(result.path("error").asText("The runner couldn't read the settings"));
        }
        try {
            return mapper.readTree(result.path("body").asText("{}"));
        } catch (Exception e) {
            throw new IllegalStateException("The runner's answer wasn't readable");
        }
    }
}
