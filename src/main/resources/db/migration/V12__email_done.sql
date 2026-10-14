-- Emails you marked Done on the Email tab: their Gmail thread was marked read, and the triage lists them as done.
create table email_done (
    account    text        not null,
    message_id text        not null,
    done       timestamptz not null default now(),
    marked     integer     not null default 0,
    primary key (account, message_id)
);
