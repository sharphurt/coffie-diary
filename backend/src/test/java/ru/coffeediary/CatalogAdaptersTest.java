package ru.coffeediary;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class CatalogAdaptersTest {
  @Test
  void folkSitemapAndProductTitle() throws Exception {
    String url = "https://folk.shop/tproduct/123-benga";
    assertEquals(java.util.List.of(url), CatalogAdapters.folkUrls("<urlset><loc>" + url + "</loc></urlset>"));
    var product = CatalogAdapters.folkProduct(url, """
        <meta property="og:title" content="Кофе &amp; чай" />
        var product = {"descr":"Карамель, цветочный мёд, цитрусовые фрукты","editions":[{"uid":2,"Вес":"1000","price":"2 400.00"}],
          "characteristics":[{"title":"Формат","value":"Зёрна"}]};
        """.replace("\n", ""));
    assertEquals("Кофе & чай", product.name());
    assertNull(product.price());
    assertEquals("BEANS", product.type());
    assertEquals(url, product.producerDescriptors().sourceUrl());
    assertEquals("Карамель, цветочный мёд, цитрусовые фрукты", product.producerDescriptors().rawText());
    assertTrue(product.producerDescriptors().descriptors().contains(new ProducerTaste.Evidence("CARAMEL", "Карамель")));
    var offer = product.offers().getFirst();
    assertEquals(1000, offer.weightG());
    assertEquals(2400, offer.price().intValueExact());
    assertEquals(600, offer.pricePer250g().intValueExact());
  }

  @Test
  void tastyNormalizesOnlyTheOfferWithAnExplicitWeightAndRubles() throws Exception {
    var listing = new CatalogAdapters.Product("https://shop.tastycoffee.ru/coffee/test",
        "Кофе", null, null, null, null, java.util.List.of());
    var product = CatalogAdapters.tastyProduct(listing, """
        <script id="__NUXT_DATA__" type="application/json">
        [{"slug":1,"mini_description":2},"another","Черника",
         {"slug":4,"mini_description":5},"/coffee/test","Ноты <span>арахиса</span>, какао и лимона"]
        </script>
        <script type="application/ld+json">
        {"@type":"Product","description":"Кофе в зёрнах","offers":[
          {"@type":"Offer","url":"/coffee/test","price":"от 900","priceCurrency":"RUB"},
          {"@type":"Offer","url":"/coffee/test/standard","price":"900","priceCurrency":"RUB"},
          {"@type":"Offer","url":"/coffee/test/1000g","price":"2400","priceCurrency":"RUB",
           "additionalProperty":{"name":"Вес упаковки","value":"1 кг"}},
          {"@type":"Offer","url":"/coffee/test/250g","price":"10","priceCurrency":"EUR",
           "additionalProperty":{"name":"Вес упаковки","value":"250 г"}}]}
        </script>
        """);
    assertEquals("BEANS", product.type());
    assertEquals("Ноты арахиса, какао и лимона", product.producerDescriptors().rawText());
    assertEquals("mini_description", product.producerDescriptors().sourceField());
    assertEquals(java.util.Set.of("NUTTY", "CHOCOLATE", "CITRUS"),
        product.producerDescriptors().descriptors().stream().map(ProducerTaste.Evidence::group)
            .collect(java.util.stream.Collectors.toSet()));
    assertNull(product.offers().get(0).price());
    assertEquals("от 900", product.offers().get(0).rawPrice());
    assertNull(product.offers().get(0).pricePer250g());
    assertNull(product.offers().get(1).pricePer250g());
    assertEquals(600, product.offers().get(2).pricePer250g().intValueExact());
    assertNull(product.offers().get(3).pricePer250g());
  }

  @Test
  void tastyKeepsAdvertisedPriceAndDeduplicatesCanonicalUrls() throws Exception {
    var products = CatalogAdapters.tasty("""
        <script type="application/ld+json">
        {"@type":"ItemList","itemListElement":[
          {"item":{"@type":"Product","name":"Тест","url":"https://shop.tastycoffee.ru/coffee/test?ref=x",
                   "category":"coffee","offers":{"price":649,"priceCurrency":"RUB"}}},
          {"item":{"@type":"Product","name":"Тест","url":"https://shop.tastycoffee.ru/coffee/test",
                   "category":"coffee","offers":{"price":649,"priceCurrency":"RUB"}}},
          {"item":{"@type":"Product","name":"Упаковка","url":"https://shop.tastycoffee.ru/giftbox/gift-bag"}}]}
        </script>
        """);
    assertEquals(1, products.size());
    assertEquals(649, products.getFirst().price().intValueExact());
    assertNull(products.getFirst().availability());
  }

  @Test
  void rejectsUnsupportedHostsAndPaths() {
    assertThrows(Rules.Invalid.class, () -> CatalogAdapters.productUrl("FOLK", "https://folk.shop.evil.test/tproduct/1-a"));
    assertThrows(Rules.Invalid.class, () -> CatalogAdapters.productUrl("TASTY", "https://shop.tastycoffee.ru/api/test"));
    assertThrows(Rules.Invalid.class, () -> CatalogAdapters.productUrl("TASTY", "https://shop.tastycoffee.ru:444/coffee/test"));
  }
}
