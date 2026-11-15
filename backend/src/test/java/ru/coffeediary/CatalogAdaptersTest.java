package ru.coffeediary;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class CatalogAdaptersTest {
  @Test
  void folkSitemapAndProductTitle() throws Exception {
    String url = "https://folk.shop/tproduct/123-benga";
    assertEquals(java.util.List.of(url), CatalogAdapters.folkUrls("<urlset><loc>" + url + "</loc></urlset>"));
    var product = CatalogAdapters.folkProduct(url, "<meta property=\"og:title\" content=\"Кофе &amp; чай\" />");
    assertEquals("Кофе & чай", product.name());
    assertNull(product.price());
  }

  @Test
  void tastyKeepsAdvertisedPriceAndDeduplicatesCanonicalUrls() throws Exception {
    var products = CatalogAdapters.tasty("""
        <script type="application/ld+json">
        {"@type":"ItemList","itemListElement":[
          {"item":{"@type":"Product","name":"Тест","url":"https://shop.tastycoffee.ru/coffee/test?ref=x",
                   "category":"coffee","offers":{"price":649,"priceCurrency":"RUB"}}},
          {"item":{"@type":"Product","name":"Тест","url":"https://shop.tastycoffee.ru/coffee/test",
                   "category":"coffee","offers":{"price":649,"priceCurrency":"RUB"}}}]}
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
