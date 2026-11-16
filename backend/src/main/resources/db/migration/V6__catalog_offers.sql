alter table catalog_product add column offers jsonb not null default '[]'::jsonb;
