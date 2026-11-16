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
