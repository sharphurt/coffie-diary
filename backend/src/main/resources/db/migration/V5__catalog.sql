create table catalog_product (
  id uuid primary key,
  source_code text not null,
  url text not null,
  name text not null,
  product_type text,
  price numeric,
  currency text,
  availability text,
  last_seen_at timestamptz not null default now(),
  unique (source_code,url)
);
create table catalog_import_run (
  id uuid primary key,
  source_code text not null,
  status text not null,
  started_at timestamptz not null default now(),
  finished_at timestamptz,
  product_count integer not null default 0
);
alter table personal_lot add column catalog_product_id uuid references catalog_product;
