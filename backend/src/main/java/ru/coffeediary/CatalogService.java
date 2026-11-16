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

  record ReviewImport(String url, List<String> texts, Map<String, String> authorIds) {
    ReviewImport(String url, List<String> texts) { this(url, texts, null); }
  }

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
        String author = input.authorIds() == null ? null : input.authorIds().get(text);
        if (author != null && !author.matches("[1-9][0-9]{0,39}"))
          throw new Rules.Invalid("authorIds", "Некорректный ID автора");
        db.update("""
            insert into external_review as r(product_id,content_hash,body,source_url,author_hash)
            values(?,encode(sha256(convert_to(?, 'UTF8')), 'hex'),?,?,
                   encode(sha256(convert_to(?, 'UTF8')), 'hex'))
            on conflict (product_id,content_hash) do update set
              author_hash=coalesce(r.author_hash,excluded.author_hash),
              author_ambiguous=r.author_ambiguous or
                (r.author_hash is not null and excluded.author_hash is not null
                 and r.author_hash<>excluded.author_hash)
            """, id, normalized, normalized, url, author);
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

  Object reviewFeatures(UUID id) {
    product(id);
    var observations = new ArrayList<Map<String, Object>>();
    var samples = new ArrayList<ReviewTasteSummary.Sample>();
    for (var review : db.queryForList("""
        select content_hash,body,source_url,author_hash,author_ambiguous from external_review where product_id=?
        order by observed_at desc,content_hash limit 500
        """, id)) {
      for (var signal : ReviewTaste.extract((String) review.get("body"))) {
        String author = Boolean.TRUE.equals(review.get("author_ambiguous"))
            ? null : (String) review.get("author_hash");
        samples.add(new ReviewTasteSummary.Sample(author, signal));
        observations.add(Map.of("dimension", signal.dimension(), "bucket", signal.bucket(),
            "evidence", signal.evidence(), "reviewHash", review.get("content_hash"),
            "sourceUrl", review.get("source_url"), "authorIdentified", author != null));
      }
    }
    return Map.of("extractorVersion", ReviewTaste.VERSION, "authorsVerified",
        !samples.isEmpty() && samples.stream().allMatch(sample -> sample.author() != null),
        "observations", observations, "summary", ReviewTasteSummary.summarize(samples));
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
      if (row.get("producer_descriptors") != null)
        row.put("producer_descriptors", json.readValue(row.get("producer_descriptors").toString(), Map.class));
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
            insert into catalog_product(id,source_code,url,name,product_type,price,currency,availability,offers,producer_descriptors)
            values(?,?,?,?,?,?,?,?,?::jsonb,?::jsonb) on conflict (source_code,url) do update set
            name=excluded.name, product_type=excluded.product_type, price=excluded.price,
            currency=excluded.currency, availability=excluded.availability, offers=excluded.offers,
            producer_descriptors=excluded.producer_descriptors,last_seen_at=now()
            returning id
            """, UUID.class, UUID.randomUUID(), source, p.url(), p.name(), p.type(), p.price(), p.currency(), p.availability(),
            encodeOffers(p.offers()),
            p.producerDescriptors() == null ? null : json.valueToTree(p.producerDescriptors()).toString());
        db.update("""
            with candidate as (
              select p.id, jsonb_build_object(
                'name',p.name,'type',p.product_type,'price',p.price,'currency',p.currency,
                'availability',p.availability,'offers',p.offers)
                || case when p.producer_descriptors is null then '{}'::jsonb
                   else jsonb_build_object('producer_descriptors',p.producer_descriptors) end snapshot
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
