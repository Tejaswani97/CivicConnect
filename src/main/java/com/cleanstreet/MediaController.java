package com.cleanstreet;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.core.io.UrlResource;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.nio.file.*;
import java.util.*;

@RestController
@RequestMapping("/api/media")
public class MediaController {
  private final UserRepo users;
  private final ComplaintRepo complaints;
  private final UpdateRepo updates;
  @Value("${app.upload-dir}") String uploadDir;

  public MediaController(UserRepo users, ComplaintRepo complaints, UpdateRepo updates) {
    this.users = users; this.complaints = complaints; this.updates = updates;
  }

  @GetMapping("/{filename}")
  public ResponseEntity<Resource> get(@RequestHeader(value="Authorization", required=false) String h, @PathVariable String filename) throws Exception {
    User u = authenticate(h);
    if (!filename.matches("[0-9a-fA-F-]{36}\\.(jpg|png|webp)")) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Media not found");
    String path = "/api/media/" + filename;
    boolean owned = false;
    for (Complaint c : complaints.findAll()) {
      if (path.equals(c.imagePath)) { owned = canView(u, c); break; }
    }
    if (!owned) {
      for (StatusUpdate s : updates.findAll()) {
        if (path.equals(s.imagePath)) {
          Complaint c = complaints.findById(s.complaintId).orElse(null);
          owned = c != null && canView(u, c); break;
        }
      }
    }
    if (!owned) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "You do not have access to this media");
    Path file = Path.of(uploadDir).toAbsolutePath().resolve(filename).normalize();
    if (!file.startsWith(Path.of(uploadDir).toAbsolutePath().normalize()) || !Files.exists(file)) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Media not found");
    Resource resource = new UrlResource(file.toUri());
    String type = Files.probeContentType(file);
    MediaType mt = type == null ? MediaType.APPLICATION_OCTET_STREAM : MediaType.parseMediaType(type);
    return ResponseEntity.ok().cacheControl(CacheControl.noStore()).contentType(mt).body(resource);
  }

  private User authenticate(String h) {
    if (h == null || !h.startsWith("Bearer ")) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Log in to view evidence");
    return users.findByToken(h.substring(7)).filter(u -> u.tokenIssuedAt != null && java.time.Instant.now().isBefore(u.tokenIssuedAt.plusSeconds(8*3600L))).orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Session expired. Log in again."));
  }

  private boolean canView(User u, Complaint c) {
    return switch (u.role) { case "ADMIN" -> true; case "OFFICER" -> Objects.equals(u.officeId, c.officeId); case "CREW" -> Objects.equals(u.id, c.staffId); default -> Objects.equals(u.id, c.reporterId); };
  }
}
