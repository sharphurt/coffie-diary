alter table tasting add column wishes jsonb not null default '[]'::jsonb;
