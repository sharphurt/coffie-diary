package ru.coffeediary;

import com.fasterxml.jackson.databind.*;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.regex.Pattern;
import org.springframework.web.util.HtmlUtils;

final class CatalogAdapters {
  record Product(String url, String name, String type, BigDecimal price, String currency, String availability) {}
  private static final Pattern JSON_LD = Pattern.compile(
      "<script\\b[^>]*type=[\"']application/ld\\+json[\"'][^>]*>(.*?)</script>", Pattern.DOTALL);
  private static final Pattern LOC = Pattern.compile("<loc>([^<]+)</loc>");
  private static final Pattern TITLE = Pattern.compile("<meta\\s+property=\"og:title\"\\s+content=\"([^\"]+)\"");
  private static final ObjectMapper JSON = new ObjectMapper();

  static String productUrl(String source, String raw) {
    try {
      URI uri = URI.create(raw);
      String host = source.equals("FOLK") ? "folk.shop" : "shop.tastycoffee.ru";
      String path = uri.getPath();
      String pattern = source.equals("FOLK") ? "/tproduct/[0-9]+-[a-zA-Z0-9-]+"
          : "/coffee/[a-zA-Z0-9-]+";
      if ("https".equals(uri.getScheme()) && host.equals(uri.getHost())
          && uri.getPort() == -1 && uri.getUserInfo() == null && path.matches(pattern))
        return "https://" + host + path;
    } catch (IllegalArgumentException | NullPointerException ignored) {
    }
    throw new Rules.Invalid("url", "Укажите ссылку на товар FOLK или Tasty Coffee");
  }

  List<Product> fetch(String source) throws IOException, InterruptedException {
    if (source.equals("TASTY")) return tasty(get("https://shop.tastycoffee.ru/coffee/filter"));
    var urls = folkUrls(get("https://folk.shop/sitemap-store.xml"));
    var result = new ArrayList<Product>();
    for (String url : urls.stream().limit(12).toList()) {
      result.add(folkProduct(url, get(url)));
    }
    return result;
  }

  static List<String> folkUrls(String xml) {
    return LOC.matcher(xml).results().map(match -> productUrl("FOLK", HtmlUtils.htmlUnescape(match.group(1).trim())))
        .distinct().toList();
  }

  static Product folkProduct(String url, String html) throws IOException {
    var title = TITLE.matcher(html);
    if (!title.find()) throw new IOException("Missing product title");
    return new Product(url, HtmlUtils.htmlUnescape(title.group(1)).strip(), null, null, null, null);
  }

  static List<Product> tasty(String html) throws IOException {
    var products = new LinkedHashMap<String, Product>();
    var scripts = JSON_LD.matcher(html);
    while (scripts.find()) {
      var root = JSON.readTree(scripts.group(1));
      if (!root.path("@type").asText().equals("ItemList")) continue;
      for (var item : root.path("itemListElement")) {
        var product = item.path("item");
        if (!product.path("@type").asText().equals("Product")) continue;
        String name = product.path("name").asText("").strip();
        if (name.isEmpty()) throw new IOException("Missing product name");
        String url = productUrl("TASTY", product.path("url").asText());
        var offer = product.path("offers");
        var price = offer.path("price");
        products.put(url, new Product(url, name, product.path("category").asText(null),
            price.isNumber() && price.decimalValue().signum() >= 0 ? price.decimalValue() : null,
            offer.path("priceCurrency").asText(null), offer.path("availability").asText(null)));
      }
    }
    return List.copyOf(products.values());
  }

  private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5))
      .followRedirects(HttpClient.Redirect.NEVER).build();

  private String get(String url) throws IOException, InterruptedException {
    var response = client.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(8))
        .header("User-Agent", "CoffeeDiary/1.0").GET().build(), HttpResponse.BodyHandlers.ofInputStream());
    try (var body = response.body()) {
      if (response.statusCode() != 200) throw new IOException("Source HTTP " + response.statusCode());
      byte[] bytes = body.readNBytes(2_000_001);
      if (bytes.length > 2_000_000) throw new IOException("Source response too large");
      return new String(bytes, StandardCharsets.UTF_8);
    }
  }
}
