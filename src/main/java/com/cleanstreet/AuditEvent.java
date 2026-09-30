package com.cleanstreet;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(indexes = {
  @Index(columnList = "createdAt"),
  @Index(columnList = "actorId"),
  @Index(columnList = "action")
})
public class AuditEvent {
  @Id @GeneratedValue(strategy = GenerationType.IDENTITY) public Long id;
  public Long actorId;
  public String actorRole;
  public String action;
  public Long complaintId;
  @Column(length = 500) public String details;
  public Instant createdAt = Instant.now();
}
