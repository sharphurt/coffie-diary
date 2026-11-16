package ru.coffeediary;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
  private final ObjectMapper json = new ObjectMapper();

  DiscoveryService(JdbcTemplate db,
      @Value("${recommendations.fresh-hours:48}") int freshHours,
      @Value("${recommendations.max-age-hours:168}") int maxAgeHours) {
    this.db = db;
    this.freshHours = freshHours;
    this.maxAgeHours = maxAgeHours;
  }

  record Result(String mode, List<Map<String, Object>> items, List<Map<String, Object>> needsCheck,
      List<Map<String, Object>> personalItems, boolean hasRatedFolkLot) {}

  Result recommend(UUID owner, boolean includeUnknownRoast, boolean includeUnverified, BigDecimal budget, String roast) {
    if (budget != null && budget.signum() < 0)
      throw new Rules.Invalid("budget", "Бюджет должен быть неотрицательным");
    if (!Set.of("FILTER", "ESPRESSO").contains(roast))
      throw new Rules.Invalid("roast", "Выберите фильтр или эспрессо");

    var rows = db.queryForList("""
        with candidates as (
          select p.id,p.name,p.url,p.source_code,p.roast,o.weight_g,o.price,o.currency,o.price_per_250g,
            p.producer_descriptors->>'rawText' producer_text,
            p.producer_descriptors::text descriptor_json,p.processing::text processing_json,
            p.last_seen_at < now() - (? * interval '1 hour') needs_refresh,
            o.availability_unknown,o.price_unknown,
            md5(u.id::text || (now() at time zone u.timezone)::date::text || p.id::text) daily_order,
            p.processing::text || coalesce(p.producer_descriptors #>> '{descriptors,0,group}', '') descriptor_group
          from app_user u
          join catalog_product p on p.source_code='FOLK' and p.product_type='BEANS'
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
                or ?::numeric is null or o.price * 250 <= ? * o."weightG")
            order by availability_unknown, price_unknown,
              o."pricePer250g" nulls last,o."variantId"
            limit 1
          ) o
          where u.id=? and p.last_seen_at >= now() - (? * interval '1 hour')
            and (p.roast in (?, 'OMNI') or (? and p.roast is null))
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
        )
        select id,name,url,source_code,roast,weight_g,price,currency,price_per_250g,producer_text,descriptor_json,processing_json,
          needs_refresh,availability_unknown,price_unknown,unverified
        from grouped
        order by unverified,needs_refresh,descriptor_position,daily_order,id
        """, freshHours, budget, budget, budget, owner, maxAgeHours, roast, includeUnknownRoast);
    var anchors = anchors(owner);
    var ranked = rows.stream().filter(row -> !anchors.isEmpty() && Boolean.FALSE.equals(row.get("price_unknown")))
        .flatMap(row -> FolkRecommendation.best(features(row), anchors).stream().map(match -> new Ranked(row, match)))
        .sorted(Comparator.comparing((Ranked item) -> Boolean.TRUE.equals(item.row().get("availability_unknown")))
            .thenComparing(item -> Boolean.TRUE.equals(item.row().get("needs_refresh")))
            .thenComparing(Comparator.comparingDouble((Ranked item) -> item.match().score()).reversed())
            .thenComparing(item -> !Objects.equals(item.row().get("weight_g"), 250))
            .thenComparing(item -> (BigDecimal) item.row().get("price_per_250g"),
                Comparator.nullsLast(Comparator.naturalOrder()))
            .thenComparing(item -> (UUID) item.row().get("id")))
        .limit(3).toList();
    var personal = ranked.stream().map(item -> {
      var card = card(item.row());
      card.put("folk_reason", item.match().reason());
      return card;
    }).toList();
    if (!personal.isEmpty()) return new Result("PERSONAL", List.of(), List.of(), personal, true);
    var discovery = rows.stream().filter(row -> Boolean.FALSE.equals(row.get("unverified")))
        .limit(3).map(this::card).toList();
    return new Result("DISCOVERY", discovery,
        includeUnverified ? rows.stream().filter(row -> Boolean.TRUE.equals(row.get("unverified")))
            .limit(3 - discovery.size()).map(this::card).toList() : List.of(),
        List.of(), !anchors.isEmpty());
  }

  private record Ranked(Map<String, Object> row, FolkRecommendation.Match match) {}

  private Map<String, Object> card(Map<String, Object> row) {
    var result = new LinkedHashMap<>(row);
    result.remove("descriptor_json");
    result.remove("processing_json");
    return result;
  }

  private List<TasteRanking.Anchor> anchors(UUID owner) {
    var rows = db.queryForList("""
        select b.id bag_id,b.final_rating,l.id lot_id,l.name lot_name,p.id product_id,p.url,
          p.producer_descriptors::text descriptor_json,p.processing::text processing_json,
          coalesce(t.cups,'[]'::jsonb)::text cups
        from coffee_bag b join personal_lot l on l.id=b.lot_id and l.owner_id=b.owner_id
        join catalog_product p on p.id=l.catalog_product_id
        cross join lateral (
          select count(*) total, jsonb_agg(jsonb_build_object(
            'rating',overall_rating,'uncertain',preparation_uncertain,'sensory',sensory,
            'methodKnown',brew_snapshot->>'method' is not null and brew_snapshot->>'milkSugar'='NONE'
          ) order by id) cups
          from tasting where bag_id=b.id and owner_id=b.owner_id and deleted_at is null
        ) t
        where b.owner_id=? and b.deleted_at is null and l.deleted_at is null
          and p.source_code='FOLK' and p.product_type='BEANS'
          and (t.cups is not null or (t.total=0 and b.final_rating is not null))
        order by p.id,b.created_at desc,b.id
        """, owner);
    var groups = new LinkedHashMap<UUID, List<Map<String, Object>>>();
    for (var row : rows) groups.computeIfAbsent((UUID) row.get("product_id"), key -> new ArrayList<>()).add(row);
    return groups.entrySet().stream().flatMap(entry -> {
      var bags = entry.getValue();
      var product = bags.getFirst();
      return TasteRanking.anchor(entry.getKey(), (String) product.get("url"), features(product),
          bags.stream().map(this::purchase).toList()).stream();
    }).toList();
  }

  private TasteRanking.Purchase purchase(Map<String, Object> row) {
    var cups = new ArrayList<TasteRanking.Cup>();
    parse(row.get("cups")).forEach(cup -> {
      var sensory = new LinkedHashMap<String, Double>();
      for (String key : TasteRanking.DIMENSIONS) {
        var entry = cup.path("sensory").path(key);
        if ("RATED".equals(entry.path("status").asText()) && entry.path("value").isNumber())
          sensory.put(key, entry.path("value").doubleValue());
      }
      cups.add(new TasteRanking.Cup(cup.path("rating").doubleValue(), cup.path("uncertain").asBoolean(),
          cup.path("methodKnown").asBoolean(), sensory));
    });
    var rating = (Number) row.get("final_rating");
    return new TasteRanking.Purchase((UUID) row.get("bag_id"), (UUID) row.get("lot_id"),
        (String) row.get("lot_name"), rating == null ? null : rating.doubleValue(), cups);
  }

  private TasteRanking.Features features(Map<String, Object> row) {
    return TasteRanking.Features.from(parse(row.get("descriptor_json")), parse(row.get("processing_json")), Map.of());
  }

  private JsonNode parse(Object value) {
    try {
      return value == null ? json.nullNode() : json.readTree(value.toString());
    } catch (java.io.IOException e) {
      throw new IllegalStateException("Invalid stored recommendation features", e);
    }
  }
}
