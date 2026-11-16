package ru.coffeediary;

import java.util.*;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.JdbcTemplate;

final class ReviewTasteEvidence {
  record Evidence(String hash, ReviewTasteSummary.Sample sample) {}

  static Map<UUID, List<Evidence>> load(JdbcTemplate db, Set<UUID> products) {
    if (products.isEmpty()) return Map.of();
    String ids = products.stream().map(UUID::toString).collect(Collectors.joining(",", "{", "}"));
    var evidence = new HashMap<UUID, List<Evidence>>();
    for (var review : db.queryForList("""
        select ids.product_id,r.content_hash,r.body,r.source_url,r.author_hash,r.author_ambiguous
        from unnest(?::uuid[]) as ids(product_id)
        cross join lateral (
          select content_hash,body,source_url,author_hash,author_ambiguous from external_review
          where product_id=ids.product_id order by observed_at desc,content_hash limit 500
        ) r
        """, ids)) {
      var signals = ReviewTaste.extract((String) review.get("body"));
      if (signals.isEmpty()) continue;
      var list = evidence.computeIfAbsent((UUID) review.get("product_id"), id -> new ArrayList<>());
      String author = Boolean.TRUE.equals(review.get("author_ambiguous")) ? null : (String) review.get("author_hash");
      for (var signal : signals) list.add(new Evidence((String) review.get("content_hash"),
          new ReviewTasteSummary.Sample(author, signal, (String) review.get("source_url"))));
    }
    return evidence;
  }

  static Map<String, ReviewTasteSummary.Dimension> summarize(List<Evidence> evidence) {
    return ReviewTasteSummary.summarize(evidence.stream().map(Evidence::sample).toList());
  }

  private ReviewTasteEvidence() {}
}
