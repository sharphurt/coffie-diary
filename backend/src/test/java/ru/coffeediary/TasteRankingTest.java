package ru.coffeediary;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import org.junit.jupiter.api.Test;

class TasteRankingTest {
  private static final TasteRanking.Features FRUITY = new TasteRanking.Features(Set.of("FRUITY"), Set.of("NATURAL"));
  private static final TasteRanking.Features CHOCOLATE = new TasteRanking.Features(Set.of("CHOCOLATE"), Set.of("WASHED"));

  @Test
  void rankingUsesKnownOverlapAndShrinksTowardDistinctProducts() {
    var anchors = List.of(anchor(FRUITY, 5, 5), anchor(CHOCOLATE, 1, 1));
    var preferred = TasteRanking.score(FRUITY, anchors).orElseThrow();
    var other = TasteRanking.score(CHOCOLATE, anchors).orElseThrow();
    assertEquals(3.625, preferred.predictedRating(), 1e-9);
    assertEquals(2.375, other.predictedRating(), 1e-9);
    assertTrue(preferred.personalScore() > other.personalScore());
    assertEquals(Set.of("NATURAL"), preferred.reasons().getFirst().processing());
    assertEquals(Set.of("FRUITY"), preferred.reasons().getFirst().descriptors());
    assertEquals(5.0 / 11, preferred.reasons().getFirst().coverage(), 1e-9);
    assertEquals("LOW", preferred.confidence());
    assertTrue(TasteRanking.score(new TasteRanking.Features(FRUITY.descriptors(), Set.of()), anchors).isEmpty());
  }

  @Test
  void measuredSensoryProfileChangesScoreWithoutChangingOverallPrediction() {
    var liked = anchor(FRUITY, 5, 5);
    var sameTaste = TasteRanking.score(FRUITY, List.of(liked, anchor(FRUITY, 2, 5))).orElseThrow();
    var differentTaste = TasteRanking.score(FRUITY, List.of(liked, anchor(FRUITY, 2, 1))).orElseThrow();
    assertEquals(sameTaste.predictedRating(), differentTaste.predictedRating(), 1e-9);
    assertEquals(1.0, sameTaste.tasteFit(), 1e-9);
    assertEquals(0.5, differentTaste.tasteFit(), 1e-9);
    assertTrue(sameTaste.personalScore() > differentTaste.personalScore());
  }

  @Test
  void finalRatingWinsAndUncertainCupHasHalfWeightInsideItsBag() {
    var normal = new TasteRanking.Cup(5, false, true, Map.of("body", 5.0));
    var uncertain = new TasteRanking.Cup(2, true, true, Map.of("body", 2.0));
    var anchor = TasteRanking.anchor(UUID.randomUUID(), "https://example.test", FRUITY, List.of(
        purchase(null, List.of(normal, uncertain)), purchase(2.0, List.of(normal)))).orElseThrow();
    assertEquals(3, anchor.rating(), 1e-9);
    assertEquals(4.5, anchor.sensory().get("body"), 1e-9);
  }

  @Test
  void sharedConsensusUsesPartialAndFullWeightsAndExplainsBothSources() {
    var candidateUrl = "https://shop.tastycoffee.ru/coffee/candidate";
    var anchorUrl = "https://shop.tastycoffee.ru/coffee/anchor";
    var anchor = anchor(withReviews(
        "acidity", consensus("LOW", anchorUrl), "body", consensus("HIGH", anchorUrl)), 5, 5);
    var partial = TasteRanking.score(withReviews("acidity", consensus("MEDIUM", candidateUrl)), List.of(anchor))
        .orElseThrow().reasons().getFirst();
    assertEquals(5.75 / 11, partial.coverage(), 1e-9);
    assertEquals((5 + 0.75 * 0.5) / 5.75, partial.similarity(), 1e-9);
    var full = TasteRanking.score(withReviews(
        "acidity", consensus("LOW", candidateUrl), "body", consensus("LOW", candidateUrl)), List.of(anchor))
        .orElseThrow().reasons().getFirst();
    assertEquals(6.5 / 11, full.coverage(), 1e-9);
    assertEquals((5 + 1.5 * 0.5) / 6.5, full.similarity(), 1e-9);
    assertEquals(List.of(1.0, 0.0), full.reviewTaste().stream().map(TasteRanking.ReviewComparison::similarity).toList());
    assertEquals(List.of(candidateUrl), full.reviewTaste().getFirst().candidate().sourceUrls());
    assertEquals(List.of(anchorUrl), full.reviewTaste().getFirst().anchor().sourceUrls());
  }

