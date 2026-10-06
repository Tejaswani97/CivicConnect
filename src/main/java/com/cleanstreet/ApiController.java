package com.cleanstreet;

import org.springframework.beans.factory.annotation.*;
import org.springframework.http.*;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;
import java.io.IOException;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.LinkedHashMap;
import java.util.Map;

@RestController @RequestMapping("/api")
public class ApiController {
  @Autowired UserRepo users; @Autowired ComplaintRepo complaints; @Autowired UpdateRepo updates;
  @Autowired OfficeService offices; @Autowired OtpService otp; @Autowired BCryptPasswordEncoder enc; @Autowired AuditRepo audits;
  @Value("${app.upload-dir}") String uploadDir;
  @Value("${app.categories}") String categoryCfg;
  @Value("${app.sla.low}") int slaLow; @Value("${app.sla.medium}") int slaMed; @Value("${app.sla.high}") int slaHigh; @Value("${app.sla.critical}") int slaCrit;
  @Value("${app.security.session-hours:8}") int sessionHours;
  @Value("${app.security.max-login-attempts:5}") int maxLoginAttempts;
  @Value("${app.security.lock-minutes:15}") int lockMinutes;
  private final Map<String, List<Instant>> complaintAttempts = new java.util.concurrent.ConcurrentHashMap<>();
  private static final Map<String, String> EXT = Map.of("image/jpeg", ".jpg", "image/png", ".png", "image/webp", ".webp");

  // ---------- Auth ----------
  @PostMapping("/auth/register")
  public Map<String, Object> register(@RequestBody Map<String, String> b) {
    String phone = phone(b.get("phone")), name = clean(b.get("name")), pw = b.get("password");
    if (name.length() < 2 || pw == null || !strongPassword(pw)) throw bad("Password must be 8+ characters and include uppercase, lowercase, number and special character");
    User u = users.findByPhone(phone).orElse(new User());
    if (u.verified) throw new ResponseStatusException(HttpStatus.CONFLICT, "This number is already registered. Log in instead.");
    u.name = name; u.phone = phone; u.role = "CITIZEN"; u.passwordHash = enc.encode(pw); u.failedLoginAttempts = 0; u.lockedUntil = null;
    u.otpRequestId = otp.start(phone); users.save(u);
    audit(null, "REGISTRATION_STARTED", null, "phone=" + mask(phone));
    return Map.of("message", "Verification code sent to " + mask(phone), "phone", phone);
  }
  @PostMapping("/auth/resend")
  public Map<String, Object> resend(@RequestBody Map<String, String> b) {
    String phone = phone(b.get("phone")); User u = users.findByPhone(phone).orElseThrow(() -> bad("No account for this number"));
    if (u.verified) throw new ResponseStatusException(HttpStatus.CONFLICT, "This number is already verified. Log in instead.");
    u.otpRequestId = otp.start(phone); users.save(u);
    return Map.of("message", "Verification code sent to " + mask(phone));
  }
  @PostMapping("/auth/verify")
  public Map<String, Object> verify(@RequestBody Map<String, String> b) {
    String phone = phone(b.get("phone")); User u = users.findByPhone(phone).orElseThrow(() -> bad("No account for this number"));
    if (u.verified) throw new ResponseStatusException(HttpStatus.CONFLICT, "This number is already verified. Log in instead.");
    otp.check(phone, clean(b.get("otp")), u.otpRequestId); u.otpRequestId = null; u.verified = true; u.failedLoginAttempts = 0; u.lockedUntil = null; users.save(u); audit(u, "PHONE_VERIFIED", null, "Phone verification approved"); return session(u);
  }
  @PostMapping("/auth/login")
  public Map<String, Object> login(@RequestBody Map<String, String> b) {
    String p = phone(b.get("phone"));
    User u = users.findByPhone(p).orElse(null);
    if (u == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Wrong mobile number or password");
    if (u.lockedUntil != null && Instant.now().isBefore(u.lockedUntil)) throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "Too many failed attempts. Try again later.");
    if (!enc.matches(String.valueOf(b.get("password")), u.passwordHash)) {
      u.failedLoginAttempts++;
      if (u.failedLoginAttempts >= maxLoginAttempts) { u.lockedUntil = Instant.now().plusSeconds(lockMinutes * 60L); u.failedLoginAttempts = 0; }
      users.save(u); audit(null, "LOGIN_FAILED", null, "phone=" + mask(p));
      throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Wrong mobile number or password");
    }
    if (!u.verified) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Verify your mobile number first. Use Resend code.");
    u.failedLoginAttempts = 0; u.lockedUntil = null; users.save(u);
    audit(u, "LOGIN_SUCCESS", null, "authenticated session created");
    return session(u);
  }
  @PostMapping("/auth/logout")
  public Map<String, String> logout(@RequestHeader(value = "Authorization", required = false) String h) { User u = auth(h); u.token = null; u.tokenIssuedAt = null; users.save(u); audit(u, "LOGOUT", null, "session invalidated"); return Map.of("message", "Logged out"); }
  private Map<String, Object> session(User u) { u.token = UUID.randomUUID().toString(); u.tokenIssuedAt = Instant.now(); users.save(u); return Map.of("token", u.token, "user", u); }

