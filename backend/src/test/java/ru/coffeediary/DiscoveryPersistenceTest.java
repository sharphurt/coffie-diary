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
      UUID second = product(ids, "FOLK", "BEANS", "InStock", 500, 60);
      UUID unknown = product(ids, "FOLK", "BEANS", null, 400, 0);
      UUID unknownPrice = product(ids, "FOLK", "BEANS", "InStock", null, 0);
      product(ids, "FOLK", "BEANS", "InStock", 700, 0);
      product(ids, "FOLK", "BEANS", "InStock", 100, 169);
      product(ids, "FOLK", "BEANS", "OutOfStock", 100, 0);
      product(ids, "TASTY", "BEANS", "InStock", 100, 0);
      product(ids, "FOLK", "DRIP", "InStock", 100, 0);
      UUID espresso = product(ids, "FOLK", "BEANS", "InStock", 450, 0);
      db.update("update catalog_product set roast='ESPRESSO' where id=?", espresso);
      db.update("insert into personal_lot(id,owner_id,name,catalog_product_id) values(?,?,'lot',?)", lot, other, folk);
      db.update("insert into coffee_bag(id,owner_id,lot_id,mass_g,status) values(?,?,?,250,'ACTIVE')",
          UUID.randomUUID(), other, lot);

      var budget = BigDecimal.valueOf(500);
      var first = service.recommend(owner, false, true, budget, "FILTER");
      assertEquals("DISCOVERY", first.mode());
      assertEquals(List.of(folk, second), first.items().stream().map(row -> row.get("id")).toList());
      assertEquals(true, first.items().getLast().get("needs_refresh"));
      assertEquals(1, first.needsCheck().size());
      assertTrue(Set.of(unknown, unknownPrice).contains(first.needsCheck().getFirst().get("id")));
      assertEquals(first, service.recommend(owner, false, true, budget, "FILTER"));
      assertTrue(service.recommend(owner, true, false, budget, "FILTER").needsCheck().isEmpty());
      assertEquals(List.of(second),
          service.recommend(other, false, false, budget, "FILTER").items().stream().map(row -> row.get("id")).toList());
      assertEquals(espresso, service.recommend(owner, false, false, budget, "ESPRESSO").items().getFirst().get("id"));
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
        insert into catalog_product(id,source_code,url,name,product_type,roast,last_seen_at,offers)
        values(?, ?, ?, 'test', ?, 'FILTER', now() - (? * interval '1 hour'),
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
      UUID candidate = product(products, "FOLK", "BEANS", null, 400, 0);
      UUID contrast = product(products, "FOLK", "BEANS", "InStock", 400, 0);
      db.update("""
          update catalog_product set processing='["NATURAL"]',
            producer_descriptors='{"rawText":"Фрукты","descriptors":[{"group":"FRUITY"}]}'
          where id in (?,?)
          """, known, candidate);
      db.update("""
          update catalog_product set producer_descriptors='{"rawText":"Шоколад","descriptors":[{"group":"CHOCOLATE"}]}'
          where id=?
          """, contrast);
      db.update("insert into personal_lot(id,owner_id,name,catalog_product_id) values(?,?,'rated lot',?)", lot, owner, known);
      for (UUID bag : List.of(firstBag, secondBag)) db.update(
          "insert into coffee_bag(id,owner_id,lot_id,mass_g,status) values(?,?,?,250,'FINISHED')", bag, owner, lot);
      tasting(owner, firstBag, 5);
      tasting(owner, secondBag, 1);
      var initial = service.recommend(owner, false, true, null, "FILTER");
      for (int i = 0; i < 9; i++) tasting(owner, firstBag, 5);
      assertEquals("PERSONAL", initial.mode());
      assertEquals(candidate, initial.personalItems().getFirst().get("id"));
      assertEquals(true, initial.personalItems().getFirst().get("availability_unknown"));
      var reason = (FolkRecommendation.Reason) initial.personalItems().getFirst().get("folk_reason");
      assertEquals(3.0, reason.rating(), 1e-9);
      assertEquals(lot, reason.lotId());
      assertEquals("COMPARE", reason.direction());
      assertEquals(Set.of("FRUITY"), reason.sharedDescriptors());
      assertEquals(initial, service.recommend(owner, false, true, null, "FILTER"));

      review(known, "one", "Кофе не горчит");
      review(known, "two", "Совсем не горчит");
      review(known, "three", "Горчит");
      review(candidate, "one", "Кофе не горчит");
      review(candidate, "two", "Совсем не горчит");
      review(candidate, "three", "Горчит");
      assertEquals(initial, service.recommend(owner, false, true, null, "FILTER"), "FOLK uses producer data only");

      db.update("update coffee_bag set final_rating=5 where id in (?,?)", firstBag, secondBag);
      var liked = service.recommend(owner, false, true, null, "FILTER").personalItems().getFirst();
      assertEquals(candidate, liked.get("id"));
      assertEquals("SIMILAR", ((FolkRecommendation.Reason) liked.get("folk_reason")).direction());
      db.update("update coffee_bag set final_rating=1 where id in (?,?)", firstBag, secondBag);
      var disliked = service.recommend(owner, false, true, null, "FILTER").personalItems().getFirst();
      assertEquals(contrast, disliked.get("id"));
      var different = (FolkRecommendation.Reason) disliked.get("folk_reason");
      assertEquals("CONTRAST", different.direction());
      assertEquals(Set.of("CHOCOLATE"), different.candidateDescriptors());
      assertEquals(Set.of("FRUITY"), different.anchorDescriptors());
    } finally {
      db.update("delete from tasting where owner_id=?", owner);
      db.update("delete from coffee_bag where owner_id=?", owner);
      db.update("delete from personal_lot where owner_id=?", owner);
      for (UUID id : products) {
        db.update("delete from external_review where product_id=?", id);
        db.update("delete from catalog_product where id=?", id);
      }
      db.update("delete from app_user where id=?", owner);
    }
  }

  private void tasting(UUID owner, UUID bag, int rating) {
    db.update("""
        insert into tasting(id,owner_id,bag_id,tasted_at,overall_rating,sensory,brew_snapshot)
        values(?,?,?,now(),?,'{}','{"method":"POUR_OVER","milkSugar":"NONE"}')
        """, UUID.randomUUID(), owner, bag, rating);
  }

  private void review(UUID product, String author, String body) {
    db.update("""
        insert into external_review(product_id,content_hash,body,source_url,author_hash)
        values(?,?,?,?,?)
        """, product, author, body, "https://example.test/" + product, author);
  }
}
