package ru.coffeediary;

import java.math.BigDecimal;
import java.time.*;
import java.util.*;

public final class Rules {
  public static final List<String> DIMENSIONS =
      List.of("acidity", "bitterness", "sweetness", "astringency", "body", "aromaIntensity");

  public static class Invalid extends RuntimeException {
    public final String field;

    public Invalid(String f, String message) {
      super(message);
      field = f;
    }
  }

  public static String text(Map<String, Object> m, String key, int max, boolean required) {
    Object v = m.get(key);
    if ((v != null && !(v instanceof String))
        || (v instanceof String raw && raw.length() > max))
      throw new Invalid(key, "Проверьте поле: " + key);
    String s = v == null ? null : ((String) v).trim();
    if (required && (s == null || s.isEmpty()))
      throw new Invalid(key, "Проверьте поле: " + key);
    return s;
  }

  public static BigDecimal number(Object value, String field, int min, int max, boolean integer) {
    try {
      BigDecimal n = new BigDecimal(value.toString());
      if (n.compareTo(BigDecimal.valueOf(min)) < 0
          || n.compareTo(BigDecimal.valueOf(max)) > 0
          || (integer && n.stripTrailingZeros().scale() > 0)) throw new Exception();
      return n;
    } catch (Exception e) {
      throw new Invalid(field, "Допустимый диапазон: " + min + "–" + max);
    }
  }

  public static Instant time(Object value) {
    try {
      Instant t = value == null ? Instant.now() : Instant.parse(value.toString());
      if (t.isAfter(Instant.now().plusSeconds(5))) throw new Exception();
      return t;
    } catch (Exception e) {
      throw new Invalid("time", "Укажите время не позже текущего");
    }
  }

  @SuppressWarnings("unchecked")
  public static Map<String, Object> sensory(Object value) {
    Map<String, Object> input = value == null ? Map.of() : (Map<String, Object>) value;
    var out = new LinkedHashMap<String, Object>();
    for (String d : DIMENSIONS) {
      var a = (Map<String, Object>) input.getOrDefault(d, Map.of("status", "UNANSWERED"));
      String status = Objects.toString(a.get("status"), "");
      Object n = a.get("value");
      if (!List.of("RATED", "UNKNOWN", "UNANSWERED").contains(status))
        throw new Invalid(d, "Неизвестное состояние ответа");
      if (status.equals("RATED")) number(n, d, 1, 5, true);
      else if (n != null) throw new Invalid(d, "Число разрешено только для RATED");
      var r = new LinkedHashMap<String, Object>();
      r.put("status", status);
      r.put("value", n);
      out.put(d, r);
    }
    return out;
  }

  public static LocalDate day(Instant time, String zone) {
    return time.atZone(ZoneId.of(zone)).toLocalDate();
  }

  private Rules() {}
}