  // ---------- Complaints ----------
  @GetMapping("/categories")
  public List<String> categories() { return cats().keySet().stream().toList(); }

  /**
   * Location preview for citizens. Returns the nearest active configured office
   * plus its distance and whether the point is inside that office's service area.
   */
  @GetMapping("/offices/nearest")
  public Map<String, Object> nearestOffice(@RequestHeader(value = "Authorization", required = false) String h,
                                           @RequestParam double lat, @RequestParam double lng) {
    auth(h);
    if (Double.isNaN(lat) || Double.isNaN(lng) || lat < -90 || lat > 90 || lng < -180 || lng > 180) {
      throw bad("Invalid coordinates");
    }
    Office o = offices.nearestAny(lat, lng)
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No active municipal office is configured"));
    double distanceKm = OfficeService.km(lat, lng, o.lat, o.lng);
    Map<String, Object> result = new LinkedHashMap<>();

    result.put("id", o.id);
    result.put("name", o.name);
    result.put("municipality", o.municipality);
    result.put("city", o.city);
    result.put("district", o.district);
    result.put("state", o.state);
    result.put("type", o.type);
    result.put("address", o.address == null ? "" : o.address);
    result.put("lat", o.lat);
    result.put("lng", o.lng);
    result.put("distanceKm", Math.round(distanceKm * 100) / 100.0);
    result.put("serviceRadiusKm", o.serviceRadiusKm);
    result.put("withinServiceArea", distanceKm <= o.serviceRadiusKm);
    result.put("active", o.active);

    return result;
  }

