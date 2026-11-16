package ru.coffeediary;

import com.fasterxml.jackson.databind.*;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.regex.Pattern;
import org.springframework.web.util.HtmlUtils;

final class CatalogAdapters {
  record Offer(String variantId, Integer weightG, BigDecimal price, String currency, String availability,
      BigDecimal pricePer250g, String rawPrice) {}
  record Product(String url, String name, String type, BigDecimal price, String currency, String availability,
      List<Offer> offers, ProducerTaste.Document producerDescriptors, List<String> processing, String roast) {
    Product(String url, String name, String type, BigDecimal price, String currency, String availability,
        List<Offer> offers, ProducerTaste.Document producerDescriptors, List<String> processing) {
      this(url, name, type, price, currency, availability, offers, producerDescriptors, processing, null);
    }
    Product(String url, String name, String type, BigDecimal price, String currency, String availability,
        List<Offer> offers, ProducerTaste.Document producerDescriptors) {
      this(url, name, type, price, currency, availability, offers, producerDescriptors, List.of());
    }
    Product(String url, String name, String type, BigDecimal price, String currency, String availability,
        List<Offer> offers) {
      this(url, name, type, price, currency, availability, offers, null);
    }
  }
  private static final Pattern JSON_LD = Pattern.compile(
      "<script\\b[^>]*type=[\"']application/ld\\+json[\"'][^>]*>(.*?)</script>", Pattern.DOTALL);
  private static final Pattern LOC = Pattern.compile("<loc>([^<]+)</loc>");
  private static final Pattern NUXT = Pattern.compile(
      "<script\\b[^>]*id=[\"']__NUXT_DATA__[\"'][^>]*>(.*?)</script>", Pattern.DOTALL);
  private static final Pattern TITLE = Pattern.compile("<meta\\s+property=\"og:title\"\\s+content=\"([^\"]+)\"");
  private static final Pattern FOLK_PRODUCT = Pattern.compile("var product = (\\{[^\\r\\n]*});");
  private static final Pattern WEIGHT = Pattern.compile("(\\d+)\\s*(г|гр|кг)?", Pattern.CASE_INSENSITIVE);
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
    if (source.equals("TASTY")) {
      var discovered = new LinkedHashMap<String, Product>();
      for (int page = 1; page <= 24; page++) {
        String url = "https://shop.tastycoffee.ru/coffee" + (page == 1 ? "" : "?page=" + page);
        var listed = tasty(get(url));
        int count = discovered.size();
        listed.forEach(product -> discovered.putIfAbsent(product.url(), product));
        if (discovered.size() == count) break;
      }
      var result = new ArrayList<Product>();
      for (Product listed : discovered.values()) {
        result.add(tastyProduct(listed, get(listed.url())));
      }
      return result;
    }
    var urls = folkUrls(get("https://folk.shop/sitemap-store.xml"));
    var result = new ArrayList<Product>();
    for (String url : urls.stream().limit(100).toList()) {
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
    var payload = FOLK_PRODUCT.matcher(html);
    if (!payload.find()) throw new IOException("Missing FOLK product data");
    var product = JSON.readTree(payload.group(1));
    var offers = new ArrayList<Offer>();
    for (var edition : product.path("editions")) {
      String quantity = edition.path("quantity").asText("").strip();
      String availability = quantity.matches("[0-9]+")
          ? quantity.matches("0+") ? "OutOfStock" : "InStock" : null;
      offers.add(offer(edition.path("uid").asText(), weight(edition.path("Вес").asText(), true),
          edition.path("price"), "RUB", availability));
    }
    String type = null;
    String roast = null;
    List<String> processing = List.of();
    for (var characteristic : product.path("characteristics")) {
      if ("Формат".equals(characteristic.path("title").asText()))
        type = productType(characteristic.path("value").asText());
      if ("Обработка".equals(characteristic.path("title").asText()))
        processing = processing(characteristic.path("value").asText());
      if ("Обжарка".equals(characteristic.path("title").asText()))
        roast = folkRoast(characteristic.path("value").asText());
    }
    if (type == null) type = productType(product.path("text").asText());
    return new Product(url, HtmlUtils.htmlUnescape(title.group(1)).strip(), type,
        price(product.path("price")), "RUB", null, offers,
        ProducerTaste.extract(product.path("descr").asText(null), url, "descr"), processing, roast);
  }

