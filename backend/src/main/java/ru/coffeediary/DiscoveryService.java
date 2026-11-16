package ru.coffeediary;

import java.math.BigDecimal;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
class DiscoveryService {
  private final JdbcTemplate db;
  private final int freshHours;
  private final int maxAgeHours;

  DiscoveryService(JdbcTemplate db,
      @Value("${recommendations.fresh-hours:48}") int freshHours,
      @Value("${recommendations.max-age-hours:168}") int maxAgeHours) {
    this.db = db;
    this.freshHours = freshHours;
    this.maxAgeHours = maxAgeHours;
  }

  record Result(String mode, List<Map<String, Object>> items, List<Map<String, Object>> needsCheck) {}

  Result recommend(UUID owner, boolean includeUnknownRoast, boolean includeUnverified, BigDecimal budget) {
    if (budget != null && budget.signum() < 0)
      throw new Rules.Invalid("budget", "Бюджет должен быть неотрицательным");
    if (!includeUnknownRoast) return new Result("DISCOVERY", List.of(), List.of());

    var rows = db.queryForList("""
        with candidates as (
          select p.id,p.name,p.url,p.source_code,o.weight_g,o.price,o.currency,o.price_per_250g,
            p.producer_descriptors->>'rawText' producer_text,
            p.last_seen_at < now() - (? * interval '1 hour') needs_refresh,
            o.availability_unknown,o.price_unknown,
            md5(u.id::text || (now() at time zone u.timezone)::date::text || p.id::text) daily_order,
            coalesce(p.producer_descriptors #>> '{descriptors,0,group}', '') descriptor_group
          from app_user u
          join catalog_product p on p.source_code in ('FOLK','TASTY') and p.product_type='BEANS'
          cross join lateral (
            select o."weightG" weight_g,o.price,o.currency,o."pricePer250g" price_per_250g,
              coalesce(regexp_replace(o.availability,'^https?://schema[.]org/',''),'Unknown') <> 'InStock'
                availability_unknown,
              (?::numeric is not null and (o."pricePer250g" is null or o.currency is distinct from 'RUB'))
                price_unknown
            from jsonb_to_recordset(p.offers) as o("variantId" text,"weightG" int,price numeric,
              currency text,"pricePer250g" numeric,availability text)
            where coalesce(regexp_replace(o.availability,'^https?://schema[.]org/',''),'Unknown')
                in ('InStock','Unknown')
              and (o."pricePer250g" is null or o.currency is distinct from 'RUB'
                or ?::numeric is null or o."pricePer250g" <= ?)
            order by availability_unknown, price_unknown,
              o."pricePer250g" nulls last,o."variantId"
            limit 1
          ) o
          where u.id=? and p.last_seen_at >= now() - (? * interval '1 hour')
            and not exists (
              select 1 from personal_lot l join coffee_bag b on b.lot_id=l.id and b.owner_id=l.owner_id
              where l.owner_id=u.id and l.catalog_product_id=p.id and l.deleted_at is null
                and b.deleted_at is null
            )
        ), grouped as (
          select *, availability_unknown or price_unknown as unverified,
            row_number() over (partition by availability_unknown or price_unknown,needs_refresh,
              descriptor_group order by daily_order,id) descriptor_position
          from candidates
        ), ordered as (
          select *, row_number() over (partition by unverified,needs_refresh,source_code
            order by descriptor_position,daily_order,id) source_position
          from grouped
        )
        select id,name,url,source_code,weight_g,price,currency,price_per_250g,producer_text,
          needs_refresh,availability_unknown,price_unknown,unverified
        from ordered where not unverified or ?
        order by unverified,needs_refresh,source_position,descriptor_position,daily_order,id
        """, freshHours, budget, budget, budget, owner, maxAgeHours, includeUnverified);
    return new Result("DISCOVERY", group(rows, false), group(rows, true));
  }

  private List<Map<String, Object>> group(List<Map<String, Object>> rows, boolean unverified) {
    return rows.stream().filter(row -> Objects.equals(row.get("unverified"), unverified)).limit(10).toList();
  }
}