  @PostMapping(value = "/complaints", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  public Complaint create(@RequestHeader(value = "Authorization", required = false) String h, @RequestParam double lat, @RequestParam double lng,
                          @RequestParam String description, @RequestParam String category, @RequestParam MultipartFile image) throws IOException {
    User u = auth(h); if (!"CITIZEN".equals(u.role)) throw forbid("Only citizens can report issues");
    enforceComplaintRateLimit(u);
    if (Double.isNaN(lat) || Double.isNaN(lng) || lat < -90 || lat > 90 || lng < -180 || lng > 180) throw bad("Invalid coordinates");
    if (description.trim().length() < 10 || description.length() > 900) throw bad("Describe the issue in 10 to 900 characters");
    String pr = cats().get(category); if (pr == null) throw bad("Choose a valid category");
    Office o = offices.nearest(lat, lng).orElseThrow(() -> new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "No municipal office configured for this area"));
    Complaint c = new Complaint(); c.description = description.trim(); c.category = category; c.priority = pr; c.lat = lat; c.lng = lng;
    c.reporterId = u.id; c.reporterName = u.name; c.officeId = o.id; c.officeName = o.name; c.imagePath = save(image, true);
    c.dueAt = c.createdAt.plus(Duration.ofHours(sla(pr))); c.number = "TMP-" + UUID.randomUUID();
    c = complaints.save(c); c.number = String.format("CS-%d-%06d", Year.now().getValue(), c.id); c = complaints.save(c);
    log(c, null, "REPORTED", "Complaint sent to " + o.name, u, null); audit(u, "COMPLAINT_CREATED", c.id, "category=" + category + ", priority=" + pr); return c;
  }
  @GetMapping("/complaints/mine")
  public List<Complaint> mine(@RequestHeader(value = "Authorization", required = false) String h) {
    User u = auth(h);
    return switch (u.role) { case "OFFICER" -> complaints.findByOfficeIdOrderByCreatedAtDesc(u.officeId);
      case "CREW" -> complaints.findByStaffIdOrderByCreatedAtDesc(u.id);
      case "ADMIN" -> complaints.findAllByOrderByCreatedAtDesc();
      default -> complaints.findByReporterIdOrderByCreatedAtDesc(u.id); };
  }
  /** Authenticated detail: only the reporter, the office's officers, the assigned crew or an admin. */
  @GetMapping("/complaints/{id}")
  public Map<String, Object> one(@RequestHeader(value = "Authorization", required = false) String h, @PathVariable Long id) {
    User u = auth(h); Complaint c = find(id); if (!canView(u, c)) throw forbid("You do not have access to this complaint");
    return Map.of("complaint", c, "timeline", updates.findByComplaintIdOrderByCreatedAtAsc(id));
  }
  /** Public, privacy-safe tracking by complaint number (no reporter details, location rounded to ~100 m). */
  @GetMapping("/track/{number}")
  public Map<String, Object> track(@PathVariable String number) {
    Complaint c = complaints.findByNumber(number.trim().toUpperCase()).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No complaint found with this number"));
    List<Map<String, Object>> tl = new ArrayList<>();
    for (StatusUpdate s : updates.findByComplaintIdOrderByCreatedAtAsc(c.id)) tl.add(Map.of("status", s.status, "by", s.byRole, "note", s.note == null ? "" : s.note, "createdAt", s.createdAt, "imagePath", ""));
    return Map.of("number", c.number, "category", c.category, "status", c.status, "createdAt", c.createdAt, "office", c.officeName, "resolvedAt", c.resolvedAt == null ? "" : c.resolvedAt,
        "lat", Math.round(c.lat * 1000) / 1000.0, "lng", Math.round(c.lng * 1000) / 1000.0, "beforePhoto", "", "timeline", tl);
  }
  @GetMapping("/complaints")
  public List<Map<String, Object>> feed() {
    List<Map<String, Object>> out = new ArrayList<>();
    for (Complaint c : complaints.findAllByOrderByCreatedAtDesc()) out.add(Map.of("id", c.id, "number", c.number, "category", c.category, "status", c.status, "officeName", c.officeName, "createdAt", c.createdAt, "imagePath", "", "description", c.description, "reporterName", "Citizen", "priority", c.priority));
    return out;
  }

  // ---------- Workflow ----------
  @GetMapping("/officer/staff")
  public List<User> staff(@RequestHeader(value = "Authorization", required = false) String h) { User u = auth(h); requireOfficer(u); return users.findByRoleAndOfficeId("CREW", u.officeId); }

