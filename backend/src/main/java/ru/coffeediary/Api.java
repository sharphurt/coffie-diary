package ru.coffeediary;

import java.security.Principal;
import java.time.*;
import java.util.*;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.*;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/v1")
public class Api {
  final DiaryService service;

  public Api(DiaryService service) {
    this.service = service;
  }

  UUID owner(Principal p) {
    return service.owner(p.getName());
  }

  @GetMapping("/auth/csrf")
  public Map<String, String> csrf(CsrfToken token) {
    return Map.of("token", token.getToken(), "headerName", token.getHeaderName());
  }

  @GetMapping("/auth/me")
  public Map<String, Object> me(Principal p) {
    return Map.of("login", p.getName(), "id", owner(p));
  }

  @GetMapping("/profile")
  public Map<String, Object> profile(Principal p) {
    return service.profile(owner(p));
  }

  @PatchMapping("/profile")
  public Object profile(Principal p, @RequestBody Map<String, Object> input) {
    return service.updateProfile(owner(p), input);
  }

  @GetMapping("/{kind:bags|lots|tastings|consumptions}")
  public Object list(
      Principal p,
      @PathVariable String kind,
      @RequestParam(defaultValue = "20") int limit,
      @RequestParam(defaultValue = "0") int offset,
      @RequestParam(defaultValue = "false") boolean deleted,
      @RequestParam(defaultValue = "") String q) {
    return service.list(owner(p), kind, limit, offset, deleted, q);
  }

  @GetMapping("/{kind:bags|lots|tastings|consumptions}/{id}")
  public Object get(Principal p, @PathVariable String kind, @PathVariable UUID id) {
    return service.row(service.table(kind), id, owner(p));
  }

  @PostMapping("/{kind:bags|tastings|consumptions}")
  public Object create(
      Principal p,
      @PathVariable String kind,
      @RequestBody Map<String, Object> input,
      @RequestHeader(value = "Idempotency-Key", required = false) String key) {
    return service.create(
        kind,
        owner(p),
        input,
        kind.equals("consumptions") ? Objects.toString(input.get("clientEventId"), null) : key);
  }

  @PostMapping("/bags/{id}/status")
  public Object status(Principal p, @PathVariable UUID id, @RequestBody Map<String, Object> in) {
    return service.status(owner(p), id, in);
  }

  @DeleteMapping("/{kind:bags|lots|tastings|consumptions}/{id}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void delete(
      Principal p, @PathVariable String kind, @PathVariable UUID id, @RequestParam long version) {
    service.deleted(owner(p), kind, id, false, version);
  }

  @PostMapping("/{kind:bags|lots|tastings|consumptions}/{id}/restore")
  public void restore(
      Principal p,
      @PathVariable String kind,
      @PathVariable UUID id,
      @RequestBody Map<String, Object> in) {
    service.deleted(owner(p), kind, id, true, ((Number) in.get("version")).longValue());
  }

  @GetMapping("/consumption-stats")
  public Object stats(Principal p, @RequestParam LocalDate start, @RequestParam LocalDate end) {
    return service.stats(owner(p), start, end);
  }

  @GetMapping("/catalog/sources")
  public Object sources() {
    return List.of(
        Map.of("code", "FOLK", "status", "NEVER_SYNCED", "reviewsStatus", "UNKNOWN"),
        Map.of("code", "TASTY", "status", "NEVER_SYNCED", "reviewsStatus", "UNKNOWN"));
  }

  @GetMapping("/catalog/products")
  public Object catalog() {
    return List.of();
  }

  @GetMapping("/recommendations")
  public Object recommendations() {
    return Map.of(
        "items",
        List.of(),
        "message",
        "Каталог ещё не загружен. Персональные рекомендации появятся после импорта каталога и"
            + " привязки оценённых покупок.");
  }

  @ExceptionHandler(Rules.Invalid.class)
  public ResponseEntity<?> invalid(Rules.Invalid e) {
    return error(422, "VALIDATION", e.getMessage(), Map.of(e.field, e.getMessage()));
  }

  @ExceptionHandler(ResponseStatusException.class)
  public ResponseEntity<?> status(ResponseStatusException e) {
    return error(
        e.getStatusCode().value(),
        "REQUEST_FAILED",
        Objects.toString(e.getReason(), "Запись недоступна"),
        Map.of());
  }

  @ExceptionHandler({DataIntegrityViolationException.class, IllegalArgumentException.class})
  public ResponseEntity<?> integrity(Exception e) {
    return error(409, "CONFLICT", "Проверьте данные или обновите страницу", Map.of());
  }

  ResponseEntity<?> error(int status, String code, String message, Map<String, String> fields) {
    return ResponseEntity.status(status)
        .body(
            Map.of(
                "code",
                code,
                "message",
                message,
                "fieldErrors",
                fields,
                "requestId",
                UUID.randomUUID().toString()));
  }
}
