alter table catalog_product add column roast text
  check (roast in ('FILTER', 'OMNI', 'ESPRESSO'));
