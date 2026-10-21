///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 21+
//DEPS org.postgresql:postgresql:42.7.13
//DEPS com.h2database:h2:2.4.240

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.util.ArrayList;
import java.util.List;
import java.util.StringJoiner;

/**
 * One-off: copies a desk's data from its old Postgres database into the new H2 file. Start the new desk once
 * first (that creates the schema), stop it, then run this with the old database's URL, user and password and the
 * new database file (without the .mv.db). Refuses if the new database already has threads.
 *
 * jbang tools/PostgresToH2.java jdbc:postgresql://localhost:5439/elfdesk elfdesk "$PASSWORD" ~/.local/share/house-elves/desk
 */
public class PostgresToH2 {

    /** Parents before children, for the foreign keys. */
    static final List<String> TABLES = List.of("watch_scope", "setting", "thread", "job", "turn", "draft",
            "github_action", "merge_watch", "ignored_item", "email_done", "zulip_done", "status_sent_yourself",
            "job_file");

    /** Tables whose id is an identity column: they continue after the highest copied id. */
    static final List<String> IDENTITY = List.of("watch_scope", "thread", "job", "turn", "draft", "github_action",
            "merge_watch", "job_file");

    public static void main(String... args) throws Exception {
        if (args.length != 4) {
            System.err.println("Usage: PostgresToH2 <postgres-url> <user> <password> <h2-file>");
            System.exit(2);
        }
        try (Connection pg = DriverManager.getConnection(args[0], args[1], args[2]);
                Connection h2 = DriverManager.getConnection("jdbc:h2:file:" + args[3] + ";IFEXISTS=TRUE", "desk", "")) {
            try (ResultSet r = h2.createStatement().executeQuery("select count(*) from thread")) {
                r.next();
                if (r.getLong(1) > 0) throw new IllegalStateException("The new database already has threads: not copying over them");
            }
            h2.setAutoCommit(false);
            for (String table : TABLES) {
                h2.createStatement().execute("delete from " + table);
            }
            for (String table : TABLES) {
                int n = copy(pg, h2, table);
                System.out.printf("%-22s %6d rows%n", table, n);
            }
            for (String table : IDENTITY) {
                try (ResultSet r = h2.createStatement().executeQuery("select coalesce(max(id), 0) + 1 from " + table)) {
                    r.next();
                    h2.createStatement().execute("alter table " + table + " alter column id restart with " + r.getLong(1));
                }
            }
            h2.commit();
            System.out.println("Done.");
        }
    }

    static int copy(Connection pg, Connection h2, String table) throws Exception {
        try (ResultSet r = pg.createStatement().executeQuery("select * from " + table + " order by 1")) {
            ResultSetMetaData m = r.getMetaData();
            List<String> cols = new ArrayList<>();
            for (int i = 1; i <= m.getColumnCount(); i++) cols.add(m.getColumnName(i));
            StringJoiner marks = new StringJoiner(", ");
            cols.forEach(c -> marks.add("?"));
            var insert = h2.prepareStatement("insert into " + table + " (" + String.join(", ", cols) + ") values (" + marks + ")");
            int n = 0;
            while (r.next()) {
                for (int i = 1; i <= cols.size(); i++) insert.setObject(i, r.getObject(i));
                insert.addBatch();
                if (++n % 200 == 0) insert.executeBatch();
            }
            insert.executeBatch();
            return n;
        }
    }
}
