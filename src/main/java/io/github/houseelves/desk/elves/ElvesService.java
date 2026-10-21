package io.github.houseelves.desk.elves;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

import io.github.houseelves.desk.github.GitHub;
import io.github.houseelves.desk.runner.RunnerHub;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * The Elves page: the elves you can install (repos with the house-elf topic, in House-elves or your own account),
 * the ones installed, and installing, updating, removing and running them. The runner does all of that on this
 * machine and checks every elf.yaml itself; the desk only shows what an elf says it does, so you can decide first.
 */
@ApplicationScoped
public class ElvesService {

    static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());
    static final Pattern NAME = Pattern.compile("[a-z0-9][a-z0-9-]{0,40}");
    static final Pattern COMMIT = Pattern.compile("[0-9a-f]{40}");
    static final String OFFICIAL = "House-elves/";

    @Inject
    RunnerHub hub;

    @Inject
    ObjectMapper mapper;

    @Inject
    GitHub github;

    /** The installed elves: {elves: [{name, description, repo, commit, tools, commands, dryRun, reads, writes, schedule, runs}]}. */
    public JsonNode installed() {
        return ask(hub.message("elves.list"), 30);
    }

    /**
     * What you can install: {elves: [{repo, description, private, official, url, commit, manifest | problem,
     * installed?: {name, commit}}]}, with each elf.yaml as it is at the repo's newest commit.
     */
    public JsonNode catalog(String login, String token) {
        Map<String, JsonNode> installed = new HashMap<>();
        if (hub.connected()) {
            try {
                installed().path("elves").forEach(e -> installed.put(e.path("repo").asText(), e));
            } catch (RuntimeException e) {
                // An old runner: everything shows as not installed.
            }
        }
        ObjectNode out = mapper.createObjectNode();
        ArrayNode list = out.putArray("elves");
        for (GitHub.ElfRepo r : github.elfRepos(token, login)) {
            ObjectNode n = list.addObject().put("repo", r.repo()).put("description", r.description())
                    .put("private", r.isPrivate()).put("official", r.repo().startsWith(OFFICIAL)).put("url", r.url())
                    .put("commit", r.commit());
            try {
                n.set("manifest", preview(r.manifest()));
            } catch (Exception e) {
                n.put("problem", e.getMessage());
            }
            JsonNode i = installed.get(r.repo());
            if (i != null) n.putObject("installed").put("name", i.path("name").asText()).put("commit", i.path("commit").asText());
        }
        return out;
    }

    /**
     * An elf.yaml as the Elves page shows it before you install: what it is, what it reads and writes, the tools it
     * needs and the settings it'll ask for. Only for showing: the runner reads and checks the file again itself.
     */
    ObjectNode preview(String yaml) throws Exception {
        if (yaml == null) throw new IllegalStateException("It has no elf.yaml");
        JsonNode y = YAML.readTree(yaml);
        if (y == null || !y.isObject()) throw new IllegalStateException("Its elf.yaml is empty");
        String name = y.path("name").asText("");
        if (!NAME.matcher(name).matches()) throw new IllegalStateException("Its elf.yaml has no usable name");
        ObjectNode m = mapper.createObjectNode().put("name", name).put("description", y.path("description").asText(""));
        for (String field : List.of("tools", "reads", "writes")) {
            ArrayNode a = m.putArray(field);
            y.path(field).forEach(x -> a.add(x.asText()));
        }
        ArrayNode settings = m.putArray("settings");
        y.path("settings").forEach(s -> settings.addObject().put("label", s.path("label").asText(s.path("key").asText()))
                .put("secret", "secret".equalsIgnoreCase(s.path("kind").asText())));
        ArrayNode commands = m.putArray("commands");
        y.path("commands").forEach(c -> commands.add(c.path("label").asText()));
        JsonNode schedule = y.path("schedule");
        if (schedule.isObject()) {
            String setting = schedule.path("setting").asText(null);
            String when = schedule.path("default").asText(null);
            for (JsonNode s : y.path("settings")) {
                if (s.path("key").asText().equals(setting)) when = s.path("default").asText(when);
            }
            m.put("schedule", when);
        }
        return m;
    }

    /**
     * Installs an elf at the commit you looked at. Only a repo from the catalog: an elf repo in House-elves or your
     * own account, never any repo a request names.
     */
    public JsonNode install(String login, String token, String repo, String commit) {
        if (repo == null || commit == null || !COMMIT.matcher(commit).matches()) throw new IllegalArgumentException("Bad repo or commit");
        if (github.elfRepos(token, login).stream().noneMatch(r -> r.repo().equals(repo))) {
            throw new IllegalArgumentException(repo + " isn't an elf you can install here");
        }
        return ask(hub.message("elf.install").put("repo", repo).put("commit", commit), 300);
    }

    /** What updating would change: {commits, diff, truncated}. */
    public JsonNode changes(String elf, String commit) {
        return ask(hub.message("elf.changes").put("elf", name(elf)).put("commit", commit(commit)), 120);
    }

    public JsonNode update(String elf, String commit) {
        return ask(hub.message("elf.update").put("elf", name(elf)).put("commit", commit(commit)), 300);
    }

    public JsonNode remove(String elf) {
        return ask(hub.message("elf.remove").put("elf", name(elf)), 60);
    }

    /** Runs one of the commands the elf declares (its dry run, say). */
    public JsonNode run(String elf, String command) {
        return ask(hub.message("elf.run").put("elf", name(elf)).put("command", name(command)), 60);
    }

    public List<String> running() {
        return hub.elvesRunning();
    }

    static String name(String s) {
        if (s == null || !NAME.matcher(s).matches()) throw new IllegalArgumentException("Bad name");
        return s;
    }

    static String commit(String s) {
        if (s == null || !COMMIT.matcher(s).matches()) throw new IllegalArgumentException("Bad commit");
        return s;
    }

    private JsonNode ask(ObjectNode msg, int seconds) {
        JsonNode result;
        try {
            result = hub.request(msg, RunnerHub.ELVES_SINCE).get(seconds, TimeUnit.SECONDS);
        } catch (Exception e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            throw new IllegalStateException(cause.getMessage() != null ? cause.getMessage() : "The runner didn't answer in time");
        }
        if (result.path("status").asInt() != 200) {
            throw new IllegalStateException(result.path("error").asText("The runner couldn't do that"));
        }
        try {
            return mapper.readTree(result.path("body").asText("{}"));
        } catch (Exception e) {
            throw new IllegalStateException("The runner's answer wasn't readable");
        }
    }
}
