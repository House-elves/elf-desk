package io.github.houseelves.desk.status;

import java.time.Instant;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;

/** Which drafts you sent to the team yourself. The desk keeps this; the elf's draft files are only the elf's. */
@ApplicationScoped
public class SentYourselfService {

    /** When you marked the draft sent to the team yourself, or null. */
    public Instant at(String draft) {
        SentYourself s = SentYourself.findById(draft);
        return s == null ? null : s.at;
    }

    @Transactional
    public void mark(String draft) {
        if (SentYourself.findById(draft) != null) return;
        SentYourself s = new SentYourself();
        s.draft = draft;
        s.persist();
    }

    @Transactional
    public void unmark(String draft) {
        SentYourself.deleteById(draft);
    }
}
