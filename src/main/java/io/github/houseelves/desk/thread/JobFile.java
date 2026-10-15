package io.github.houseelves.desk.thread;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Basic;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** A file the agent left for you (a screenshot, a log). See V15. */
@Entity
@Table(name = "job_file")
public class JobFile extends PanacheEntityBase {

    /** A plain name: no path, no leading dot. */
    static final Pattern NAME = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,99}");
    /** The types kept, by extension. Images show inline; the rest only download (never rendered by the desk). */
    static final Map<String, String> TYPES = Map.ofEntries(Map.entry("png", "image/png"), Map.entry("jpg", "image/jpeg"),
            Map.entry("jpeg", "image/jpeg"), Map.entry("gif", "image/gif"), Map.entry("webp", "image/webp"),
            Map.entry("txt", "text/plain"), Map.entry("md", "text/plain"), Map.entry("log", "text/plain"),
            Map.entry("csv", "text/plain"), Map.entry("json", "text/plain"), Map.entry("diff", "text/plain"),
            Map.entry("patch", "text/plain"));
    static final int MAX_SIZE = 10 * 1024 * 1024, MAX_PER_JOB = 20;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    @Column(name = "job_id", nullable = false)
    public Long jobId;

    @Column(nullable = false)
    public String name;

    @Column(name = "content_type", nullable = false)
    public String contentType;

    @Column(nullable = false)
    public int size;

    @Basic(fetch = FetchType.LAZY)
    @Column(nullable = false)
    public byte[] data;

    @Column(nullable = false)
    public Instant created = Instant.now();

    /** The type for this name, or null when it isn't one kept. */
    public static String typeOf(String name) {
        if (name == null || !NAME.matcher(name).matches() || !name.contains(".")) return null;
        return TYPES.get(name.substring(name.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT));
    }

    public boolean image() {
        return contentType.startsWith("image/");
    }

    /** What a thread shows of a job's files: no data. */
    public record Info(long id, String name, String type, int size) {
    }

    public static List<Info> infos(long jobId) {
        return getEntityManager().createQuery("select new " + Info.class.getName()
                + "(f.id, f.name, f.contentType, f.size) from JobFile f where f.jobId = ?1 order by f.name", Info.class)
                .setParameter(1, jobId).getResultList();
    }
}