  @PostMapping("/officer/complaints/{id}/assign")
  public Complaint assign(@RequestHeader(value = "Authorization", required = false) String h, @PathVariable Long id, @RequestBody Map<String, Long> b) {
    User o = auth(h); requireOfficer(o); Complaint c = find(id); if (!canView(o, c)) throw forbid("This complaint belongs to another office");
    if (!Workflow.allowed(o.role, c.status, "ASSIGNED")) throw bad("Cannot assign a complaint that is " + c.status + ". Verify it first.");
    User s = users.findById(b.get("staffId")).filter(x -> "CREW".equals(x.role) && Objects.equals(x.officeId, c.officeId)).orElseThrow(() -> bad("Choose a crew member from this office"));
    String old = c.status; c.staffId = s.id; c.staffName = s.name; c.status = "ASSIGNED"; complaints.save(c);
    log(c, old, "ASSIGNED", "Assigned to crew member", o, null); audit(o, "CREW_ASSIGNED", c.id, "crewId=" + s.id); return c;
  }
  /** Generic transition: VERIFIED/REJECTED/RESOLVED (officer), ACCEPTED/IN_PROGRESS/COMPLETED (crew), REOPENED (citizen). */
  @PostMapping(value = "/complaints/{id}/updates", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  public Complaint progress(@RequestHeader(value = "Authorization", required = false) String h, @PathVariable Long id, @RequestParam String status,
                            @RequestParam(defaultValue = "") String note, @RequestParam(required = false) MultipartFile image) throws IOException {
    User u = auth(h); Complaint c = find(id); if (!canView(u, c)) throw forbid("You do not have access to this complaint");
    if ("CREW".equals(u.role) && !Objects.equals(c.staffId, u.id)) throw forbid("This complaint is not assigned to you");
    if (!Workflow.allowed(u.role, c.status, status)) throw new ResponseStatusException(HttpStatus.CONFLICT, "Invalid status change: " + c.status + " to " + status);
    boolean hasImg = image != null && !image.isEmpty();
    if ("COMPLETED".equals(status) && !hasImg) throw bad("Upload an after-cleaning photo to complete this work");
    if (("REJECTED".equals(status) || "REOPENED".equals(status)) && note.trim().length() < 5) throw bad("Add a reason in the remarks");
    String old = c.status; c.status = status; if ("RESOLVED".equals(status)) c.resolvedAt = Instant.now(); if ("REOPENED".equals(status)) { c.resolvedAt = null; c.dueAt = Instant.now().plus(Duration.ofHours(sla(c.priority))); }
    complaints.save(c); log(c, old, status, note.trim(), u, hasImg ? save(image, false) : null); audit(u, "COMPLAINT_STATUS_CHANGED", c.id, old + "->" + status); return c;
  }
  @GetMapping("/admin/audit")
  public List<AuditEvent> audit(@RequestHeader(value = "Authorization", required = false) String h) { User u = auth(h); if (!"ADMIN".equals(u.role)) throw forbid("Admin access required"); return audits.findTop100ByOrderByCreatedAtDesc(); }

  @GetMapping("/stats")
  public Map<String, Object> stats() {
    List<Complaint> all = complaints.findAll(); Map<String, Long> by = new LinkedHashMap<>(), cat = new LinkedHashMap<>();
    for (String s : List.of("REPORTED", "VERIFIED", "ASSIGNED", "ACCEPTED", "IN_PROGRESS", "COMPLETED", "RESOLVED", "REOPENED", "REJECTED")) by.put(s, all.stream().filter(c -> c.status.equals(s)).count());
    all.forEach(c -> cat.merge(c.category, 1L, Long::sum));
    List<Complaint> done = all.stream().filter(c -> c.resolvedAt != null).toList();
    Map<String, Object> m = new LinkedHashMap<>(Map.of("total", all.size(), "byStatus", by, "byCategory", cat));
    if (done.size() >= 5) m.put("avgResolutionHours", Math.round(done.stream().mapToLong(c -> Duration.between(c.createdAt, c.resolvedAt).toHours()).average().orElse(0)));
    return m;
  }

  // ---------- helpers ----------
  private boolean canView(User u, Complaint c) {
    return switch (u.role) { case "ADMIN" -> true; case "OFFICER" -> Objects.equals(u.officeId, c.officeId);
      case "CREW" -> Objects.equals(u.id, c.staffId); default -> Objects.equals(u.id, c.reporterId); };
  }
  private Map<String, String> cats() { Map<String, String> m = new LinkedHashMap<>(); for (String p : categoryCfg.split(",")) { String[] a = p.split("\\|"); m.put(a[0].trim(), a[1].trim()); } return m; }
  private int sla(String p) { return switch (p) { case "LOW" -> slaLow; case "HIGH" -> slaHigh; case "CRITICAL" -> slaCrit; default -> slaMed; }; }
  private Complaint find(Long id) { return complaints.findById(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Complaint not found")); }
  private void log(Complaint c, String old, String status, String note, User by, String img) {
    StatusUpdate s = new StatusUpdate(); s.complaintId = c.id; s.oldStatus = old; s.status = status; s.note = note; s.byName = by.name; s.byRole = by.role; s.imagePath = img; updates.save(s);
  }
  private String save(MultipartFile f, boolean required) throws IOException {
    String ext = f == null ? null : EXT.get(f.getContentType());
    if (f == null || f.isEmpty()) { if (required) throw bad("A photo is required"); return null; }
    if (ext == null) throw bad("Only JPEG, PNG or WebP images are allowed");
    if (f.getSize() > 8L * 1024 * 1024) throw bad("Image is too large (max 8 MB)");
    Path dir = Path.of(uploadDir).toAbsolutePath(); Files.createDirectories(dir);
    String name = UUID.randomUUID() + ext; f.transferTo(dir.resolve(name)); return "/api/media/" + name; // protected media endpoint; server-generated name prevents path traversal
  }
  private User auth(String h) {
    if (h == null || !h.startsWith("Bearer ")) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Log in to continue");
    User u = users.findByToken(h.substring(7)).orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Session expired. Log in again."));
    if (u.tokenIssuedAt == null || Instant.now().isAfter(u.tokenIssuedAt.plusSeconds(sessionHours * 3600L))) { u.token = null; u.tokenIssuedAt = null; users.save(u); throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Session expired. Log in again."); }
    return u;
  }

  private boolean strongPassword(String p) { return p != null && p.length() >= 8 && p.length() <= 128 && p.matches(".*[A-Z].*") && p.matches(".*[a-z].*") && p.matches(".*\\d.*") && p.matches(".*[^A-Za-z0-9].*"); }
  private void enforceComplaintRateLimit(User u) {
    Instant now = Instant.now();
    List<Instant> list = complaintAttempts.computeIfAbsent(String.valueOf(u.id), k -> new ArrayList<>());
    synchronized (list) {
      list.removeIf(t -> t.isBefore(now.minus(Duration.ofHours(1))));
      if (list.size() >= 5) throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "Complaint submission limit reached. Please try again later.");
      list.add(now);
    }
  }
  private void audit(User actor, String action, Long complaintId, String details) {
    AuditEvent a = new AuditEvent(); a.actorId = actor == null ? null : actor.id; a.actorRole = actor == null ? "SYSTEM" : actor.role; a.action = action; a.complaintId = complaintId; a.details = details; audits.save(a);
  }
  private void requireOfficer(User u) { if (!"OFFICER".equals(u.role) && !"ADMIN".equals(u.role)) throw forbid("Officer access required"); }
  static String phone(String raw) {
    String p = raw == null ? "" : raw.replaceAll("[\\s-]", "");
    if (p.matches("[6-9]\\d{9}")) return "+91" + p;
    if (p.matches("\\+91[6-9]\\d{9}") || (p.matches("\\+[1-9]\\d{7,14}") && !p.startsWith("+91"))) return p;
    throw bad("Enter a valid mobile number (10-digit Indian number or +country code)");
  }
  static String mask(String p) { return (p.startsWith("+91") ? "+91 XXXXXX" : p.substring(0, 3) + " XXXXXX") + p.substring(p.length() - 4); }
  private static String clean(String s) { return s == null ? "" : s.trim(); }
  private static ResponseStatusException bad(String m) { return new ResponseStatusException(HttpStatus.BAD_REQUEST, m); }
  private static ResponseStatusException forbid(String m) { return new ResponseStatusException(HttpStatus.FORBIDDEN, m); }
  @ExceptionHandler(ResponseStatusException.class)
  public ResponseEntity<Map<String, String>> err(ResponseStatusException e) { return ResponseEntity.status(e.getStatusCode()).body(Map.of("error", e.getReason() == null ? "Error" : e.getReason())); }
  @ExceptionHandler(Exception.class)
  public ResponseEntity<Map<String, String>> fallback(Exception e) { return ResponseEntity.status(500).body(Map.of("error", "Something went wrong. Please try again.")); }
}
