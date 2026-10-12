-- Items you're involved in but someone else is handling: shown in a collapsed "Ignored" section.
create table ignored_item (
    item_key varchar(300) primary key,
    login    varchar(100) not null,
    ignored  timestamptz  not null default now()
);
