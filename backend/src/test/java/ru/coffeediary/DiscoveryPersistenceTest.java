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
}
