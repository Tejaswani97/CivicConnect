package com.cleanstreet;
import jakarta.persistence.*;
@Entity @Table(indexes = @Index(columnList = "active"))
public class Office {
  @Id @GeneratedValue(strategy = GenerationType.IDENTITY) public Long id;
  public String name, municipality, city, district, state, country = "India", type = "CORPORATION", address, phone, email;
  public double lat, lng;
  public double serviceRadiusKm = 25;
  public boolean active = true;
}
