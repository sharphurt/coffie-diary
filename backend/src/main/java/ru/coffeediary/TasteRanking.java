package ru.coffeediary;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.*;

final class TasteRanking {
  static final List<String> DIMENSIONS =
      List.of("acidity", "bitterness", "sweetness", "astringency", "body", "aromaIntensity");

  record Features(Set<String> descriptors, Set<String> processing) {
    static Features from(JsonNode descriptors, JsonNode processing) {
      var groups = new TreeSet<String>();
      descriptors.path("descriptors").forEach(node -> groups.add(node.path("group").asText()));
      groups.remove("");
      var methods = new TreeSet<String>();
      processing.forEach(node -> methods.add(node.asText()));
      return new Features(groups, methods);
    }
  }

  record Cup(double rating, boolean uncertain, boolean methodKnown, Map<String, Double> sensory) {}
  record Purchase(UUID bagId, UUID lotId, String lotName, Double finalRating, List<Cup> cups) {}
  record Anchor(UUID productId, String sourceUrl, Features features, List<Purchase> purchases,
      double rating, double reliability, Map<String, Double> sensory) {}
  record Reason(UUID productId, String sourceUrl, List<PurchaseRef> purchases, double rating,
      Set<String> descriptors, Set<String> processing, double similarity, double coverage, double weight) {}
  record PurchaseRef(UUID bagId, UUID lotId, String lotName) {}
  record Score(double personalScore, double predictedRating, Double tasteFit, Map<String, Double> sensory,
      String confidence, List<Reason> reasons) {}

  private record BagRating(Purchase purchase, double rating, double methodReliability, Map<String, Double> sensory) {}
  private record Match(Anchor anchor, double similarity, double coverage, double weight,
      Set<String> descriptors, Set<String> processing) {}

  static Optional<Anchor> anchor(UUID id, String url, Features features, List<Purchase> purchases) {
    var bags = purchases.stream().map(TasteRanking::rateBag).flatMap(Optional::stream).toList();
    if (bags.isEmpty()) return Optional.empty();
    double rating = bags.stream().mapToDouble(BagRating::rating).average().orElseThrow();
    double method = bags.stream().mapToDouble(BagRating::methodReliability).average().orElseThrow();
    var sensory = aggregate(bags.stream().map(bag -> new Measurement(bag.sensory(), 1)).toList(), 1);
    return Optional.of(new Anchor(id, url, features, bags.stream().map(BagRating::purchase).toList(),
        rating, 0.5 * method, sensory));
  }

  private static Optional<BagRating> rateBag(Purchase bag) {
    if (bag.finalRating() == null && bag.cups().isEmpty()) return Optional.empty();
    double rating = bag.finalRating() == null ? bag.cups().stream()
        .mapToDouble(cup -> cup.rating() * weight(cup)).sum()
        / bag.cups().stream().mapToDouble(TasteRanking::weight).sum() : bag.finalRating();
    double method = bag.cups().stream().anyMatch(Cup::methodKnown) ? 1 : 0.5;
    var sensory = aggregate(bag.cups().stream()
        .map(cup -> new Measurement(cup.sensory(), weight(cup))).toList(), 1);
    return Optional.of(new BagRating(bag, rating, method, sensory));
  }

  private static double weight(Cup cup) {
    return cup.uncertain() ? 0.5 : 1;
  }

  private record Measurement(Map<String, Double> values, double weight) {}

  private static Map<String, Double> aggregate(List<Measurement> measurements, int minimum) {
    var result = new LinkedHashMap<String, Double>();
    for (String dimension : DIMENSIONS) {
      var known = measurements.stream().filter(item -> item.values().containsKey(dimension)).toList();
      if (known.size() >= minimum) result.put(dimension,
          known.stream().mapToDouble(item -> item.values().get(dimension) * item.weight()).sum()
              / known.stream().mapToDouble(Measurement::weight).sum());
    }
    return result;
  }

  static Optional<Score> score(Features candidate, List<Anchor> anchors) {
    var matches = anchors.stream().map(anchor -> match(candidate, anchor)).flatMap(Optional::stream)
        .sorted(Comparator.comparingDouble(Match::weight).reversed().thenComparing(match -> match.anchor().productId()))
        .limit(5).toList();
    if (matches.isEmpty()) return Optional.empty();
    double prior = anchors.stream().mapToDouble(Anchor::rating).average().orElseThrow();
    double prediction = (matches.stream().mapToDouble(match -> match.weight() * match.anchor().rating()).sum()
        + 0.5 * prior) / (matches.stream().mapToDouble(Match::weight).sum() + 0.5);
    var sensory = aggregate(matches.stream().map(match -> new Measurement(match.anchor().sensory(), match.weight()))
        .toList(), 2);
    var fit = anchors.stream().filter(anchor -> anchor.rating() >= 4)
        .mapToDouble(anchor -> distance(sensory, anchor.sensory())).filter(Double::isFinite).min();
    Double tasteFit = fit.isPresent() ? 1 - fit.getAsDouble() : null;
    double overall = (prediction - 1) / 4;
    double preference = tasteFit == null ? overall : 0.8 * overall + 0.2 * tasteFit;
    return Optional.of(new Score(preference, prediction, tasteFit, sensory, "LOW",
        matches.stream().map(TasteRanking::reason).toList()));
  }

  private static double distance(Map<String, Double> predicted, Map<String, Double> positive) {
    var common = predicted.keySet().stream().filter(positive::containsKey).toList();
    if (common.size() < 3) return Double.NaN;
    return common.stream().mapToDouble(key -> Math.abs(predicted.get(key) - positive.get(key)) / 4)
        .average().orElseThrow();
  }

  private static Optional<Match> match(Features candidate, Anchor anchor) {
    var other = anchor.features();
    double descriptors = jaccard(candidate.descriptors(), other.descriptors());
    double processing = jaccard(candidate.processing(), other.processing());
    double available = (Double.isNaN(descriptors) ? 0 : 3) + (Double.isNaN(processing) ? 0 : 2);
    double coverage = available / 11;
    if (coverage < 0.35) return Optional.empty();
    double similarity = (3 * descriptors + 2 * processing) / available;
    if (similarity < 0.45) return Optional.empty();
    return Optional.of(new Match(anchor, similarity, coverage, similarity * similarity * coverage * anchor.reliability(),
        intersection(candidate.descriptors(), other.descriptors()), intersection(candidate.processing(), other.processing())));
  }

  private static double jaccard(Set<String> left, Set<String> right) {
    if (left.isEmpty() || right.isEmpty()) return Double.NaN;
    var union = new HashSet<>(left);
    union.addAll(right);
    return (double) intersection(left, right).size() / union.size();
  }

  private static Set<String> intersection(Set<String> left, Set<String> right) {
    var common = new TreeSet<>(left);
    common.retainAll(right);
    return common;
  }

  private static Reason reason(Match match) {
    Anchor anchor = match.anchor();
    return new Reason(anchor.productId(), anchor.sourceUrl(),
        anchor.purchases().stream().map(purchase -> new PurchaseRef(
            purchase.bagId(), purchase.lotId(), purchase.lotName())).toList(),
        anchor.rating(), match.descriptors(), match.processing(), match.similarity(), match.coverage(), match.weight());
  }

  private TasteRanking() {}
}
