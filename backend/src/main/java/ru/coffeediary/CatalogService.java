package ru.coffeediary;

import java.util.*;
import java.util.concurrent.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

@Service
class CatalogService {
  private final JdbcTemplate db;
  private final TransactionTemplate transaction;
  private final Executor executor;
  private final CatalogAdapters adapters = new CatalogAdapters();
  private final ObjectMapper json = new ObjectMapper();

  CatalogService(JdbcTemplate db, TransactionTemplate transaction,
      @Qualifier("applicationTaskExecutor") Executor executor) {
    this.db = db;
    this.transaction = transaction;
    this.executor = executor;
  }

  Object sources() {
    return db.queryForList("""
        select s.code, coalesce(r.status, 'NEVER_SYNCED') status, 'UNKNOWN' "reviewsStatus",
               r.started_at, r.finished_at, r.product_count
        from (values ('FOLK'), ('TASTY')) s(code)
        left join lateral (
          select * from catalog_import_run where source_code=s.code order by started_at desc limit 1
        ) r on true order by s.code
        """);
  }

  Object products(String q) {
    return db.queryForList("""
        select * from catalog_product where strpos(lower(name),lower(?))>0 or ?=''
        order by name,id limit 500
        """, q.strip(), q.strip()).stream().map(this::decodeOffers).toList();
  }

  Object product(UUID id) {
    return db.queryForList("select * from catalog_product where id=?", id).stream().findFirst()
        .map(this::decodeOffers).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
  }

  Object resolve(String raw) {
    String source = raw.startsWith("https://folk.shop/") ? "FOLK" : "TASTY";
    String url = CatalogAdapters.productUrl(source, raw);
    return db.queryForList("select * from catalog_product where source_code=? and url=?", source, url)
        .stream().findFirst().map(this::decodeOffers)
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
  }

  private Map<String, Object> decodeOffers(Map<String, Object> row) {
    try {
      row.put("offers", json.readValue(row.get("offers").toString(), List.class));
      return row;
    } catch (java.io.IOException e) {
      throw new IllegalStateException("Invalid stored offers", e);
    }
  }

  private String encodeOffers(List<CatalogAdapters.Offer> offers) {
    return json.valueToTree(offers).toString();
  }

  synchronized UUID start(String source) {
    if (!List.of("FOLK", "TASTY").contains(source))
      throw new ResponseStatusException(HttpStatus.NOT_FOUND);
    if (Boolean.TRUE.equals(db.queryForObject("""
          select exists(select 1 from catalog_import_run where source_code=?
              and started_at > now() - interval '5 minutes')
          """, Boolean.class, source)))
      throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "Повторите импорт через пять минут");
    UUID id = UUID.randomUUID();
    db.update("insert into catalog_import_run(id,source_code,status) values(?,?,'RUNNING')", id, source);
    executor.execute(() -> {
      try {
        save(id, source, adapters.fetch(source));
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        fail(id);
      } catch (Exception e) {
        fail(id);
      }
    });
    return id;
  }

  void save(UUID run, String source, List<CatalogAdapters.Product> products) {
    if (products.isEmpty()) {
      fail(run);
      return;
    }
    transaction.executeWithoutResult(status -> {
      for (var p : products) {
        db.update("""
            insert into catalog_product(id,source_code,url,name,product_type,price,currency,availability,offers)
            values(?,?,?,?,?,?,?,?,?::jsonb) on conflict (source_code,url) do update set
            name=excluded.name, product_type=excluded.product_type, price=excluded.price,
            currency=excluded.currency, availability=excluded.availability, offers=excluded.offers,last_seen_at=now()
            """, UUID.randomUUID(), source, p.url(), p.name(), p.type(), p.price(), p.currency(), p.availability(),
            encodeOffers(p.offers()));
      }
      db.update("update catalog_import_run set status='PARTIAL',product_count=?,finished_at=now() where id=?",
          products.size(), run);
    });
  }

  private void fail(UUID run) {
    db.update("update catalog_import_run set status='FAILED',finished_at=now() where id=?", run);
  }

  Object run(UUID id) {
    return db.queryForList("select * from catalog_import_run where id=?", id).stream().findFirst()
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
  }
}
