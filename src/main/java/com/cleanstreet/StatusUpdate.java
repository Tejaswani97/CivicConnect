package com.cleanstreet;
import jakarta.persistence.*;
import java.time.Instant;
@Entity @Table(indexes = @Index(columnList = "complaintId"))
public class StatusUpdate { // permanent, append-only status history
  @Id @GeneratedValue(strategy = GenerationType.IDENTITY) public Long id;
  public Long complaintId;
  public String oldStatus, status, byName, byRole, imagePath;
  @Column(length = 500) public String note;
  public Instant createdAt = Instant.now();
}
