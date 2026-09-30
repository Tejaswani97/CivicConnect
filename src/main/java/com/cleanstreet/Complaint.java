package com.cleanstreet;
import jakarta.persistence.*;
import java.time.Instant;
@Entity @Table(indexes = {@Index(columnList = "number", unique = true), @Index(columnList = "status"), @Index(columnList = "officeId"), @Index(columnList = "reporterId"), @Index(columnList = "staffId"), @Index(columnList = "createdAt")})
public class Complaint {
  @Id @GeneratedValue(strategy = GenerationType.IDENTITY) public Long id;
  public String number; // public tracking ID, e.g. CS-2026-000123
  public String category, priority = "MEDIUM";
  @Column(length = 1000) public String description;
  public double lat, lng;
  public String imagePath;
  public String status = "REPORTED";
  public Instant createdAt = Instant.now(), dueAt, resolvedAt;
  public Long reporterId; public String reporterName;
  public Long officeId; public String officeName;
  public Long staffId; public String staffName;
}
