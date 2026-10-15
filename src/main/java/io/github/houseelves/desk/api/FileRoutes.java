package io.github.houseelves.desk.api;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import io.github.houseelves.desk.thread.Job;
import io.github.houseelves.desk.thread.JobFile;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.vertx.core.buffer.Buffer;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import jakarta.enterprise.event.Observes;

/**
 * The agent's files, for the thread page: {@code /files/<id>} (an image shows inline, anything else downloads) and
 * {@code /files/job/<job>.zip} (all of a job's files). Behind the desk's sign-in like every page. Nothing here is
 * ever rendered as a page: text is served as a download, with no sniffing and a sandboxing CSP.
 */
public class FileRoutes {

    void install(@Observes Router router) {
        router.get("/files/job/:job").blockingHandler(this::zip);
        router.get("/files/:id").blockingHandler(this::file);
    }

    void file(RoutingContext rc) {
        long id = number(rc.pathParam("id"));
        // Read inside the transaction: the data is loaded lazily.
        Loaded f = id < 0 ? null : QuarkusTransaction.requiringNew().call(() -> {
            JobFile found = JobFile.findById(id);
            return found == null ? null : new Loaded(found.name, found.contentType, found.image(), found.data);
        });
        if (f == null) {
            rc.response().setStatusCode(404).end("No such file");
            return;
        }
        safe(rc).putHeader("Content-Type", f.type())
                .putHeader("Content-Disposition", (f.image() ? "inline" : "attachment") + "; filename=\"" + f.name() + "\"")
                .end(Buffer.buffer(f.data()));
    }

    record Loaded(String name, String type, boolean image, byte[] data) {
    }

    void zip(RoutingContext rc) {
        String last = rc.pathParam("job");
        long job = number(last.endsWith(".zip") ? last.substring(0, last.length() - 4) : last);
        byte[] zip = job < 0 ? null : QuarkusTransaction.requiringNew().call(() -> {
            if (Job.findById(job) == null) return null;
            List<JobFile> files = JobFile.list("jobId = ?1 order by name", job);
            if (files.isEmpty()) return null;
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            try (ZipOutputStream z = new ZipOutputStream(out)) {
                for (JobFile f : files) {
                    z.putNextEntry(new ZipEntry(f.name));
                    z.write(f.data);
                    z.closeEntry();
                }
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
            return out.toByteArray();
        });
        if (zip == null) {
            rc.response().setStatusCode(404).end("No files for that job");
            return;
        }
        safe(rc).putHeader("Content-Type", "application/zip")
                .putHeader("Content-Disposition", "attachment; filename=\"agent-files-" + job + ".zip\"")
                .end(Buffer.buffer(zip));
    }

    private static io.vertx.core.http.HttpServerResponse safe(RoutingContext rc) {
        return rc.response().putHeader("X-Content-Type-Options", "nosniff")
                .putHeader("Content-Security-Policy", "default-src 'none'; sandbox")
                .putHeader("Cache-Control", "private, max-age=86400");
    }

    private static long number(String s) {
        try {
            return s != null && s.matches("\\d{1,18}") ? Long.parseLong(s) : -1;
        } catch (NumberFormatException e) {
            return -1;
        }
    }
}
