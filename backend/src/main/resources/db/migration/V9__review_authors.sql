alter table external_review add column author_hash text;
alter table external_review add column author_ambiguous boolean not null default false;
