-- Weekly status drafts you sent to the team yourself (from your own mail client): "I sent it myself" on the
-- Status tab. The draft counts as sent to the team; nothing was emailed by the elf.
create table status_sent_yourself (
    draft   text        primary key,
    at      timestamptz not null default now()
);
