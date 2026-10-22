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
    /** A file in an elf's data folder: a plain name, as the runner checks it too. */
    static final Pattern FILE = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,120}");
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
        // What its tab may do: without asking, or only after you confirm in the desk's bar.
        ArrayNode quiet = m.putArray("quietActions"), asks = m.putArray("askingActions");
        y.path("actions").forEach(a -> (a.path("ask").isBoolean() && !a.path("ask").asBoolean() ? quiet : asks)
                .add(a.path("label").asText()));
        m.put("title", y.path("title").asText(name));
        // Its tab: one per elf (an elf.yaml from before 0.32 listed tabs: its first is the one it gets).
        JsonNode tab = y.hasNonNull("tab") ? y.path("tab") : y.path("tabs").path(0);
        m.put("tab", tab.isObject());
        m.put("card", tab.path("card").asBoolean(false));
        m.put("agents", y.path("agents").isObject() || y.path("agents").asBoolean(false));
        // Its own version and who made it, as the elf.yaml says (the runner checks both when it installs it).
        String version = y.path("version").asText(""), author = y.path("author").asText("").replaceFirst("^@", "");
        if (version.matches("\\d{1,4}(\\.\\d{1,4}){0,3}([.-][A-Za-z0-9]{1,20})?")) m.put("version", version);
        if (author.matches("[A-Za-z0-9](?:[A-Za-z0-9-]{0,38})")) m.put("author", author);
        // When it refreshes on its own until you change it, if it refreshes at all.
        JsonNode refresh = y.path("refresh");
        JsonNode schedule = y.path("schedule");
        if (refresh.isObject() || refresh.isTextual()) {
            m.put("refreshes", true);
            if (refresh.hasNonNull("schedule")) m.put("schedule", refresh.path("schedule").asText());
        } else if (schedule.isObject()) {
            String setting = schedule.path("setting").asText(null);
            String when = schedule.path("default").asText(null);
            for (JsonNode s : y.path("settings")) {
                if (s.path("key").asText().equals(setting)) when = s.path("default").asText(when);
            }
            m.put("refreshes", true);
            if (when != null) m.put("schedule", when);
        } else {
            m.put("refreshes", tab.hasNonNull("refresh"));
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

    /** Runs one of the commands the elf declares (its dry run, say), with the values it takes (checked by the runner). */
    public JsonNode run(String elf, String command, JsonNode params) {
        ObjectNode msg = hub.message("elf.run").put("elf", name(elf)).put("command", name(command));
        if (params != null && params.isObject()) msg.set("params", params);
        return ask(msg, 60);
    }

    // ---- an elf's own tab ----

    /** Its runs going now and since when, and the end of the log of each whose last run failed. */
    public JsonNode state() {
        return hub.elvesState();
    }

    public JsonNode data(String elf, String folder) {
        return ask(hub.message("elf.data").put("elf", name(elf)).put("folder", name(folder)), 30, RunnerHub.ELF_TABS_SINCE);
    }

    public JsonNode read(String elf, String folder, List<String> files, List<String> fields) {
        ObjectNode msg = hub.message("elf.read").put("elf", name(elf)).put("folder", name(folder));
        ArrayNode f = msg.putArray("files");
        for (String name : files == null ? List.<String>of() : files) {
            if (name == null || !FILE.matcher(name).matches()) throw new IllegalArgumentException("Bad file name");
            f.add(name);
        }
        if (fields != null && !fields.isEmpty()) {
            ArrayNode x = msg.putArray("fields");
            fields.forEach(x::add);
        }
        return ask(msg, 60, RunnerHub.ELF_TABS_SINCE);
    }

    public JsonNode action(String elf, String action, JsonNode params) {
        ObjectNode msg = hub.message("elf.action").put("elf", name(elf)).put("action", name(action));
        msg.set("params", params != null && params.isObject() ? params : mapper.createObjectNode());
        return ask(msg, 180, RunnerHub.ELF_TABS_SINCE);
    }

    /** The folders an installed elf's tab may read (its elf.yaml's data, and agents for one that starts them). */
    public java.util.List<String> dataFolders(String elf) {
        for (JsonNode e : installed().path("elves")) {
            if (e.path("name").asText().equals(elf)) {
                java.util.List<String> out = new java.util.ArrayList<>();
                e.path("data").forEach(d -> out.add(d.asText()));
                return out;
            }
        }
        return java.util.List.of();
    }

    /** Stops one of an elf's agents. */
    public JsonNode cancelAgent(String elf, String id) {
        if (id == null || !id.matches("[a-z0-9][a-z0-9-]{0,79}")) throw new IllegalArgumentException("Not an agent");
        // "agent", not "id": a request's id is the desk's own, to match the answer.
        return ask(hub.message("elf.agentCancel").put("elf", name(elf)).put("agent", id), 30, RunnerHub.ELF_AGENTS_SINCE);
    }

    /** When an elf refreshes on its own: a calendar time, null for never, or "default" for its elf.yaml's. */
    public JsonNode schedule(String elf, String calendar) {
        ObjectNode msg = hub.message("elf.schedule").put("elf", name(elf));
        if (calendar == null || calendar.isBlank()) msg.putNull("calendar");
        else if (calendar.length() > 300 || calendar.chars().anyMatch(c -> c < 0x20)) throw new IllegalArgumentException("Not a time it can run at");
        else msg.put("calendar", calendar.strip());
        return ask(msg, 30, RunnerHub.ELF_REFRESH_SINCE);
    }

    /** An installed elf's tab: {elf, commit, title, page}, or null if it has none. */
    public Tab tab(String elf) {
        if (elf == null || !NAME.matcher(elf).matches()) return null;
        for (JsonNode e : installed().path("elves")) {
            if (!e.path("name").asText().equals(elf)) continue;
            // A runner before 0.32 lists tabs: the first is the elf's tab.
            JsonNode t = e.hasNonNull("tab") ? e.path("tab") : e.path("tabs").path(0);
            if (!t.isObject()) return null;
            return new Tab(elf, e.path("commit").asText(), e.path("title").asText(elf), t.path("page").asText());
        }
        return null;
    }

    public record Tab(String elf, String commit, String title, String page) {
    }

    /** The UI's files: they only change with the commit, so each is asked for once. */
    private final Map<String, byte[]> files = new java.util.concurrent.ConcurrentHashMap<>();

    /** A file of an elf's UI (ui/...) at that commit, or null. */
    public byte[] file(String elf, String commit, String path) {
        String key = elf + "@" + commit + ":" + path;
        byte[] cached = files.get(key);
        if (cached != null) return cached;
        JsonNode r = ask(hub.message("elf.file").put("elf", name(elf)).put("path", path), 30, RunnerHub.ELF_TABS_SINCE);
        if (!r.path("commit").asText().equals(commit)) return null; // updated meanwhile: the frame reloads
        byte[] data = java.util.Base64.getDecoder().decode(r.path("data").asText(""));
        if (files.size() > 500) files.clear();
        files.put(key, data);
        return data;
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
        return ask(msg, seconds, RunnerHub.ELVES_SINCE);
    }

    private JsonNode ask(ObjectNode msg, int seconds, String since) {
        JsonNode result;
        try {
            result = hub.request(msg, since).get(seconds, TimeUnit.SECONDS);
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
