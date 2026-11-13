package ru.coffeediary;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;

class RulesTest {
  @Test
  void missingSensoryIsUnanswered() {
    var s = Rules.sensory(null);
    assertEquals(6, s.size());
    assertEquals("UNANSWERED", ((Map<?, ?>) s.get("acidity")).get("status"));
    assertNull(((Map<?, ?>) s.get("acidity")).get("value"));
  }

  @Test
  void unknownRemainsDistinct() {
    var s = Rules.sensory(Map.of("sweetness", Map.of("status", "UNKNOWN")));
    assertEquals("UNKNOWN", ((Map<?, ?>) s.get("sweetness")).get("status"));
  }

  @Test
  void ratedRequiresNumber() {
    assertThrows(
        Rules.Invalid.class, () -> Rules.sensory(Map.of("acidity", Map.of("status", "RATED"))));
    assertThrows(
        Rules.Invalid.class,
        () -> Rules.sensory(Map.of("acidity", Map.of("status", "UNKNOWN", "value", 3))));
  }

  @Test
  void ratingsAreIntegersInRange() {
    for (Object n : List.of(0, 6, 1.5, -1))
      assertThrows(Rules.Invalid.class, () -> Rules.number(n, "rating", 1, 5, true));
    assertEquals(4, Rules.number(4, "rating", 1, 5, true).intValue());
  }

  @Test
  void midnightUsesOwnerTimezone() {
    assertEquals(
        "2026-11-13",
        Rules.day(Instant.parse("2026-11-12T19:01:00Z"), "Asia/Yekaterinburg").toString());
    assertEquals(
        "2026-11-12",
        Rules.day(Instant.parse("2026-11-12T18:59:00Z"), "Asia/Yekaterinburg").toString());
  }

  @Test
  void futureRejected() {
    assertThrows(Rules.Invalid.class, () -> Rules.time("2099-01-01T00:00:00Z"));
  }
}