  @Test
  void unknownAndConflictedAreExactlyEquivalentToMissingOnEitherSide() {
    var known = consensus("LOW", "https://example.test/reviews");
    var knownFeatures = withReviews("acidity", known);
    var plainAnchor = anchor(FRUITY, 5, 5);
    var knownAnchor = withReviews(plainAnchor, knownFeatures);
    for (String status : List.of("UNKNOWN", "CONFLICTED")) {
      var absent = new ReviewTasteSummary.Dimension(status, null, 2, 0, 0, Map.of(), List.of());
      var absentFeatures = withReviews("acidity", absent);
      assertEquals(TasteRanking.score(FRUITY, List.of(knownAnchor)),
          TasteRanking.score(absentFeatures, List.of(knownAnchor)));
      assertEquals(TasteRanking.score(knownFeatures, List.of(plainAnchor)),
          TasteRanking.score(knownFeatures, List.of(withReviews(plainAnchor, absentFeatures))));
    }
  }

  @Test
  void consensusCanMeetCoverageWhenProcessingIsMissing() {
    var features = new TasteRanking.Features(FRUITY.descriptors(), Set.of(), Map.of(
        "acidity", consensus("LOW", "https://example.test"), "body", consensus("HIGH", "https://example.test")));
    var reason = TasteRanking.score(features, List.of(anchor(features, 4, 4))).orElseThrow().reasons().getFirst();
    assertEquals(4.5 / 11, reason.coverage(), 1e-9);
    assertEquals(1.0, reason.similarity());
  }

  @Test
  void reviewAgreementChangesTheOrderOfCandidatesWithIdenticalProducerFeatures() {
    var low = withReviews("bitterness", consensus("LOW", "https://example.test/low"));
    var high = withReviews("bitterness", consensus("HIGH", "https://example.test/high"));
    var anchors = List.of(anchor(low, 5, 3), anchor(high, 1, 3));
    var preferred = TasteRanking.score(low, anchors).orElseThrow();
    var other = TasteRanking.score(high, anchors).orElseThrow();
    assertTrue(preferred.personalScore() > other.personalScore());
    assertEquals(3.0, TasteRanking.score(FRUITY, anchors).orElseThrow().predictedRating(), 1e-9);
  }

  private TasteRanking.Features withReviews(String first, ReviewTasteSummary.Dimension firstValue,
      String second, ReviewTasteSummary.Dimension secondValue) {
    return new TasteRanking.Features(FRUITY.descriptors(), FRUITY.processing(),
        Map.of(first, firstValue, second, secondValue));
  }

  private TasteRanking.Features withReviews(String dimension, ReviewTasteSummary.Dimension value) {
    return new TasteRanking.Features(FRUITY.descriptors(), FRUITY.processing(), Map.of(dimension, value));
  }

  private TasteRanking.Anchor withReviews(TasteRanking.Anchor original, TasteRanking.Features features) {
    return TasteRanking.anchor(original.productId(), original.sourceUrl(), features, original.purchases()).orElseThrow();
  }

  private ReviewTasteSummary.Dimension consensus(String bucket, String url) {
    return new ReviewTasteSummary.Dimension("CONSENSUS", bucket, 3, 0, 0, Map.of(bucket, 3), List.of(url));
  }

  private TasteRanking.Anchor anchor(TasteRanking.Features features, double rating, double sensory) {
    var cup = new TasteRanking.Cup(rating, false, true, Map.of(
        "acidity", sensory, "sweetness", sensory, "body", sensory));
    return TasteRanking.anchor(UUID.randomUUID(), "https://example.test", features,
        List.of(purchase(null, List.of(cup)))).orElseThrow();
  }

  private TasteRanking.Purchase purchase(Double finalRating, List<TasteRanking.Cup> cups) {
    return new TasteRanking.Purchase(UUID.randomUUID(), UUID.randomUUID(), "lot", finalRating, cups);
  }
}
