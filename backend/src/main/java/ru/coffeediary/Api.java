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
  final MediaService media;
  final CatalogService catalogs;
  final DiscoveryService discovery;

  public Api(DiaryService service, MediaService media, CatalogService catalogs, DiscoveryService discovery) {
    this.service = service;
    this.media = media;
    this.catalogs = catalogs;
    this.discovery = discovery;
  }

  UUID owner(Principal p) {
    return service.owner(p.getName());
  }

  @PostMapping(value = "/media", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  @ResponseStatus(HttpStatus.CREATED)
  public Object uploadMedia(Principal p, @RequestParam org.springframework.web.multipart.MultipartFile file)
      throws java.io.IOException {
    return media.upload(owner(p), file.getBytes());
  }

  @GetMapping("/media/{id}")
  public ResponseEntity<org.springframework.core.io.FileSystemResource> readMedia(
      Principal p, @PathVariable UUID id, @RequestParam(defaultValue = "false") boolean thumbnail) {
    return ResponseEntity.ok().contentType(MediaType.IMAGE_PNG)
        .cacheControl(CacheControl.noStore()).body(media.read(owner(p), id, thumbnail));
  }

  @DeleteMapping("/media/{id}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void deleteMedia(Principal p, @PathVariable UUID id) {
    media.delete(owner(p), id);
  }

  record Photos(List<UUID> mediaIds) {}

  @PutMapping("/bags/{id}/photos")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void attachPhotos(Principal p, @PathVariable UUID id, @RequestBody Photos photos) {
    media.attach(owner(p), id, photos.mediaIds());
  }

  @ExceptionHandler(org.springframework.web.multipart.MaxUploadSizeExceededException.class)
  public ResponseEntity<?> uploadTooLarge() {
    return error(413, "FILE_TOO_LARGE", "Размер фото не должен превышать 10 МиБ", Map.of());
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

  @GetMapping("/brew-profiles")
  public Object brewProfiles(Principal p) {
    return service.brewProfiles(owner(p));
  }

  @PostMapping("/brew-profiles")
  public Object createBrewProfile(Principal p, @RequestBody Map<String, Object> input) {
    return service.createBrewProfile(owner(p), input);
  }

  @PatchMapping("/brew-profiles/{id}")
  public Object editBrewProfile(Principal p, @PathVariable UUID id, @RequestBody Map<String, Object> input) {
    return service.editBrewProfile(owner(p), id, input);
  }

  @DeleteMapping("/brew-profiles/{id}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void archiveBrewProfile(Principal p, @PathVariable UUID id, @RequestParam long version) {
    service.archiveBrewProfile(owner(p), id, version);
  }

  @PutMapping("/profile/default-brew-profile")
  public Object defaultBrewProfile(Principal p, @RequestBody Map<String, Object> input) {
    return service.setDefaultBrewProfile(owner(p), service.uuid(input.get("profileId")));
  }

  @GetMapping("/{kind:bags|lots|tastings|consumptions}")
  public Object list(
      Principal p,
      @PathVariable String kind,
      @RequestParam(defaultValue = "20") int limit,
      @RequestParam(defaultValue = "0") int offset,
      @RequestParam(defaultValue = "false") boolean deleted,
      @RequestParam(defaultValue = "") String q,
      @RequestParam(required = false) LocalDate start,
      @RequestParam(required = false) LocalDate end,
      @RequestParam(required = false) String status,
      @RequestParam(required = false) Integer rating,
      @RequestParam(required = false) String coffee,
      @RequestParam(required = false) String sort,
      @RequestParam(required = false) UUID profileId, @RequestParam(required = false) UUID bagId,
      @RequestParam(required = false) UUID lotId) {
    return service.list(owner(p), kind, limit, offset, deleted, q, start, end, status, rating, coffee, sort,
        new DiaryService.CupScope(profileId, bagId, lotId));
  }

  @GetMapping("/{kind:bags|lots|tastings|consumptions}/{id}")
  public Object get(Principal p, @PathVariable String kind, @PathVariable UUID id) {
    return service.row(service.table(kind), id, owner(p));
  }

  @PostMapping("/{kind:lots|bags|tastings|consumptions}")
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

  @PatchMapping("/lots/{id}")
  public Object editLot(Principal p, @PathVariable UUID id, @RequestBody Map<String, Object> in) {
    return service.editLot(owner(p), id, in);
  }

  @PatchMapping("/bags/{id}")
  public Object editBag(Principal p, @PathVariable UUID id, @RequestBody Map<String, Object> in) {
    return service.editBag(owner(p), id, in);
  }

  @GetMapping("/bags/{id}/status-history")
  public Object bagStatusHistory(Principal p, @PathVariable UUID id) {
    return service.bagStatusHistory(owner(p), id);
  }

  @PutMapping("/bags/{id}/final-impression")
  public Object finalImpression(Principal p, @PathVariable UUID id, @RequestBody Map<String, Object> input) {
    return service.finalImpression(owner(p), id, input);
  }

  @PatchMapping("/consumptions/{id}")
  public Object editConsumption(Principal p, @PathVariable UUID id, @RequestBody Map<String, Object> in) {
    return service.editConsumption(owner(p), id, in);
  }

  @GetMapping("/consumptions/{id}/eligible-tastings")
  public Object eligibleTastings(Principal p, @PathVariable UUID id) {
    return service.eligibleTastings(owner(p), id);
  }

  @PutMapping("/consumptions/{id}/tasting-link")
  public Object linkTasting(Principal p, @PathVariable UUID id, @RequestBody Map<String, Object> input) {
    return service.changeTastingLink(owner(p), id, service.uuid(input.get("tastingId")), input);
  }

  @DeleteMapping("/consumptions/{id}/tasting-link")
  public Object unlinkTasting(Principal p, @PathVariable UUID id, @RequestParam long version) {
    return service.changeTastingLink(owner(p), id, null, Map.of("version", version));
  }

  @GetMapping("/bags/{id}/last-grind")
  public Object lastGrind(Principal p, @PathVariable UUID id) {
    return service.lastGrind(owner(p), id);
  }

  @PatchMapping("/tastings/{id}")
  public Object editTasting(Principal p, @PathVariable UUID id, @RequestBody Map<String, Object> in) {
    return service.editTasting(owner(p), id, in);
  }

  @DeleteMapping("/{kind:bags|lots|tastings|consumptions}/{id}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void delete(
      Principal p, @PathVariable String kind, @PathVariable UUID id, @RequestParam long version) {
    service.deleted(owner(p), kind, id, false, version);
  }

  @PostMapping("/{kind:bags|lots|tastings|consumptions}/{id}/restore")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void restore(
      Principal p,
      @PathVariable String kind,
      @PathVariable UUID id,
      @RequestBody Map<String, Object> in) {
    service.deleted(owner(p), kind, id, true, ((Number) in.get("version")).longValue());
  }

  @GetMapping("/consumption-stats")
  public Object stats(Principal p, @RequestParam LocalDate start, @RequestParam LocalDate end,
      @RequestParam(defaultValue = "") String q, @RequestParam(required = false) String coffee,
      @RequestParam(required = false) UUID profileId, @RequestParam(required = false) UUID bagId,
      @RequestParam(required = false) UUID lotId) {
    return service.stats(owner(p), start, end, q, coffee, new DiaryService.CupScope(profileId, bagId, lotId));
  }

  @GetMapping("/catalog/sources")
  public Object sources() {
    return catalogs.sources();
  }

  @GetMapping("/catalog/products")
  public Object catalog(@RequestParam(defaultValue = "") String q) {
    return catalogs.products(q);
  }

  @GetMapping("/catalog/products/{id}")
  public Object catalogProduct(@PathVariable UUID id) {
    return catalogs.product(id);
  }

  @GetMapping("/catalog/products/{id}/reviews")
  public Object catalogReviews(@PathVariable UUID id) {
    return catalogs.reviews(id);
  }

  @GetMapping("/catalog/products/{id}/review-features")
  public Object reviewFeatures(@PathVariable UUID id) {
    return catalogs.reviewFeatures(id);
  }

  @PostMapping("/catalog/products/{id}/reviews/import")
  public Object importCatalogReviews(@PathVariable UUID id, @RequestBody CatalogService.ReviewImport input) {
    return catalogs.importReviews(id, input);
  }

  @PostMapping("/catalog/sources/{code}/sync")
  @ResponseStatus(HttpStatus.ACCEPTED)
  public Object syncCatalog(@PathVariable String code) {
    return Map.of("runId", catalogs.start(code));
  }

  @GetMapping("/catalog/import-runs/{id}")
  public Object importRun(@PathVariable UUID id) {
    return catalogs.run(id);
  }

  @PostMapping("/catalog/resolve-url")
  public Object resolveCatalog(@RequestBody Map<String, Object> input) {
    return catalogs.resolve(Rules.text(input, "url", 1500, true));
  }

  @PutMapping("/lots/{id}/catalog-link")
  public Object linkCatalog(Principal p, @PathVariable UUID id, @RequestBody Map<String, Object> input) {
    UUID productId = service.uuid(input.get("productId"));
    catalogs.product(productId);
    return service.linkCatalog(owner(p), id, productId, input);
  }

  @DeleteMapping("/lots/{id}/catalog-link")
  public Object unlinkCatalog(Principal p, @PathVariable UUID id, @RequestParam long version) {
    return service.linkCatalog(owner(p), id, null, Map.of("version", version));
  }

  @GetMapping("/recommendations")
  public Object recommendations(Principal p,
      @RequestParam(defaultValue = "false") boolean includeUnknownRoast,
      @RequestParam(defaultValue = "true") boolean includeUnverified,
      @RequestParam(required = false) java.math.BigDecimal budget,
      @RequestParam(defaultValue = "FILTER") String roast) {
    return discovery.recommend(owner(p), includeUnknownRoast, includeUnverified, budget, roast);
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
