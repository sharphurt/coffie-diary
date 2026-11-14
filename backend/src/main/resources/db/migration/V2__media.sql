alter table media_asset add column position integer not null default 0;
create index media_bag_order on media_asset(owner_id, bag_id, position);
create index media_pending_age on media_asset(created_at) where bag_id is null;
