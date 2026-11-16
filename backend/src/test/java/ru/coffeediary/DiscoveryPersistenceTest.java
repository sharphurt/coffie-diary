package ru.coffeediary;

import static org.junit.jupiter.api.Assertions.*;

import java.math.BigDecimal;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

@EnabledIfEnvironmentVariable(named = "E2E_CATALOG_DATABASE", matches = "true")
class DiscoveryPersistenceTest {
  private final JdbcTemplate db = new JdbcTemplate(new DriverManagerDataSource(System.getenv("DATABASE_URL"),
      System.getenv("POSTGRES_USER"), System.getenv("POSTGRES_PASSWORD")));
  private final DiscoveryService service = new DiscoveryService(db, 48, 168);

  @Test
  void discoverySeparatesUnverifiedOffersAndUsesOnlyTheOwnersPurchases() {
    UUID owner = UUID.randomUUID(), other = UUID.randomUUID(), lot = UUID.randomUUID();
    db.update("insert into app_user(id,login,password_hash) values(?,?,'test'),(?,?,'test')",
        owner, owner.toString(), other, other.toString());
    var ids = new ArrayList<UUID>();
    try {
      UUID folk = product(ids, "FOLK", "BEANS", "InStock", 500, 0);
      UUID tasty = product(ids, "TASTY", "BEANS", "https://schema.org/InStock", 500, 60);
      UUID unknown = product(ids, "FOLK", "BEANS", null, 400, 0);
      UUID unknownPrice = product(ids, "TASTY", "BEANS", "InStock", null, 0);
      product(ids, "TASTY", "BEANS", "InStock", 700, 0);
      product(ids, "TASTY", "BEANS", "InStock", 100, 169);
      product(ids, "TASTY", "BEANS", "OutOfStock", 100, 0);
      product(ids, "FOLK", "DRIP", "InStock", 100, 0);
      db.update("insert into personal_lot(id,owner_id,name,catalog_product_id) values(?,?,'lot',?)", lot, other, folk);
      db.update("insert into coffee_bag(id,owner_id,lot_id,mass_g,status) values(?,?,?,250,'ACTIVE')",
          UUID.randomUUID(), other, lot);

      var budget = BigDecimal.valueOf(500);
      assertTrue(service.recommend(owner, false, true, budget).items().isEmpty());
      var first = service.recommend(owner, true, true, budget);
      assertEquals("DISCOVERY", first.mode());
      assertEquals(List.of(folk, tasty), first.items().stream().map(row -> row.get("id")).toList());
      assertEquals(true, first.items().getLast().get("needs_refresh"));
      assertEquals(Set.of(unknown, unknownPrice),
          new HashSet<>(first.needsCheck().stream().map(row -> row.get("id")).toList()));
      assertEquals(first, service.recommend(owner, true, true, budget));
      assertTrue(service.recommend(owner, true, false, budget).needsCheck().isEmpty());
      assertEquals(List.of(tasty),
          service.recommend(other, true, false, budget).items().stream().map(row -> row.get("id")).toList());
    } finally {
      db.update("delete from coffee_bag where lot_id=?", lot);
      db.update("delete from personal_lot where id=?", lot);
      for (UUID id : ids) db.update("delete from catalog_product where id=?", id);
      db.update("delete from app_user where id in (?,?)", owner, other);
    }
  }

  private UUID product(List<UUID> ids, String source, String type, String availability, Integer price, int hours) {
    UUID id = UUID.randomUUID();
    db.update("""
        insert into catalog_product(id,source_code,url,name,product_type,last_seen_at,offers)
        values(?, ?, ?, 'test', ?, now() - (? * interval '1 hour'),
          jsonb_build_array(jsonb_build_object('variantId','250','weightG',250,'price',?::numeric,
            'currency','RUB','pricePer250g',?::numeric,'availability',?::text)))
        """, id, source, "https://example.test/" + id, type, hours, price, price, availability);
    ids.add(id);
    return id;
  }

  @Test
  void extraCupsDoNotTurnOneBagIntoExtraInfluenceInTheRecommendation() {
    UUID owner = UUID.randomUUID(), lot = UUID.randomUUID();
    UUID firstBag = UUID.randomUUID(), secondBag = UUID.randomUUID();
    db.update("insert into app_user(id,login,password_hash) values(?,?,'test')", owner, owner.toString());
    var products = new ArrayList<UUID>();
    try {
      UUID known = product(products, "FOLK", "BEANS", "InStock", 400, 0);
      UUID candidate = product(products, "TASTY", "BEANS", "InStock", 400, 0);
      db.update("""
          update catalog_product set processing='["NATURAL"]',
            producer_descriptors='{"rawText":"Фрукты","descriptors":[{"group":"FRUITY"}]}'
          where id in (?,?)
          """, known, candidate);
      db.update("insert into personal_lot(id,owner_id,name,catalog_product_id) values(?,?,'rated lot',?)", lot, owner, known);
      for (UUID bag : List.of(firstBag, secondBag)) db.update(
          "insert into coffee_bag(id,owner_id,lot_id,mass_g,status) values(?,?,?,250,'FINISHED')", bag, owner, lot);
      tasting(owner, firstBag, 5);
      tasting(owner, secondBag, 1);
      var initial = service.recommend(owner, true, false, null);
      for (int i = 0; i < 9; i++) tasting(owner, firstBag, 5);
      var anchors = service.anchors(owner);
      assertEquals(1, anchors.size());
      assertEquals(2, anchors.getFirst().purchases().size());
      assertEquals(3.0, anchors.getFirst().rating(), 1e-9);
      assertEquals("PERSONAL", initial.mode());
      assertEquals(candidate, initial.personalItems().getFirst().get("id"));
      var score = (TasteRanking.Score) initial.personalItems().getFirst().get("recommendation");
      assertEquals(lot, score.reasons().getFirst().purchases().getFirst().lotId());
      assertEquals(initial, service.recommend(owner, true, false, null));
    } finally {
      db.update("delete from tasting where owner_id=?", owner);
      db.update("delete from coffee_bag where owner_id=?", owner);
      db.update("delete from personal_lot where owner_id=?", owner);
      for (UUID id : products) db.update("delete from catalog_product where id=?", id);
      db.update("delete from app_user where id=?", owner);
    }
  }

  private void tasting(UUID owner, UUID bag, int rating) {
    db.update("""
        insert into tasting(id,owner_id,bag_id,tasted_at,overall_rating,sensory,brew_snapshot)
        values(?,?,?,now(),?,'{}','{"method":"POUR_OVER","milkSugar":"NONE"}')
        """, UUID.randomUUID(), owner, bag, rating);
  }
}
