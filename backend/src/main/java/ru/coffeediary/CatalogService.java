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
        select s.code, coalesce(r.status, 'NEVER_SYNCED') status,
               coalesce(reviews.status,'UNKNOWN') "reviewsStatus",
               r.started_at, r.finished_at, r.product_count
        from (values ('FOLK'), ('TASTY')) s(code)
        left join lateral (
          select * from catalog_import_run where source_code=s.code order by started_at desc limit 1
        ) r on true left join lateral (
          select case when bool_or(p.reviews_status='AVAILABLE') then 'AVAILABLE'
            when bool_or(p.reviews_status='UNAVAILABLE') then 'UNAVAILABLE' end status
          from catalog_product p where p.source_code=s.code
        ) reviews on true order by s.code
        """);
  }

  record ReviewImport(String url, List<String> texts) {}

  Object importReviews(UUID id, ReviewImport input) {
    if (input.texts() == null || input.texts().size() > 50)
      throw new Rules.Invalid("texts", "Передайте до 50 отзывов");
    String url = CatalogAdapters.productUrl("TASTY", input.url());
    transaction.executeWithoutResult(status -> {
      var rows = db.queryForList("select url from catalog_product where id=? and source_code='TASTY' for update", id);
      if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
      if (!url.equals(rows.getFirst().get("url")))
        throw new Rules.Invalid("url", "Ссылка не соответствует товару");
      for (String text : input.texts()) {
        if (text == null || text.isBlank() || text.length() > 8000)
          throw new Rules.Invalid("texts", "Некорректный текст отзыва");
        String normalized = java.text.Normalizer.normalize(text, java.text.Normalizer.Form.NFC)
            .replaceAll("(?U)\\s+", " ").strip();
        db.update("""
            insert into external_review(product_id,content_hash,body,source_url)
            values(?,encode(sha256(convert_to(?, 'UTF8')), 'hex'),?,?)
            on conflict (product_id,content_hash) do nothing
            """, id, normalized, normalized, url);
      }
      db.update("update catalog_product set reviews_status=? where id=?",
          input.texts().isEmpty() ? "UNAVAILABLE" : "AVAILABLE", id);
    });
    return reviews(id);
  }

  Object reviews(UUID id) {
    String status = db.queryForList("select reviews_status from catalog_product where id=?", String.class, id)
        .stream().findFirst().orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    return Map.of("status", status, "items", db.queryForList("""
        select body,source_url,observed_at from external_review where product_id=?
        order by observed_at desc,content_hash limit 50
        """, id));
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
    return json.valueToTree(offers.stream()
        .sorted(Comparator.comparing(CatalogAdapters.Offer::variantId)).toList()).toString();
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
    executor.execute(() -> complete(id, source, () -> adapters.fetch(source)));
    return id;
  }

  void complete(UUID run, String source, Callable<List<CatalogAdapters.Product>> fetch) {
    try {
      save(run, source, fetch.call());
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      fail(run);
    } catch (Exception e) {
      fail(run);
    }
  }

  private void save(UUID run, String source, List<CatalogAdapters.Product> products) {
    if (products.isEmpty()) {
      fail(run);
      return;
    }
    transaction.executeWithoutResult(status -> {
      for (var p : products) {
        UUID product = db.queryForObject("""
            insert into catalog_product(id,source_code,url,name,product_type,price,currency,availability,offers)
            values(?,?,?,?,?,?,?,?,?::jsonb) on conflict (source_code,url) do update set
            name=excluded.name, product_type=excluded.product_type, price=excluded.price,
            currency=excluded.currency, availability=excluded.availability, offers=excluded.offers,last_seen_at=now()
            returning id
            """, UUID.class, UUID.randomUUID(), source, p.url(), p.name(), p.type(), p.price(), p.currency(), p.availability(),
            encodeOffers(p.offers()));
        db.update("""
            with candidate as (
              select p.id, jsonb_build_object(
                'name',p.name,'type',p.product_type,'price',p.price,'currency',p.currency,
                'availability',p.availability,'offers',p.offers) snapshot
              from catalog_product p where p.id=?
            ), inserted as (
              insert into catalog_revision(id,product_id,snapshot)
              select gen_random_uuid(),c.id,c.snapshot from candidate c
              where c.snapshot is distinct from (
                select r.snapshot from catalog_product p
                join catalog_revision r on r.id=p.current_revision_id where p.id=c.id)
              returning id,product_id
            )
            update catalog_product p set current_revision_id=i.id from inserted i where i.product_id=p.id
            """, product);
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
