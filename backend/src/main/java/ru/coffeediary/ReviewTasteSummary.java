package ru.coffeediary;

import java.util.*;

final class ReviewTasteSummary {
  record Sample(String author, ReviewTaste.Observation observation) {}
  record Dimension(String status, String bucket, int independentAuthors,
      int unverifiedObservations, int conflictingAuthors, Map<String, Integer> distribution) {}

  private static final List<String> DIMENSIONS =
      List.of("acidity", "bitterness", "sweetness", "astringency", "body", "aromaIntensity");

  static Map<String, Dimension> summarize(List<Sample> samples) {
    var result = new LinkedHashMap<String, Dimension>();
    for (String dimension : DIMENSIONS) {
      var authors = new HashMap<String, Set<String>>();
      int unverified = 0;
      for (Sample sample : samples) {
        if (!dimension.equals(sample.observation().dimension())) continue;
        if (sample.author() == null) unverified++;
        else authors.computeIfAbsent(sample.author(), key -> new HashSet<>()).add(sample.observation().bucket());
      }
      var distribution = new LinkedHashMap<>(Map.of("LOW", 0, "MEDIUM", 0, "HIGH", 0));
      int conflicts = 0;
      for (var votes : authors.values()) {
        if (votes.size() == 1) distribution.merge(votes.iterator().next(), 1, Integer::sum);
        else conflicts++;
      }
      int count = authors.size() - conflicts;
      String bucket = null;
      String status = "UNKNOWN";
      if (count >= 3) {
        status = "CONFLICTED";
        for (var vote : distribution.entrySet()) {
          if (3 * vote.getValue() >= 2 * count) {
            bucket = vote.getKey();
            status = "CONSENSUS";
            break;
          }
        }
      }
      result.put(dimension, new Dimension(status, bucket, count, unverified, conflicts, distribution));
    }
    return result;
  }

  private ReviewTasteSummary() {}
}
