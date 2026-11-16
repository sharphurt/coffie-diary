package ru.coffeediary;

import static org.junit.jupiter.api.Assertions.*;
import java.util.List;
import org.junit.jupiter.api.Test;

class ReviewTasteSummaryTest {
  private static ReviewTasteSummary.Sample sample(String author, String bucket) {
    return new ReviewTasteSummary.Sample(author, new ReviewTaste.Observation("bitterness", bucket, "fixture"),
        "https://example.test/" + author);
  }

  @Test
  void unknownBelowThreeIndependentAuthorsEvenWithDuplicatesAndAnonymousTexts() {
    var summary = ReviewTasteSummary.summarize(List.of(
        sample("one", "LOW"), sample("one", "LOW"), sample("two", "LOW"),
        sample(null, "LOW"), sample(null, "LOW")));
    var taste = summary.get("bitterness");
    assertEquals("UNKNOWN", taste.status());
    assertEquals(2, taste.independentAuthors());
    assertEquals(2, taste.unverifiedObservations());
    assertNull(taste.bucket());
    assertEquals("UNKNOWN", summary.get("acidity").status());
  }

  @Test
  void acceptsExactlyTwoThirdsAndCountsOneVotePerAuthor() {
    var taste = ReviewTasteSummary.summarize(List.of(
        sample("one", "LOW"), sample("one", "LOW"), sample("two", "LOW"),
        sample("three", "HIGH"))).get("bitterness");
    assertEquals("CONSENSUS", taste.status());
    assertEquals("LOW", taste.bucket());
    assertEquals(2, taste.distribution().get("LOW"));
    assertEquals(3, taste.independentAuthors());
    assertEquals(List.of("https://example.test/one", "https://example.test/two"), taste.sourceUrls());
  }

  @Test
  void conflictedBelowTwoThirdsAndDiscardsContradictoryAuthor() {
    var taste = ReviewTasteSummary.summarize(List.of(
        sample("one", "LOW"), sample("two", "LOW"), sample("three", "HIGH"),
        sample("four", "HIGH"), sample("five", "HIGH"), sample("five", "LOW"))).get("bitterness");
    assertEquals("CONFLICTED", taste.status());
    assertNull(taste.bucket());
    assertEquals(4, taste.independentAuthors());
    assertEquals(1, taste.conflictingAuthors());
    assertTrue(taste.sourceUrls().isEmpty());
  }
}
