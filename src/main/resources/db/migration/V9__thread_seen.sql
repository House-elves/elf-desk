-- When the thread page last showed the thread: an agent answer after that is new ("Answer ready" on the board).
-- Existing threads count as seen, so only answers from now on show up.
alter table thread add column seen timestamptz;
update thread set seen = now();
