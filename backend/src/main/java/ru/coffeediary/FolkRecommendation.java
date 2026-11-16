package ru.coffeediary;

import java.util.*;

final class FolkRecommendation {
  record Comparison(Set<String> shared, Set<String> candidateOnly, Set<String> anchorOnly, double similarity) {
    private static Comparison of(Set<String> candidate, Set<String> anchor) {
      if (candidate.isEmpty() || anchor.isEmpty())
        return new Comparison(Set.of(), Set.of(), Set.of(), Double.NaN);
      var shared = new TreeSet<>(candidate);
      shared.retainAll(anchor);
      var candidateOnly = new TreeSet<>(candidate);
      candidateOnly.removeAll(anchor);
      var anchorOnly = new TreeSet<>(anchor);
      anchorOnly.removeAll(candidate);
      return new Comparison(shared, candidateOnly, anchorOnly,
          (double) shared.size() / (shared.size() + candidateOnly.size() + anchorOnly.size()));
    }

    double contribution(int weight, boolean contrast) {
      return Double.isNaN(similarity) ? 0 : weight * (contrast ? 1 - similarity : similarity);
    }
  }

  record Reason(UUID lotId, String lotName, String sourceUrl, double rating, String direction,
      Set<String> sharedDescriptors, Set<String> candidateDescriptors, Set<String> anchorDescriptors,
      Set<String> sharedProcessing, Set<String> candidateProcessing, Set<String> anchorProcessing) {}

  record Match(double score, Reason reason) {}

  static Optional<Match> best(TasteRanking.Features candidate, List<TasteRanking.Anchor> anchors) {
    return anchors.stream().map(anchor -> match(candidate, anchor)).filter(match -> match.score() > 0)
        .max(Comparator.comparingDouble(Match::score).thenComparing(match -> match.reason().lotId()));
  }

  private static Match match(TasteRanking.Features candidate, TasteRanking.Anchor anchor) {
    var descriptors = Comparison.of(candidate.descriptors(), anchor.features().descriptors());
    var processing = Comparison.of(candidate.processing(), anchor.features().processing());
    boolean contrast = anchor.rating() <= 2;
    var lot = anchor.purchases().getFirst();
    var reason = new Reason(lot.lotId(), lot.lotName(), anchor.sourceUrl(), anchor.rating(),
        contrast ? "CONTRAST" : anchor.rating() >= 4 ? "SIMILAR" : "COMPARE",
        descriptors.shared(), descriptors.candidateOnly(), descriptors.anchorOnly(),
        processing.shared(), processing.candidateOnly(), processing.anchorOnly());
    double score = (descriptors.contribution(3, contrast) + processing.contribution(2, contrast)) / 5;
    return new Match(score, reason);
  }

  private FolkRecommendation() {}
}
