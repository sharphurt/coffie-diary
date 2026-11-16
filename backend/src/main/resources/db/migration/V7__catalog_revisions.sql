create table catalog_revision (
  id uuid primary key,
  product_id uuid not null references catalog_product(id),
  snapshot jsonb not null,
  observed_from timestamptz not null default now(),
  unique (product_id, id)
);
alter table catalog_product add column current_revision_id uuid;
alter table catalog_product add foreign key (id,current_revision_id) references catalog_revision(product_id,id);

insert into catalog_revision(id,product_id,snapshot,observed_from)
select gen_random_uuid(),id,jsonb_build_object(
  'name',name,'type',product_type,'price',price,'currency',currency,'availability',availability,
  'offers',coalesce((select jsonb_agg(offer order by offer->>'variantId' collate "C")
    from jsonb_array_elements(offers) offer),'[]'::jsonb)),last_seen_at
from catalog_product;

update catalog_product p set current_revision_id=r.id from catalog_revision r where r.product_id=p.id;
