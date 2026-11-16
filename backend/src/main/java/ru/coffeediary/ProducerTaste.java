package ru.coffeediary;

import java.util.*;
import java.util.regex.Pattern;
import org.springframework.web.util.HtmlUtils;

final class ProducerTaste {
  record Evidence(String group, String term) {}
  record Document(String rawText, String sourceUrl, String sourceField, String version, List<Evidence> descriptors) {}
  private record Rule(String group, Pattern words) {}

  private static final List<Rule> DICTIONARY = List.of(
      rule("CHOCOLATE", "шоколад|какао"),
      rule("NUTTY", "орех|арахис|фундук|миндал|фисташ|кешью"),
      rule("CARAMEL", "карамел|ириск|тоффи"),
      rule("FRUITY", "фрукт|яблок|яблоч|груш|персик|абрикос|манго|ананас|слив[ауыое]|банан|виноград"),
      rule("BERRY", "ягод|смородин|малин|клубник|черник|ежевик|вишн|клюкв|земляник"),
      rule("CITRUS", "цитрус|лимон|апельсин|грейпфрут|мандарин|лайм|бергамот"),
      rule("FLORAL", "цветоч|жасмин|лаванд|гибискус|фиалк|роз[аыеу]|розов"),
      rule("TEA", "чай|чая|чаем|чайны"),
      rule("FERMENTED", "фермент|винн|вино|ромов|конья|ликер"),
      rule("SPICY", "специ|пряност|кориц|кардамон|гвоздик|мускат|перец|ванил"),
      rule("EARTHY", "землист|землян|табак|древес"));

  private static Rule rule(String group, String stems) {
    return new Rule(group, Pattern.compile("(?iU)\\b(?:" + stems + ")[\\p{L}]*\\b"));
  }

  static Document extract(String html, String url, String field) {
    if (html == null) return null;
    String text = HtmlUtils.htmlUnescape(html.replaceAll("<[^>]*>", "")).strip();
    if (text.isBlank()) return null;
    String normalized = text.replace('ё', 'е').replace('Ё', 'Е');
    var evidence = new LinkedHashSet<Evidence>();
    for (Rule rule : DICTIONARY) {
      var matcher = rule.words().matcher(normalized);
      while (matcher.find()) evidence.add(new Evidence(rule.group(), text.substring(matcher.start(), matcher.end())));
    }
    return new Document(text, url, field, "producer-ru-v1", List.copyOf(evidence));
  }

  private ProducerTaste() {}
}
