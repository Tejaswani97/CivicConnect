package com.cleanstreet;
import jakarta.persistence.*;
@Entity @Table(name = "users")
public class User {
  @Id @GeneratedValue(strategy = GenerationType.IDENTITY) public Long id;
  public String name;
  @Column(unique = true) public String phone;
  @com.fasterxml.jackson.annotation.JsonIgnore public String passwordHash;
  public String role; // CITIZEN, OFFICER, CREW, ADMIN
  public boolean verified;
  public Long officeId;
  @com.fasterxml.jackson.annotation.JsonIgnore public String token;
  @com.fasterxml.jackson.annotation.JsonIgnore public java.time.Instant tokenIssuedAt;
  @com.fasterxml.jackson.annotation.JsonIgnore public int failedLoginAttempts;
  @com.fasterxml.jackson.annotation.JsonIgnore public java.time.Instant lockedUntil;
  @com.fasterxml.jackson.annotation.JsonIgnore public String otpRequestId;
}
