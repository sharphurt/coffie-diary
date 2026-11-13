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
                    ? map(v)
                    : v instanceof Timestamp t ? t.toInstant().toString() : v));
    return out;
  }

  List<Map<String, Object>> rows(String sql, Object... args) {
    return db.queryForList(sql, args).stream().map(this::clean).toList();
  }

  Map<String, Object> profile(UUID owner) {
    return clean(
        db.queryForMap(
            "select * from brew_profile where owner_id=? and deleted_at is null order by id limit"
                + " 1",
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
          case "bags" -> bag(owner, input);
          case "consumptions" -> consumption(owner, input, null);
          case "tastings" -> tasting(owner, input);
          default -> throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        };
    db.update("insert into idempotency values(?,?,?,?::jsonb)", owner, key, hash, json(result));
    return result;
  }

  Map<String, Object> bag(UUID owner, Map<String, Object> in) {
    UUID lot;
    if (in.get("lotId") == null) {
      lot = UUID.randomUUID();
      db.update(
          "insert into personal_lot(id,owner_id,name,roaster_name) values(?,?,?,?)",
          lot,
          owner,
          Rules.text(in, "name", 200, true),
          Rules.text(in, "roasterName", 120, false));
    } else {
      lot = uuid(in.get("lotId"));
      active("personal_lot", lot, owner);
    }
    UUID id = UUID.randomUUID();
    String status =
        choice(
            in.getOrDefault("status", "ACTIVE"), "status", List.of("ACTIVE", "STOCK", "FINISHED"));
    var mass = Rules.number(in.getOrDefault("massG", 250), "massG", 1, 5000, false);
    db.update(
        "insert into coffee_bag(id,owner_id,lot_id,mass_g,status,opened_at,finished_at,note)"
            + " values(?,?,?,?,?, ?,?,?)",
        id,
        owner,
        lot,
        mass,
        status,
        status.equals("STOCK") ? null : Timestamp.from(Instant.now()),
        status.equals("FINISHED") ? Timestamp.from(Instant.now()) : null,
        Rules.text(in, "note", 5000, false));
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
    var p = profile(owner);
    var volume =
        Rules.number(
            in.getOrDefault("volumeMl", p.get("default_cup_volume_ml")),
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

  Map<String, Object> tasting(UUID owner, Map<String, Object> in) {
    UUID bag = uuid(in.get("bagId"));
    active("coffee_bag", bag, owner);
    UUID id = UUID.randomUUID();
    var rating = Rules.number(in.get("overallRating"), "overallRating", 1, 5, true);
    var snapshot = in.getOrDefault("brewSnapshot", profile(owner).get("recipe"));
    db.update(
        "insert into"
            + " tasting(id,owner_id,bag_id,tasted_at,overall_rating,sensory,repurchase,comment,brew_snapshot,preparation_uncertain)"
            + " values(?,?,?,?,?,?::jsonb,?,?,?::jsonb,?)",
        id,
        owner,
        bag,
        Timestamp.from(Rules.time(in.get("tastedAt"))),
        rating.intValue(),
        json(Rules.sensory(in.get("sensory"))),
        choice(in.get("repurchase"), "repurchase", List.of("YES", "MAYBE", "NO")),
        Rules.text(in, "comment", 5000, false),
        json(snapshot),
        Boolean.TRUE.equals(in.get("preparationUncertain")));
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
    version(old, input);
    int volume =
        Rules.number(input.get("defaultCupVolumeMl"), "defaultCupVolumeMl", 10, 2000, true)
            .intValue();
    int count =
        db.update(
            "update brew_profile set name=?,default_cup_volume_ml=?,version=version+1 where id=?"
                + " and owner_id=? and version=?",
            Rules.text(input, "name", 200, true),
            volume,
            old.get("id"),
            owner,
            old.get("version"));
    if (count != 1) throw new ResponseStatusException(HttpStatus.CONFLICT);
    return profile(owner);
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

  public List<Map<String, Object>> list(
      UUID owner, String kind, int limit, int offset, boolean deleted, String query) {
    limit = Math.clamp(limit, 1, 100);
    offset = Math.max(0, offset);
    String condition = deleted ? " is not null" : " is null";
    if (kind.equals("bags"))
      return rows(
          "select b.*,l.name,l.roaster_name,(select avg(t.overall_rating) from tasting t where"
              + " t.bag_id=b.id and t.deleted_at is null) rating,(select count(*) from tasting t"
              + " where t.bag_id=b.id and t.deleted_at is null) tasting_count,(select count(*) from"
              + " consumption_event c where c.bag_id=b.id and c.deleted_at is null)"
              + " cup_count,(select id from media_asset m where m.bag_id=b.id order by created_at"
              + " limit 1) photo_id from coffee_bag b join personal_lot l on l.id=b.lot_id where"
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

  public Map<String, Object> stats(UUID owner, LocalDate start, LocalDate end) {
    if (end.isBefore(start) || start.plusYears(3).isBefore(end))
      throw new Rules.Invalid("period", "Выберите период до трёх лет");
    String zone =
        db.queryForObject("select timezone from app_user where id=?", String.class, owner);
    var points =
        rows(
            "select (consumed_at at time zone ?)::date as local_day,count(*) cups,sum(volume_ml)"
                + " volume from consumption_event where owner_id=? and deleted_at is null and"
                + " consumed_at>=? and consumed_at<? group by local_day order by local_day",
            zone,
            owner,
            Timestamp.from(start.atStartOfDay(ZoneId.of(zone)).toInstant()),
            Timestamp.from(end.plusDays(1).atStartOfDay(ZoneId.of(zone)).toInstant()));
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
    return Map.of("cups", count, "volume", volume, "days", days, "timezone", zone);
  }
}
