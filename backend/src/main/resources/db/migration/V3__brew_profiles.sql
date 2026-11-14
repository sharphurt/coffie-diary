alter table brew_profile add unique (id, owner_id);
alter table app_user add column default_brew_profile_id uuid;
alter table app_user add foreign key (default_brew_profile_id,id) references brew_profile(id,owner_id);
update app_user u set default_brew_profile_id =
  (select p.id from brew_profile p where p.owner_id=u.id and p.deleted_at is null order by p.id limit 1);
alter table brew_profile add column grinder text;
alter table brew_profile add column brewer text;
update brew_profile set grinder='MHW-3BOMBER Race M1', brewer='Kitfort КТ-7570'
 where name='Race M1 + Kitfort КТ-7570';
