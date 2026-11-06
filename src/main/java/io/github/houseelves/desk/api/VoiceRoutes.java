package io.github.houseelves.desk.api;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import io.vertx.ext.web.handler.BodyHandler;
import jakarta.enterprise.event.Observes;

/**
 * Voice input with Whisper on this machine (whisper.cpp), for when your audio shouldn't leave it: the desk's page
 * records you and sends the recording here, ffmpeg makes it 16 kHz mono, and whisper-cli writes it down. Nothing is
 * kept: the files go when it's done. {@code GET /voice/status} says whether it can; {@code POST /voice/transcribe}
 * (the audio as the body) answers {text}. Behind the desk's sign-in and its same-origin check, like every route.
 */
public class VoiceRoutes {

    static final long MAX_BYTES = 25L * 1024 * 1024;
    static final Path MODELS = Path.of(System.getenv().getOrDefault("HOUSE_ELVES_DATA",
            System.getProperty("user.home") + "/.local/share/house-elves")).resolve("whisper");
    /** One at a time: Whisper takes every core it's given. */
    static final AtomicInteger BUSY = new AtomicInteger();

    void install(@Observes Router router) {
        router.get("/voice/status").blockingHandler(this::status);
        router.post("/voice/transcribe").handler(BodyHandler.create().setBodyLimit(MAX_BYTES).setDeleteUploadedFilesOnEnd(true))
                .blockingHandler(this::transcribe, false);
    }

    void status(RoutingContext rc) {
        String cli = whisper(), model = model();
        boolean ffmpeg = onPath("ffmpeg") != null;
        JsonObject out = new JsonObject().put("whisper", cli != null && model != null && ffmpeg);
        if (model != null) out.put("model", Path.of(model).getFileName().toString());
        if (cli == null || model == null || !ffmpeg) {
            List<String> how = new ArrayList<>();
            if (cli == null) how.add("install whisper.cpp (Fedora: sudo dnf install whisper-cpp; macOS: brew install whisper-cpp)");
            if (!ffmpeg) how.add("install ffmpeg");
            if (model == null) how.add("put a model in " + MODELS + " (like ggml-base.bin from huggingface.co/ggerganov/whisper.cpp)");
            out.put("how", String.join(", then ", how));
        }
        json(rc, 200, out);
    }

    void transcribe(RoutingContext rc) {
        String cli = whisper(), model = model();
        if (cli == null || model == null || onPath("ffmpeg") == null) {
            json(rc, 409, new JsonObject().put("error", "Whisper isn't set up on this machine"));
            return;
        }
        if (rc.body() == null || rc.body().length() == 0) {
            json(rc, 400, new JsonObject().put("error", "No recording"));
            return;
        }
        if (BUSY.getAndIncrement() > 0) {
            BUSY.decrementAndGet();
            json(rc, 429, new JsonObject().put("error", "Whisper is busy with another recording: try again in a moment"));
            return;
        }
        Path dir = null;
        try {
            dir = Files.createTempDirectory("desk-voice-");
            Path in = dir.resolve("in"), wav = dir.resolve("voice.wav");
            Files.write(in, rc.body().buffer().getBytes());
            run(List.of(onPath("ffmpeg"), "-hide_banner", "-loglevel", "error", "-i", in.toString(), "-ar", "16000", "-ac", "1",
                    "-c:a", "pcm_s16le", wav.toString()), dir, 60);
            int threads = Math.max(1, Math.min(8, Runtime.getRuntime().availableProcessors() - 1));
            run(List.of(cli, "-m", model, "-f", wav.toString(), "-l", "auto", "-nt", "-np", "-t", String.valueOf(threads),
                    "-otxt", "-of", dir.resolve("voice").toString()), dir, 180);
            String text = Files.readString(dir.resolve("voice.txt"), StandardCharsets.UTF_8).replaceAll("\\s+", " ").strip();
            // Whisper's way of saying it heard nothing.
            if (text.matches("(?i)\\[?\\(?(blank_audio|silence|music)\\]?\\)?")) text = "";
            json(rc, 200, new JsonObject().put("text", text));
        } catch (IOException | RuntimeException e) {
            json(rc, 500, new JsonObject().put("error", "Whisper couldn't write it down: " + e.getMessage()));
        } finally {
            BUSY.decrementAndGet();
            if (dir != null) delete(dir);
        }
    }

    static void run(List<String> cmd, Path dir, int seconds) throws IOException {
        Path log = dir.resolve("log");
        ProcessBuilder pb = new ProcessBuilder(cmd).directory(dir.toFile()).redirectErrorStream(true).redirectOutput(log.toFile());
        try {
            Process p = pb.start();
            if (!p.waitFor(seconds, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                throw new IOException(Path.of(cmd.get(0)).getFileName() + " took too long");
            }
            if (p.exitValue() != 0) {
                String out = Files.readString(log).strip();
                throw new IOException(out.length() > 300 ? out.substring(out.length() - 300) : out);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted");
        }
    }

    /** whisper.cpp's command line, by the names its packages give it. */
    static String whisper() {
        for (String name : List.of("whisper-cli", "whisper-cpp", "whisper.cpp")) {
            String p = onPath(name);
            if (p != null) return p;
        }
        return null;
    }

    /** The model in ~/.local/share/house-elves/whisper (or WHISPER_MODEL): the biggest one there, the most accurate. */
    static String model() {
        String given = System.getenv("WHISPER_MODEL");
        if (given != null && Files.isRegularFile(Path.of(given))) return given;
        if (!Files.isDirectory(MODELS)) return null;
        try (var files = Files.list(MODELS)) {
            return files.filter(f -> f.getFileName().toString().matches("ggml-.*\\.bin") && Files.isRegularFile(f))
                    .max(Comparator.comparingLong(f -> f.toFile().length())).map(Path::toString).orElse(null);
        } catch (IOException e) {
            return null;
        }
    }

    static String onPath(String name) {
        for (String d : System.getenv().getOrDefault("PATH", "/usr/bin:/bin").split(java.io.File.pathSeparator)) {
            Path p = Path.of(d, name);
            if (Files.isExecutable(p)) return p.toString();
        }
        for (String d : List.of("/usr/bin", "/usr/local/bin", "/opt/homebrew/bin")) {
            Path p = Path.of(d, name);
            if (Files.isExecutable(p)) return p.toString();
        }
        return null;
    }

    static void json(RoutingContext rc, int status, JsonObject body) {
        rc.response().setStatusCode(status).putHeader("Content-Type", "application/json").putHeader("Cache-Control", "no-store")
                .end(body.encode());
    }

    static void delete(Path dir) {
        try (var all = Files.walk(dir)) {
            all.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
        } catch (IOException ignored) {
            // the temp dir is cleaned by the system eventually
        }
    }
}
