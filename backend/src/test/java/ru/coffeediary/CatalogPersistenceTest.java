package ru.coffeediary;

import static org.junit.jupiter.api.Assertions.*;
import java.io.IOException;
import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.Callable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

@EnabledIfEnvironmentVariable(named = "E2E_CATALOG_DATABASE", matches = "true")
class CatalogPersistenceTest {
  @Test
  void reviewImportDeduplicatesAndPreservesLastSuccessfulTexts() {
    var datasource = new DriverManagerDataSource(System.getenv("DATABASE_URL"),
        System.getenv("POSTGRES_USER"), System.getenv("POSTGRES_PASSWORD"));
    var db = new JdbcTemplate(datasource);
    var service = new CatalogService(db, new TransactionTemplate(new DataSourceTransactionManager(datasource)),
        Runnable::run);
    UUID id = UUID.randomUUID();
    String url = "https://shop.tastycoffee.ru/coffee/test-" + id;
    db.update("insert into catalog_product(id,source_code,url,name) values(?,'TASTY',?,?)",
        id, url, "Синтетический тест отзывов");
    try {
      assertEquals(Map.of("status", "UNKNOWN", "items", List.of()), service.reviews(id));
      Object first = service.importReviews(id, new CatalogService.ReviewImport(url, List.of("Тестовый  отзыв")));
      assertEquals(first, service.importReviews(id, new CatalogService.ReviewImport(url, List.of("Тестовый отзыв"))));
      assertEquals(1, db.queryForObject("select count(*) from external_review where product_id=?",
          Integer.class, id));
      assertThrows(Rules.Invalid.class, () -> service.importReviews(id,
          new CatalogService.ReviewImport("https://shop.tastycoffee.ru/coffee/another", List.of("Другой текст"))));
      var unavailable = (Map<?, ?>) service.importReviews(id, new CatalogService.ReviewImport(url, List.of()));
      assertEquals("UNAVAILABLE", unavailable.get("status"));
      assertEquals(((Map<?, ?>) first).get("items"), unavailable.get("items"));
    } finally {
      db.update("delete from external_review where product_id=?", id);
      db.update("delete from catalog_product where id=?", id);
    }
  }

  @Test
  void repeatChangeAndFailedRunsPreserveCatalogAndImmutableSnapshots() {
    var datasource = new DriverManagerDataSource(System.getenv("DATABASE_URL"),
        System.getenv("POSTGRES_USER"), System.getenv("POSTGRES_PASSWORD"));
    var db = new JdbcTemplate(datasource);
    var service = new CatalogService(db, new TransactionTemplate(new DataSourceTransactionManager(datasource)),
        Runnable::run);
    String source = "TEST_" + UUID.randomUUID();
    var small = new CatalogAdapters.Offer("250", 250, BigDecimal.TEN, "RUB", "InStock", BigDecimal.TEN, "10");
    var large = new CatalogAdapters.Offer("1000", 1000, BigDecimal.valueOf(40), "RUB", "InStock", BigDecimal.TEN, "40");
    var initial = product("one", "Первый", List.of(small, large));
    var other = product("two", "Второй", List.of());
    assertEquals("PARTIAL", run(db, service, source, () -> List.of(initial, other)));
    var first = productRow(db, source, initial.url());
    var firstRevision = first.get("current_revision_id");
    assertNotNull(firstRevision);
    var originalSnapshot = db.queryForObject("select snapshot::text from catalog_revision where id=?",
        String.class, firstRevision);

    assertEquals("PARTIAL", run(db, service, source, () -> List.of(
        product("one", "Первый", List.of(large, small)), other)));
    assertEquals(first.get("id"), productRow(db, source, initial.url()).get("id"));
    assertEquals(firstRevision, productRow(db, source, initial.url()).get("current_revision_id"));
    assertEquals(2, count(db, source));

    assertEquals("PARTIAL", run(db, service, source, () -> List.of(product("one", "Новое название", List.of(large)))));
    var changed = productRow(db, source, initial.url());
    assertEquals(first.get("id"), changed.get("id"));
    assertNotEquals(firstRevision, changed.get("current_revision_id"));
    assertEquals(originalSnapshot, db.queryForObject("select snapshot::text from catalog_revision where id=?",
        String.class, firstRevision));
    assertEquals(3, count(db, source));
    var lastGood = db.queryForList("select * from catalog_product where source_code=? order by url", source);
    assertEquals(2, lastGood.size());
    assertEquals("InStock", lastGood.getLast().get("availability"));

    assertEquals("FAILED", run(db, service, source, List::of));
    assertEquals("FAILED", run(db, service, source, () -> { throw new IOException("source unavailable"); }));
    assertEquals("FAILED", run(db, service, source, () ->
        List.of(product("one", "Откатить это изменение", List.of()), product("invalid", null, List.of()))));
    assertEquals(lastGood, db.queryForList("select * from catalog_product where source_code=? order by url", source));
    assertEquals(3, count(db, source));
  }

  private static CatalogAdapters.Product product(String slug, String name, List<CatalogAdapters.Offer> offers) {
    return new CatalogAdapters.Product("https://shop.tastycoffee.ru/coffee/" + slug,
        name, "BEANS", null, "RUB", "InStock", offers);
  }

  private static Map<String, Object> productRow(JdbcTemplate db, String source, String url) {
    return db.queryForMap("select * from catalog_product where source_code=? and url=?", source, url);
  }

  private static int count(JdbcTemplate db, String source) {
    return db.queryForObject("select count(*) from catalog_revision r join catalog_product p"
        + " on p.id=r.product_id where p.source_code=?", Integer.class, source);
  }

  private static String run(JdbcTemplate db, CatalogService service, String source,
      Callable<List<CatalogAdapters.Product>> fetch) {
    UUID run = UUID.randomUUID();
    db.update("insert into catalog_import_run(id,source_code,status) values(?,?,'RUNNING')", run, source);
    service.complete(run, source, fetch);
    return db.queryForObject("select status from catalog_import_run where id=?", String.class, run);
  }
}
