package ru.coffeediary;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class DiaryService {
  final JdbcTemplate db;
  final ObjectMapper json = new ObjectMapper();

  public DiaryService(JdbcTemplate db) {
    this.db = db;
  }

  String json(Object v) {
    try {
      return json.writeValueAsString(v);
    } catch (Exception e) {
      throw new IllegalArgumentException(e);
    }
  }

  @SuppressWarnings("unchecked")
  Map<String, Object> map(Object v) {
    try {
      return json.readValue(v.toString(), Map.class);
    } catch (Exception e) {
      throw new IllegalArgumentException(e);
    }
  }

  UUID owner(String login) {
    return db.queryForObject("select id from app_user where login=?", UUID.class, login);
  }

  Map<String, Object> row(String table, UUID id, UUID owner) {
    var rows = db.queryForList("select * from " + table + " where id=? and owner_id=?", id, owner);
    if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
    return clean(rows.getFirst());
  }

  Map<String, Object> clean(Map<String, Object> r) {
    var out = new LinkedHashMap<String, Object>();
    r.forEach(
        (k, v) ->
            out.put(
                k,
                v != null && v.getClass().getSimpleName().equals("PGobject")
                    ? k.equals("wishes") ? Arrays.asList(wishArray(v)) : map(v)
                    : v instanceof Timestamp t ? t.toInstant().toString()
                    : v instanceof java.sql.Date date ? date.toLocalDate().toString() : v));
    return out;
  }

  private String[] wishArray(Object value) {
    try {
      return json.readValue(value.toString(), String[].class);
    } catch (java.io.IOException e) {
      throw new IllegalStateException("Invalid stored wishes", e);
    }
  }

  List<Map<String, Object>> rows(String sql, Object... args) {
    return db.queryForList(sql, args).stream().map(this::clean).toList();
  }

  Map<String, Object> profile(UUID owner) {
    return clean(
        db.queryForMap(
            "select p.* from brew_profile p join app_user u on u.id=p.owner_id"
                + " where p.owner_id=? and p.deleted_at is null"
                + " order by (p.id is distinct from u.default_brew_profile_id),p.id limit 1",
            owner));
  }

  void active(String table, UUID id, UUID owner) {
    var r = row(table, id, owner);
    if (r.get("deleted_at") != null)
      throw new ResponseStatusException(HttpStatus.CONFLICT, "Запись удалена");
  }

  UUID uuid(Object x) {
    try {
      return UUID.fromString(x.toString());
    } catch (Exception e) {
      throw new Rules.Invalid("id", "Некорректный идентификатор");
    }
  }

  String choice(Object v, String field, List<String> allowed) {
    if (v == null) return null;
    String s = v.toString();
    if (!allowed.contains(s)) throw new Rules.Invalid(field, "Недопустимое значение");
    return s;
  }

  void version(Map<String, Object> existing, Map<String, Object> input) {
    if (!Objects.toString(existing.get("version")).equals(Objects.toString(input.get("version"))))
      throw new ResponseStatusException(
          HttpStatus.CONFLICT, "Запись изменена в другой вкладке. Обновите страницу.");
  }

  @Transactional
  public Map<String, Object> create(
      String kind, UUID owner, Map<String, Object> input, String key) {
    if (key == null || key.isBlank() || key.length() > 200)
      throw new Rules.Invalid("Idempotency-Key", "Требуется ключ операции");
    db.queryForObject("select id from app_user where id=? for update", UUID.class, owner);
    String hash;
    try {
      hash =
          HexFormat.of()
              .formatHex(
                  MessageDigest.getInstance("SHA-256")
                      .digest((kind + json(input)).getBytes(StandardCharsets.UTF_8)));
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
    var old =
        db.queryForList(
            "select request_hash,result from idempotency where owner_id=? and key=?", owner, key);
    if (!old.isEmpty()) {
      if (!hash.equals(old.getFirst().get("request_hash")))
        throw new ResponseStatusException(
            HttpStatus.CONFLICT, "Ключ уже использован для других данных");
      return map(old.getFirst().get("result"));
    }
    Map<String, Object> result =
        switch (kind) {
          case "lots" -> lot(owner, input);
          case "bags" -> bag(owner, input);
          case "consumptions" -> consumption(owner, input, null);
          case "tastings" -> tasting(owner, input);
          default -> throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        };
    db.update("insert into idempotency values(?,?,?,?::jsonb)", owner, key, hash, json(result));
    return result;
  }

  Map<String, Object> lot(UUID owner, Map<String, Object> in) {
    UUID id = UUID.randomUUID();
    db.update("insert into personal_lot(id,owner_id,name,roaster_name) values(?,?,?,?)",
        id, owner, Rules.text(in, "name", 200, true), Rules.text(in, "roasterName", 120, false));
    return row("personal_lot", id, owner);
  }

  @Transactional
  public Map<String, Object> editLot(UUID owner, UUID id, Map<String, Object> in) {
    var old = editable("personal_lot", owner, id, in);
    db.update("update personal_lot set name=?,roaster_name=?,updated_at=now(),version=version+1"
            + " where id=? and owner_id=?",
        Rules.text(in, "name", 200, true),
        in.containsKey("roasterName") ? Rules.text(in, "roasterName", 120, false) : old.get("roaster_name"),
        id, owner);
    return row("personal_lot", id, owner);
  }

  @Transactional
  public Map<String, Object> editBag(UUID owner, UUID id, Map<String, Object> in) {
    var old = editable("coffee_bag", owner, id, in);
    active("personal_lot", uuid(old.get("lot_id")), owner);
    LocalDate today = LocalDate.now(ownerZone(owner));
    db.update("update coffee_bag set mass_g=?,note=?,purchased_on=?::date,roasted_on=?::date,"
            + "purchase_price=?,updated_at=now(),version=version+1 where id=? and owner_id=?",
        Rules.number(in.getOrDefault("massG", old.get("mass_g")), "massG", 1, 5000, false),
        in.containsKey("note") ? Rules.text(in, "note", 5000, false) : old.get("note"),
        purchaseDate(in.getOrDefault("purchasedOn", old.get("purchased_on")), "purchasedOn", today),
        purchaseDate(in.getOrDefault("roastedOn", old.get("roasted_on")), "roastedOn", today),
        purchasePrice(in.getOrDefault("purchasePrice", old.get("purchase_price"))), id, owner);
    return row("coffee_bag", id, owner);
  }

  private Map<String, Object> editable(String table, UUID owner, UUID id, Map<String, Object> in) {
    db.queryForObject("select id from app_user where id=? for update", UUID.class, owner);
    var record = row(table, id, owner);
    version(record, in);
    if (record.get("deleted_at") != null)
      throw new ResponseStatusException(HttpStatus.CONFLICT, "Запись удалена");
    return record;
  }

  private String purchaseDate(Object value, String field, LocalDate today) {
    if (value == null) return null;
    try {
      LocalDate date = LocalDate.parse(value.toString());
      if (!date.isAfter(today)) return date.toString();
    } catch (java.time.format.DateTimeParseException ignored) {
      // Report the same field error for malformed dates and future dates.
    }
    throw new Rules.Invalid(field, "Укажите корректную дату не позже сегодняшней");
  }

  private java.math.BigDecimal purchasePrice(Object value) {
    if (value == null) return null;
    try {
      var price = new java.math.BigDecimal(value.toString());
      if (price.signum() >= 0) return price;
    } catch (NumberFormatException ignored) {
      // Report a field error for non-numeric input.
    }
    throw new Rules.Invalid("purchasePrice", "Укажите неотрицательную цену");
  }

  Map<String, Object> bag(UUID owner, Map<String, Object> in) {
    UUID lot;
    if (in.get("lotId") == null) {
      lot = uuid(lot(owner, in).get("id"));
    } else {
      lot = uuid(in.get("lotId"));
      active("personal_lot", lot, owner);
    }
    UUID id = UUID.randomUUID();
    String status =
        choice(
            in.getOrDefault("status", "ACTIVE"), "status", List.of("ACTIVE", "STOCK", "FINISHED"));
    var mass = Rules.number(in.getOrDefault("massG", 250), "massG", 1, 5000, false);
    if (status == null) throw new Rules.Invalid("status", "Укажите статус");
    LocalDate today = LocalDate.now(ownerZone(owner));
    db.update(
        "insert into coffee_bag(id,owner_id,lot_id,mass_g,status,opened_at,finished_at,note,"
            + "purchased_on,roasted_on,purchase_price) values(?,?,?,?,?,?,?,?,?::date,?::date,?)",
        id,
        owner,
        lot,
        mass,
        status,
        status.equals("STOCK") ? null : Timestamp.from(Instant.now()),
        status.equals("FINISHED") ? Timestamp.from(Instant.now()) : null,
        Rules.text(in, "note", 5000, false),
        purchaseDate(in.get("purchasedOn"), "purchasedOn", today),
        purchaseDate(in.get("roastedOn"), "roastedOn", today),
        purchasePrice(in.get("purchasePrice")));
    db.update(
        "insert into bag_status_event(id,bag_id,to_status) values(?,?,?)",
        UUID.randomUUID(),
        id,
        status);
    return row("coffee_bag", id, owner);
  }

  Map<String, Object> consumption(UUID owner, Map<String, Object> in, UUID tasting) {
    UUID id = UUID.randomUUID(), bag = in.get("bagId") == null ? null : uuid(in.get("bagId"));
    if (bag != null) active("coffee_bag", bag, owner);
    Map<String, Object> p = profile(owner);
    if (in.containsKey("profileId")) {
      if (in.get("profileId") == null) p = Map.of();
      else {
        p = row("brew_profile", uuid(in.get("profileId")), owner);
        if (p.get("deleted_at") != null)
          throw new ResponseStatusException(HttpStatus.CONFLICT, "Профиль архивирован");
      }
    }
    var volume =
        Rules.number(
            in.getOrDefault("volumeMl", p.getOrDefault("default_cup_volume_ml", 250)),
            "volumeMl",
            10,
            2000,
            true);
    db.update(
        "insert into"
            + " consumption_event(id,owner_id,bag_id,consumed_at,volume_ml,tasting_id,coffee_label,comment,profile_snapshot,client_event_id)"
            + " values(?,?,?,?,?,?,?,?,?::jsonb,?)",
        id,
        owner,
        bag,
        Timestamp.from(Rules.time(in.get("consumedAt"))),
        volume.intValue(),
        tasting,
        Rules.text(in, "coffeeLabel", 200, false),
        Rules.text(in, "comment", 5000, false),
        json(p),
        uuid(in.get("clientEventId")));
    return row("consumption_event", id, owner);
  }

  @Transactional
  public Map<String, Object> editConsumption(UUID owner, UUID id, Map<String, Object> in) {
    db.queryForObject("select id from app_user where id=? for update", UUID.class, owner);
    var current = row("consumption_event", id, owner);
    version(current, in);
    if (current.get("deleted_at") != null)
      throw new ResponseStatusException(HttpStatus.CONFLICT, "Запись удалена");
    if (in.get("consumedAt") == null)
      throw new Rules.Invalid("consumedAt", "Укажите время чашки");
    db.update(
        "update consumption_event set consumed_at=?,volume_ml=?,comment=?,updated_at=now(),"
            + "version=version+1 where id=? and owner_id=?",
        Timestamp.from(Rules.time(in.get("consumedAt"))),
        Rules.number(in.get("volumeMl"), "volumeMl", 10, 2000, true).intValue(),
        Rules.text(in, "comment", 5000, false), id, owner);
    return row("consumption_event", id, owner);
  }

  public Object eligibleTastings(UUID owner, UUID id) {
    var cup = row("consumption_event", id, owner);
    if (cup.get("deleted_at") != null || cup.get("bag_id") == null) return List.of();
    return rows("select t.id,t.overall_rating,t.tasted_at,t.comment from tasting t"
        + " where t.owner_id=? and t.bag_id=? and t.deleted_at is null"
        + " and not exists (select 1 from consumption_event c where c.tasting_id=t.id)"
        + " order by t.tasted_at desc,t.id", owner, cup.get("bag_id"));
  }

  @Transactional
  public Map<String, Object> changeTastingLink(UUID owner, UUID id, UUID tasting, Map<String, Object> input) {
    var cup = editable("consumption_event", owner, id, input);
    if (tasting != null) {
      var rating = row("tasting", tasting, owner);
      if (cup.get("tasting_id") != null || rating.get("deleted_at") != null
          || !Objects.equals(cup.get("bag_id"), rating.get("bag_id"))
          || Boolean.TRUE.equals(db.queryForObject(
              "select exists(select 1 from consumption_event where tasting_id=?)", Boolean.class, tasting)))
        throw new ResponseStatusException(HttpStatus.CONFLICT, "Оценку нельзя связать с этой чашкой");
    }
    db.update("update consumption_event set tasting_id=?,version=version+1,updated_at=now()"
        + " where id=? and owner_id=?", tasting, id, owner);
    return row("consumption_event", id, owner);
  }

  @Transactional
  public Map<String, Object> editTasting(UUID owner, UUID id, Map<String, Object> in) {
    db.queryForObject("select id from app_user where id=? for update", UUID.class, owner);
    var current = row("tasting", id, owner);
    version(current, in);
    if (current.get("deleted_at") != null)
      throw new ResponseStatusException(HttpStatus.CONFLICT, "Запись удалена");
    db.update(
        "update tasting set overall_rating=?,sensory=?::jsonb,repurchase=?,comment=?,"
            + "tasted_at=?::timestamptz,brew_snapshot=?::jsonb,preparation_uncertain=?,wishes=?::jsonb,"
            + "version=version+1,updated_at=now() where id=? and owner_id=?",
        Rules.number(in.get("overallRating"), "overallRating", 1, 5, true).intValue(),
        json(Rules.sensory(in.get("sensory"))),
        choice(in.get("repurchase"), "repurchase", List.of("YES", "MAYBE", "NO")),
        Rules.text(in, "comment", 5000, false),
        in.containsKey("tastedAt") ? Rules.time(in.get("tastedAt")).toString() : current.get("tasted_at"),
        json(in.containsKey("brewSnapshot") || in.containsKey("profileId")
            ? tastingRecipe(owner, in, current) : current.get("brew_snapshot")),
        in.getOrDefault("preparationUncertain", current.get("preparation_uncertain")),
        in.containsKey("wishes") ? wishes(in.get("wishes")) : json(current.get("wishes")), id, owner);
    return row("tasting", id, owner);
  }

  Map<String, Object> tasting(UUID owner, Map<String, Object> in) {
    UUID bag = uuid(in.get("bagId"));
    active("coffee_bag", bag, owner);
    UUID id = UUID.randomUUID();
    var rating = Rules.number(in.get("overallRating"), "overallRating", 1, 5, true);
    var snapshot = tastingRecipe(owner, in, null);
    db.update(
        "insert into"
            + " tasting(id,owner_id,bag_id,tasted_at,overall_rating,sensory,repurchase,comment,brew_snapshot,preparation_uncertain,wishes)"
            + " values(?,?,?,?,?,?::jsonb,?,?,?::jsonb,?,?::jsonb)",
        id,
        owner,
        bag,
        Timestamp.from(Rules.time(in.get("tastedAt"))),
        rating.intValue(),
        json(Rules.sensory(in.get("sensory"))),
        choice(in.get("repurchase"), "repurchase", List.of("YES", "MAYBE", "NO")),
        Rules.text(in, "comment", 5000, false),
        json(snapshot),
        Boolean.TRUE.equals(in.get("preparationUncertain")), wishes(in.get("wishes")));
    if (in.get("consumptionEventId") != null) {
      UUID event = uuid(in.get("consumptionEventId"));
      var e = row("consumption_event", event, owner);
      if (e.get("deleted_at") != null
          || !bag.equals(e.get("bag_id"))
          || e.get("tasting_id") != null)
        throw new ResponseStatusException(HttpStatus.CONFLICT, "Чашку нельзя связать с оценкой");
      db.update(
          "update consumption_event set tasting_id=?,version=version+1 where id=?", id, event);
    } else if (Boolean.TRUE.equals(in.get("recordConsumption"))) {
      var c = new LinkedHashMap<>(in);
      c.put("clientEventId", in.getOrDefault("clientEventId", UUID.randomUUID().toString()));
      c.put("consumedAt", in.get("tastedAt"));
      consumption(owner, c, id);
    }
    return row("tasting", id, owner);
  }

  private Map<String, Object> tastingRecipe(UUID owner, Map<String, Object> in, Map<String, Object> current) {
    var snapshot = new LinkedHashMap<String, Object>();
    if (current != null && !in.containsKey("profileId")) {
      var previous = map(json(current.get("brew_snapshot")));
      for (String key : List.of("profileName", "profileId", "grinder", "brewer"))
        if (previous.containsKey(key)) snapshot.put(key, previous.get(key));
    } else {
      var selected = in.containsKey("profileId")
          ? row("brew_profile", uuid(in.get("profileId")), owner) : profile(owner);
      if (selected.get("deleted_at") != null)
        throw new ResponseStatusException(HttpStatus.CONFLICT, "Профиль архивирован");
      snapshot.putAll(map(json(selected.get("recipe"))));
      snapshot.put("profileName", selected.get("name"));
      if (in.containsKey("profileId")) {
        snapshot.put("profileId", selected.get("id"));
        snapshot.put("grinder", selected.get("grinder"));
        snapshot.put("brewer", selected.get("brewer"));
      }
    }
    if (in.containsKey("brewSnapshot")) {
      snapshot.remove("grindClicks");
      snapshot.putAll(validateRecipe(in.get("brewSnapshot")));
    } else if (current == null) {
      var grind = lastGrind(owner, uuid(in.get("bagId")));
      if (!grind.isEmpty()) {
        snapshot.remove("grindClicks");
        snapshot.remove("grind");
        snapshot.putAll(grind);
      }
    }
    return snapshot;
  }

  public Map<String, Object> lastGrind(UUID owner, UUID bag) {
    row("coffee_bag", bag, owner);
    var previous = rows("select brew_snapshot from tasting where owner_id=? and bag_id=?"
        + " and deleted_at is null and (brew_snapshot->>'grindClicks' is not null"
        + " or nullif(brew_snapshot->>'grind','') is not null)"
        + " order by tasted_at desc,created_at desc,id limit 1", owner, bag);
    if (previous.isEmpty()) return Map.of();
    var recipe = map(json(previous.getFirst().get("brew_snapshot")));
    return recipe.get("grindClicks") != null ? Map.of("grindClicks", recipe.get("grindClicks"))
        : Map.of("grind", recipe.get("grind"));
  }

  private String wishes(Object value) {
    if (value == null) return "[]";
    if (!(value instanceof List<?> items)) throw new Rules.Invalid("wishes", "Выберите пожелания");
    var allowed = List.of("LESS_ACIDITY", "MORE_ACIDITY", "LESS_BITTERNESS", "MORE_SWEETNESS",
        "LESS_DRYNESS", "MORE_BODY", "LESS_BODY", "MORE_AROMA", "NOTHING_TO_CHANGE");
    var chosen = new LinkedHashSet<>();
    for (Object item : items) {
      if (!allowed.contains(Objects.toString(item, ""))) throw new Rules.Invalid("wishes", "Неизвестное пожелание");
      chosen.add(item);
    }
    if ((chosen.contains("NOTHING_TO_CHANGE") && chosen.size() > 1)
        || (chosen.contains("LESS_ACIDITY") && chosen.contains("MORE_ACIDITY"))
        || (chosen.contains("LESS_BODY") && chosen.contains("MORE_BODY")))
      throw new Rules.Invalid("wishes", "Пожелания противоречат друг другу");
    return json(chosen);
  }

  @Transactional
  public Map<String, Object> status(UUID owner, UUID id, Map<String, Object> in) {
    db.queryForObject("select id from app_user where id=? for update", UUID.class, owner);
    var b = row("coffee_bag", id, owner);
    version(b, in);
    String s = choice(in.get("status"), "status", List.of("STOCK", "ACTIVE", "FINISHED"));
    if (s == null) throw new Rules.Invalid("status", "Укажите статус");
    db.update(
        "update coffee_bag set status=?,opened_at=case when ?='ACTIVE' then"
            + " coalesce(opened_at,now()) else opened_at end,finished_at=case when ?='FINISHED'"
            + " then now() else null end,updated_at=now(),version=version+1 where id=?",
        s,
        s,
        s,
        id);
    db.update(
        "insert into bag_status_event(id,bag_id,from_status,to_status) values(?,?,?,?)",
        UUID.randomUUID(),
        id,
        b.get("status"),
        s);
    return row("coffee_bag", id, owner);
  }

  @Transactional
  public void deleted(UUID owner, String kind, UUID id, boolean restore, long version) {
    String table = table(kind);
    db.queryForObject("select id from app_user where id=? for update", UUID.class, owner);
    var r = row(table, id, owner);
    version(r, Map.of("version", version));
    db.update(
        "update " + table + " set deleted_at=?,updated_at=now(),version=version+1 where id=?",
        restore ? null : Timestamp.from(Instant.now()),
        id);
    if (kind.equals("tastings"))
      db.update(
          "update consumption_event set tasting_id=null,version=version+1 where owner_id=? and"
              + " tasting_id=?",
          owner,
          id);
    if (kind.equals("consumptions"))
      db.update("update consumption_event set tasting_id=null where id=?", id);
  }

  @Transactional
  public Map<String, Object> updateProfile(UUID owner, Map<String, Object> input) {
    var old = profile(owner);
    return editBrewProfile(owner, uuid(old.get("id")), input);
  }

  public Object brewProfiles(UUID owner) {
    return rows("select * from brew_profile where owner_id=? and deleted_at is null order by name,id", owner);
  }

  @Transactional
  public Object createBrewProfile(UUID owner, Map<String, Object> input) {
    UUID id = UUID.randomUUID();
    db.update("insert into brew_profile(id,owner_id,name,default_cup_volume_ml,recipe,grinder,brewer)"
            + " values(?,?,?,?,?::jsonb,?,?)",
        id, owner, Rules.text(input,"name",200,true),
        Rules.number(input.getOrDefault("defaultCupVolumeMl",250),"defaultCupVolumeMl",10,2000,true).intValue(),
        json(input.containsKey("recipe") ? validateRecipe(input.get("recipe")) : Map.of()),
        Rules.text(input,"grinder",200,false), Rules.text(input,"brewer",200,false));
    return row("brew_profile", id, owner);
  }

  @Transactional
  public Map<String, Object> setDefaultBrewProfile(UUID owner, UUID id) {
    db.queryForObject("select id from app_user where id=? for update", UUID.class, owner);
    active("brew_profile", id, owner);
    db.update("update app_user set default_brew_profile_id=? where id=?", id, owner);
    return profile(owner);
  }

  @Transactional
  public void archiveBrewProfile(UUID owner, UUID id, long version) {
    editable("brew_profile", owner, id, Map.of("version", version));
    if (id.equals(profile(owner).get("id")))
      throw new ResponseStatusException(HttpStatus.CONFLICT, "Сначала выберите другой профиль по умолчанию");
    db.update("update brew_profile set deleted_at=now(),version=version+1 where id=? and owner_id=?", id, owner);
  }

  @Transactional
  public Map<String, Object> editBrewProfile(UUID owner, UUID id, Map<String, Object> input) {
    var old = editable("brew_profile", owner, id, input);
    version(old, input);
    int volume =
        Rules.number(input.get("defaultCupVolumeMl"), "defaultCupVolumeMl", 10, 2000, true)
            .intValue();
    int count =
        db.update(
            "update brew_profile set name=?,default_cup_volume_ml=?,recipe=?::jsonb,grinder=?,brewer=?,version=version+1 where id=?"
                + " and owner_id=? and version=?",
            Rules.text(input, "name", 200, true),
            volume,
            json(input.containsKey("recipe") ? validateRecipe(input.get("recipe")) : old.get("recipe")),
            input.containsKey("grinder") ? Rules.text(input,"grinder",200,false) : old.get("grinder"),
            input.containsKey("brewer") ? Rules.text(input,"brewer",200,false) : old.get("brewer"),
            old.get("id"),
            owner,
            old.get("version"));
    if (count != 1) throw new ResponseStatusException(HttpStatus.CONFLICT);
    return row("brew_profile", id, owner);
  }

  private Map<String, Object> validateRecipe(Object value) {
    if (!(value instanceof Map<?, ?> fields))
      throw new Rules.Invalid("recipe", "Укажите параметры рецепта");
    var recipe = new LinkedHashMap<String, Object>();
    fields.forEach((key, field) -> recipe.put(key.toString(), field));
    var result = new LinkedHashMap<String, Object>();
    result.put("method", choice(recipe.get("method"), "method",
        List.of("AUTO_POUR_OVER", "POUR_OVER", "DRIP", "IMMERSION", "ESPRESSO", "OTHER")));
    result.put("milkSugar", choice(recipe.get("milkSugar"), "milkSugar",
        List.of("NONE", "MILK", "SUGAR", "BOTH", "UNKNOWN")));
    for (String key : List.of("grind", "water", "filterNote"))
      result.put(key, Rules.text(recipe, key, key.equals("filterNote") ? 500 : 200, false));
    if (recipe.get("grindClicks") != null)
      result.put("grindClicks", Rules.number(recipe.get("grindClicks"), "grindClicks", 0, Integer.MAX_VALUE, true));
    if (result.containsKey("grindClicks") && result.get("grind") instanceof String grind && !grind.isBlank())
      throw new Rules.Invalid("grind", "Укажите щелчки или текстовую настройку помола");
    if (recipe.get("doseG") != null) {
      var dose = Rules.number(recipe.get("doseG"), "doseG", 0, 200, false);
      if (dose.compareTo(new java.math.BigDecimal("0.1")) < 0)
        throw new Rules.Invalid("doseG", "Допустимый диапазон: 0.1–200");
      result.put("doseG", dose);
    }
    var limits = Map.of("waterG", 5000, "temperatureC", 100, "durationSeconds", 3600);
    limits.forEach((key, max) -> {
      if (recipe.get(key) != null)
        result.put(key, Rules.number(recipe.get(key), key, 1, max, key.equals("durationSeconds")));
    });
    return result;
  }

  String table(String kind) {
    return switch (kind) {
      case "bags" -> "coffee_bag";
      case "lots" -> "personal_lot";
      case "tastings" -> "tasting";
      case "consumptions" -> "consumption_event";
      default -> throw new ResponseStatusException(HttpStatus.NOT_FOUND);
    };
  }

  public List<Map<String, Object>> bagStatusHistory(UUID owner, UUID id) {
    row("coffee_bag", id, owner);
    return rows("select * from bag_status_event where bag_id=? order by changed_at,id", id);
  }

  public List<Map<String, Object>> list(
      UUID owner, String kind, int limit, int offset, boolean deleted, String query,
      LocalDate start, LocalDate end) {
    limit = Math.clamp(limit, 1, 100);
    offset = Math.max(0, offset);
    String condition = deleted ? " is not null" : " is null";
    if (kind.equals("consumptions")) {
      var args = new ArrayList<Object>();
      args.add(owner);
      String period = "";
      if (start != null || end != null) {
        validatePeriod(start, end);
        ZoneId zone = ownerZone(owner);
        period = " and c.consumed_at>=? and c.consumed_at<?";
        args.add(midnight(start, zone));
        args.add(midnight(end.plusDays(1), zone));
      }
      args.add(limit);
      args.add(offset);
      return rows(
          "select c.*, (b.deleted_at is not null or l.deleted_at is not null) bag_deleted,"
              + " case when b.deleted_at is null and l.deleted_at is null then l.name end bag_name"
              + " from consumption_event c left join coffee_bag b on b.id=c.bag_id and b.owner_id=c.owner_id"
              + " left join personal_lot l on l.id=b.lot_id and l.owner_id=b.owner_id"
              + " where c.owner_id=? and c.deleted_at" + condition + period
              + " order by c.consumed_at desc,c.id limit ? offset ?",
          args.toArray());
    }
    if (kind.equals("bags"))
      return rows(
          "select b.*,l.name,l.roaster_name,(select avg(t.overall_rating) from tasting t where"
              + " t.bag_id=b.id and t.deleted_at is null) rating,(select count(*) from tasting t"
              + " where t.bag_id=b.id and t.deleted_at is null) tasting_count,"
              + " (select coalesce(jsonb_object_agg(dimension,jsonb_build_object('average',mean,'count',n)),"
              + " '{}'::jsonb) from (select s.key dimension,avg((s.value->>'value')::numeric) mean,"
              + " count(*) n from tasting t cross join lateral jsonb_each(t.sensory) s"
              + " where t.bag_id=b.id and t.owner_id=b.owner_id and t.deleted_at is null"
              + " and s.value->>'status'='RATED' and s.value->>'value' is not null"
              + " group by s.key) measured) sensory_summary,(select count(*) from"
              + " consumption_event c where c.bag_id=b.id and c.deleted_at is null)"
              + " cup_count,(select id from media_asset m where m.bag_id=b.id and m.owner_id=b.owner_id"
              + " order by position,id limit 1) photo_id,"
              + " (select jsonb_build_object('ids',coalesce(jsonb_agg(m.id order by position,id),'[]'::jsonb))"
              + " from media_asset m where m.bag_id=b.id and m.owner_id=b.owner_id) photos"
              + " from coffee_bag b join personal_lot l on l.id=b.lot_id where"
              + " b.owner_id=? and b.deleted_at"
              + condition
              + " and l.deleted_at is null and (l.name ilike ? or coalesce(l.roaster_name,'') ilike"
              + " ?) order by b.updated_at desc,b.id limit ? offset ?",
          owner,
          "%" + query + "%",
          "%" + query + "%",
          limit,
          offset);
    return rows(
        "select * from "
            + table(kind)
            + " where owner_id=? and deleted_at"
            + condition
            + " order by created_at desc,id limit ? offset ?",
        owner,
        limit,
        offset);
  }

  private void validatePeriod(LocalDate start, LocalDate end) {
    if (start == null || end == null || end.isBefore(start) || start.plusYears(3).isBefore(end))
      throw new Rules.Invalid("period", "Выберите период до трёх лет");
  }

  private ZoneId ownerZone(UUID owner) {
    return ZoneId.of(db.queryForObject("select timezone from app_user where id=?", String.class, owner));
  }

  private Timestamp midnight(LocalDate date, ZoneId zone) {
    return Timestamp.from(date.atStartOfDay(zone).toInstant());
  }

  public Map<String, Object> stats(UUID owner, LocalDate start, LocalDate end) {
    validatePeriod(start, end);
    ZoneId zone = ownerZone(owner);
    var points =
        rows(
            "select (consumed_at at time zone ?)::date as local_day,count(*) cups,sum(volume_ml)"
                + " volume from consumption_event where owner_id=? and deleted_at is null and"
                + " consumed_at>=? and consumed_at<? group by local_day order by local_day",
            zone.getId(),
            owner,
            midnight(start, zone),
            midnight(end.plusDays(1), zone));
    var days = new ArrayList<Map<String, Object>>();
    long count = 0, volume = 0;
    for (LocalDate d = start; !d.isAfter(end); d = d.plusDays(1)) {
      String date = d.toString();
      var p =
          points.stream()
              .filter(x -> x.get("local_day").toString().equals(date))
              .findFirst()
              .orElse(Map.of("cups", 0, "volume", 0));
      long c = ((Number) p.get("cups")).longValue(), v = ((Number) p.get("volume")).longValue();
      count += c;
      volume += v;
      days.add(Map.of("day", date, "cups", c, "volume", v));
    }
    return Map.of("cups", count, "volume", volume, "days", days, "timezone", zone.getId());
  }
}
