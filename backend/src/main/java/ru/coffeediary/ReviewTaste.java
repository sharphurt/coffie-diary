package ru.coffeediary;

import java.util.*;
import java.util.regex.Pattern;

final class ReviewTaste {
  static final String VERSION = "ru-rules-v1";
  record Observation(String dimension, String bucket, String evidence) {}

  private static final Pattern CLAUSES = Pattern.compile("[.!?;\\n]+|,?\\s+(?:но|однако|зато)\\s+");
  private static final Pattern QUALIFIER = Pattern.compile(
      "хотелось бы|хотел[аи]? бы|было бы|кажется|возможно|наверное|не сказал[аи]? бы|"
          + "не уверен|не (?:слишком|очень|особо|сильно)|\\bесли\\b|\\bвроде\\b",
      Pattern.UNICODE_CHARACTER_CLASS);
  private static final Pattern LOGISTICS = Pattern.compile("достав|курьер|упаков|пункт выдачи|посылк|коробк");
  private static final Pattern STAFF = Pattern.compile("команда поддержки|сотрудник tasty coffee|"
      + "команда tasty coffee|спасибо за (?:ваш )?отзыв|спасибо, что поделил");
  private static final String LOW = "слабая|низкая|легкая|мягкая";
  private static final String MEDIUM = "средняя|умеренная";
  private static final String HIGH = "сильная|высокая|яркая|выраженная|интенсивная";
  private static final Map<String, String> DIMENSIONS = Map.of(
      "acidity", "кислотность", "bitterness", "горечь", "sweetness", "сладость",
      "astringency", "терпкость|сухость", "body", "плотность", "aromaIntensity", "интенсивность аромата");

  static List<Observation> extract(String text) {
    String normalized = normalize(text);
    if (STAFF.matcher(normalized).find()) return List.of();
    var observations = new LinkedHashMap<String, Observation>();
    var conflicts = new HashSet<String>();
    for (String original : CLAUSES.split(text)) {
      String evidence = original.strip();
      String clause = normalize(evidence);
      if (clause.isEmpty() || QUALIFIER.matcher(clause).find() || LOGISTICS.matcher(clause).find()) continue;
      Map<String, String> buckets = new LinkedHashMap<>();
      DIMENSIONS.forEach((key, nouns) -> {
        if (matches(clause, "(?:без|нет)\\s+(?:" + genitive(nouns) + ")")) buckets.put(key, "LOW");
        else if (matches(clause, "(?:не|без)\\s+(?:" + LOW + "|" + MEDIUM + "|" + HIGH + ")\\s+(?:" + nouns + ")")) {
          // A negated intensity does not establish a different bucket.
        } else if (matches(clause, "(?:" + LOW + ")\\s+(?:" + nouns + ")")) buckets.put(key, "LOW");
        else if (matches(clause, "(?:" + MEDIUM + ")\\s+(?:" + nouns + ")")) buckets.put(key, "MEDIUM");
        else if (matches(clause, "(?:" + HIGH + ")\\s+(?:" + nouns + ")")) buckets.put(key, "HIGH");
      });
      if (matches(clause, "не\\s+горчит")) buckets.put("bitterness", "LOW");
      else if (matches(clause, "горчит")) buckets.put("bitterness", "HIGH");
      if (matches(clause, "не\\s+кисл(?:ый|ая|ое)")) buckets.put("acidity", "LOW");
      if (matches(clause, "насыщенный\\s+аромат")) buckets.put("aromaIntensity", "HIGH");
      buckets.forEach((dimension, bucket) -> {
        var previous = observations.putIfAbsent(dimension, new Observation(dimension, bucket, evidence));
        if (previous != null && !previous.bucket().equals(bucket)) conflicts.add(dimension);
      });
    }
    conflicts.forEach(observations::remove);
    return List.copyOf(observations.values());
  }

  private static String genitive(String nouns) {
    return nouns.replace("кислотность", "кислотности").replace("горечь", "горечи")
        .replace("сладость", "сладости").replace("терпкость", "терпкости")
        .replace("сухость", "сухости").replace("плотность", "плотности")
        .replace("интенсивность", "интенсивности");
  }

  private static boolean matches(String text, String expression) {
    return Pattern.compile("(?U)\\b(?:" + expression + ")\\b").matcher(text).find();
  }

  private static String normalize(String value) {
    return value.toLowerCase(Locale.ROOT).replace('ё', 'е');
  }

  private ReviewTaste() {}
}
