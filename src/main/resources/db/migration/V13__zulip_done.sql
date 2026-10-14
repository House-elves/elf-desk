-- Zulip topics you marked Done on the Zulip tab: their messages were marked read. A topic with newer messages
-- than "done" shows again.
create table zulip_done (
    server  text        not null,
    stream  text        not null,
    topic   text        not null,
    done    timestamptz not null default now(),
    marked  integer     not null default 0,
    primary key (server, stream, topic)
);
