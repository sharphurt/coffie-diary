package ru.coffeediary;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class ReviewTasteTest {
  @Test
  void separatesExplicitNegationFromPositiveAssertion() {
    assertEquals("LOW", ReviewTaste.extract("Кофе не горчит.").getFirst().bucket());
    assertEquals("HIGH", ReviewTaste.extract("Кофе горчит.").getFirst().bucket());
    assertEquals("LOW", ReviewTaste.extract("Кофе не кислый.").getFirst().bucket());
  }

  @Test
  void avoidsConvertingHedgesAndWishesIntoBuckets() {
    for (String text : new String[] {"Не сказал бы, что кислый", "Не слишком кислый",
        "Хотелось бы слаще", "Кажется, горчит", "Не высокая кислотность", "Если горчит, остудите"}) {
      assertTrue(ReviewTaste.extract(text).isEmpty(), text);
    }
  }

  @Test
  void ignoresLogisticsStaffAndDescriptorProxies() {
    assertTrue(ReviewTaste.extract("Яркая коробка, доставка быстрая.").isEmpty());
    assertTrue(ReviewTaste.extract("Команда поддержки: высокая кислотность.").isEmpty());
    assertTrue(ReviewTaste.extract("Смородина и тёмный шоколад.").isEmpty());
    assertEquals("aromaIntensity", ReviewTaste.extract("Насыщенный аромат.").getFirst().dimension());
    assertEquals("MEDIUM", ReviewTaste.extract("Доставили вовремя, но умеренная горечь.").getFirst().bucket());
  }

  @Test
  void givesAtMostOneObservationPerDimensionAndSkipsConflicts() {
    assertEquals(1, ReviewTaste.extract("Не горчит. Без горечи.").size());
    assertTrue(ReviewTaste.extract("Горчит. Без горечи.").isEmpty());
  }
}
