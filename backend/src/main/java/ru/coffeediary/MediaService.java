package ru.coffeediary;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.server.ResponseStatusException;

@Service
public class MediaService {
  private final JdbcTemplate db;
  private final Path root;

  MediaService(JdbcTemplate db, @Value("${app.media-path}") String path) {
    this.db = db;
    root = Path.of(path).toAbsolutePath().normalize();
  }

  private Path file(UUID id, boolean thumb) {
    return root.resolve(id + (thumb ? "-thumb.png" : ".png"));
  }

  private Map<String, Object> asset(UUID owner, UUID id) {
    return db.queryForList("select id,bag_id from media_asset where id=? and owner_id=?", id, owner)
        .stream().findFirst().orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
  }

  @Transactional(rollbackFor = IOException.class)
  public Map<String, Object> upload(UUID owner, byte[] bytes) throws IOException {
    UUID id = UUID.randomUUID();
    Files.createDirectories(root);
    TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
      @Override public void afterCompletion(int status) {
        if (status != STATUS_COMMITTED) removeFiles(id);
      }
    });
    PhotoImages.write(bytes, file(id, false), file(id, true));
    db.update("insert into media_asset(id,owner_id,storage_key) values(?,?,?)", id, owner, id.toString());
    return Map.of("id", id, "previewUrl", "/api/v1/media/" + id + "?thumbnail=true");
  }

  public FileSystemResource read(UUID owner, UUID id, boolean thumb) {
    asset(owner, id);
    var resource = new FileSystemResource(file(id, thumb));
    if (!resource.isReadable()) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
    return resource;
  }

  private void lockOwner(UUID owner) {
    db.queryForObject("select id from app_user where id=? for update", UUID.class, owner);
  }

  @Transactional
  public void attach(UUID owner, UUID bag, List<UUID> ids) {
    if (ids == null || ids.size() > 5 || ids.contains(null) || new HashSet<>(ids).size() != ids.size())
      throw new Rules.Invalid("mediaIds", "Выберите до пяти разных фото");
    lockOwner(owner);
    if (db.queryForList("select b.id from coffee_bag b join personal_lot l on l.id=b.lot_id"
        + " where b.id=? and b.owner_id=? and b.deleted_at is null and l.deleted_at is null",
        bag, owner).isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
    for (UUID id : ids) {
      var matches = db.queryForList(
          "select bag_id from media_asset where id=? and owner_id=? for update", id, owner);
      if (matches.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
      Object attached = matches.getFirst().get("bag_id");
      if (attached != null && !bag.equals(attached))
        throw new ResponseStatusException(HttpStatus.CONFLICT, "Фото уже привязано к другой пачке");
    }
    db.update("update media_asset set bag_id=null, created_at=now() where bag_id=? and owner_id=?", bag, owner);
    for (int i = 0; i < ids.size(); i++)
      db.update("update media_asset set bag_id=?,position=? where id=? and owner_id=?", bag, i, ids.get(i), owner);
  }

  @Transactional
  public void delete(UUID owner, UUID id) {
    lockOwner(owner);
    asset(owner, id);
    db.update("delete from media_asset where id=? and owner_id=?", id, owner);
    deleteAfterCommit(List.of(id));
  }

  @Scheduled(fixedDelay = 3600000)
  @Transactional
  public void purgePending() {
    var expired = db.queryForList(
        "delete from media_asset where bag_id is null and created_at < now() - interval '24 hours' returning id",
        UUID.class);
    deleteAfterCommit(expired);
  }

  private void deleteAfterCommit(List<UUID> ids) {
    TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
      @Override public void afterCommit() { ids.forEach(MediaService.this::removeFiles); }
    });
  }

  private void removeFiles(UUID id) {
    for (boolean thumb : new boolean[] {false, true}) {
      try { Files.deleteIfExists(file(id, thumb)); }
      catch (IOException e) {
        System.getLogger(MediaService.class.getName()).log(System.Logger.Level.WARNING,
            "Could not remove media file " + id, e);
      }
    }
  }
}