  private static String folkRoast(String raw) {
    return switch (raw.strip().toLowerCase(Locale.ROOT)) {
      case "фильтр", "для фильтра" -> "FILTER";
      case "эспрессо", "для эспрессо" -> "ESPRESSO";
      case "омни", "омни-обжарка", "универсальная", "фильтр и эспрессо", "эспрессо и фильтр" -> "OMNI";
      default -> null;
    };
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
        String url;
        try {
          url = productUrl("TASTY", product.path("url").asText());
        } catch (Rules.Invalid ignored) {
          // The general catalog also promotes gift wrapping outside /coffee.
          continue;
        }
        var offer = product.path("offers");
        var price = offer.path("price");
        products.put(url, new Product(url, name, product.path("category").asText(null),
            price.isNumber() && price.decimalValue().signum() >= 0 ? price.decimalValue() : null,
            offer.path("priceCurrency").asText(null), offer.path("availability").asText(null), List.of()));
      }
    }
    return List.copyOf(products.values());
  }

  static Product tastyProduct(Product listed, String html) throws IOException {
    var scripts = JSON_LD.matcher(html);
    while (scripts.find()) {
      var product = JSON.readTree(scripts.group(1));
      if (!"Product".equals(product.path("@type").asText())) continue;
      var offers = new LinkedHashMap<String, Offer>();
      var node = product.path("offers");
      Iterable<JsonNode> entries = node.isArray() ? node : List.of(node);
      for (var entry : entries) {
        if (!"Offer".equals(entry.path("@type").asText())) continue;
        var property = entry.path("additionalProperty");
        Iterable<JsonNode> properties = property.isArray() ? property : List.of(property);
        Integer grams = null;
        for (var field : properties)
          if ("Вес упаковки".equals(field.path("name").asText()))
            grams = weight(field.path("value").asText(), false);
        String key = entry.path("url").asText(listed.url()) + "#" + grams;
        offers.put(key, offer(key, grams, entry.path("price"),
            entry.path("priceCurrency").asText(null), entry.path("availability").asText(null)));
      }
      String type = productType(product.path("description").asText());
      if (type == null) type = productType(listed.name());
      var published = tastyDetails(listed.url(), html);
      return new Product(listed.url(), listed.name(), type, listed.price(),
          listed.currency(), listed.availability(), List.copyOf(offers.values()),
          published.descriptors(), published.processing());
    }
    return listed;
  }

  private record PublishedTaste(ProducerTaste.Document descriptors, List<String> processing) {}

  private static PublishedTaste tastyDetails(String url, String html) throws IOException {
    var script = NUXT.matcher(html);
    var empty = new PublishedTaste(null, List.of());
    if (!script.find()) return empty;
    var data = JSON.readTree(script.group(1));
    if (!data.isArray()) return empty;
    String path = URI.create(url).getPath();
    String slug = path.substring("/coffee/".length());
    for (var object : data) {
      var slugRef = object.path("slug");
      var descriptionRef = object.path("mini_description");
      if (!slugRef.isIntegralNumber()) continue;
      String publishedSlug = data.path(slugRef.intValue()).asText();
      if (path.equals(publishedSlug) || slug.equals(publishedSlug)) {
        var processRef = object.path("processing_method");
        if (!descriptionRef.isIntegralNumber() && !processRef.isIntegralNumber()) continue;
        String description = data.path(descriptionRef.asInt(-1)).asText(null);
        return new PublishedTaste(ProducerTaste.extract(description, url, "mini_description"),
            processing(data.path(processRef.asInt(-1)).asText()));
      }
    }
    return empty;
  }

  static List<String> processing(String raw) {
    return Arrays.stream(raw.toLowerCase(Locale.ROOT).replace('ё', 'е').split("[,/;]"))
        .map(String::strip).map(value -> switch (value) {
          case "натуральная", "натуральный", "сухая", "natural" -> "NATURAL";
          case "мытая", "мытый", "washed" -> "WASHED";
          case "хани", "honey" -> "HONEY";
          default -> "";
        }).filter(value -> !value.isEmpty()).distinct().sorted().toList();
  }

  private static Offer offer(String id, Integer grams, JsonNode value, String currency, String availability) {
    BigDecimal price = price(value);
    BigDecimal normalized = grams != null && price != null && "RUB".equals(currency)
        ? price.multiply(BigDecimal.valueOf(250)).divide(BigDecimal.valueOf(grams), 2, RoundingMode.HALF_UP)
        : null;
    return new Offer(id, grams, price, currency, availability, normalized, value.asText(null));
  }

  private static Integer weight(String raw, boolean implicitGrams) {
    var match = WEIGHT.matcher(raw.strip());
    if (!match.matches() || (match.group(2) == null && !implicitGrams)) return null;
    try {
      int grams = Math.multiplyExact(Integer.parseInt(match.group(1)),
          "кг".equalsIgnoreCase(match.group(2)) ? 1000 : 1);
      return grams > 0 ? grams : null;
    } catch (ArithmeticException | NumberFormatException e) {
      return null;
    }
  }

  private static BigDecimal price(JsonNode value) {
    String raw = value.asText("").replaceAll("[ \\u00a0\\u202f]", "");
    if (!raw.matches("[0-9]+(?:[.,][0-9]+)?")) return null;
    return new BigDecimal(raw.replace(',', '.'));
  }

  private static String productType(String text) {
    String value = text.toLowerCase(Locale.ROOT);
    if (value.contains("дрип")) return "DRIP";
    if (value.contains("капсул")) return "CAPSULES";
    if (value.contains("зёрна") || value.contains("зерна") || value.contains("в зёрнах")
        || value.contains("в зернах")) return "BEANS";
    if (value.contains("растворим")) return "INSTANT";
    if (value.contains("концентрат")) return "CONCENTRATE";
    return null;
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
