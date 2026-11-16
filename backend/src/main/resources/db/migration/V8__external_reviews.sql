alter table catalog_product add column reviews_status text not null default 'UNKNOWN';
create table external_review (
  product_id uuid not null references catalog_product(id),
  content_hash text not null,
  body text not null,
  source_url text not null,
  observed_at timestamptz not null default now(),
  primary key (product_id, content_hash)
);
