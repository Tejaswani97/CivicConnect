package com.cleanstreet;
import org.springframework.stereotype.Service;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Office import + nearest-office routing. The CSV format lets any official dataset be imported later. */
@Service
public class OfficeService {
  private final OfficeRepo repo;
  public OfficeService(OfficeRepo repo) { this.repo = repo; }

  /** Nearest ACTIVE office whose service radius contains the point; empty if the area is not served. */
  public Optional<Office> nearest(double lat, double lng) { return nearest(repo.findAll(), lat, lng); }

  /** Nearest ACTIVE office regardless of service radius, for location preview. */
  public Optional<Office> nearestAny(double lat, double lng) {
    return repo.findAll().stream().filter(o -> o.active)
        .min(Comparator.comparingDouble(o -> km(lat, lng, o.lat, o.lng)));
  }

  static Optional<Office> nearest(List<Office> all, double lat, double lng) {
    return all.stream().filter(o -> o.active).filter(o -> km(lat, lng, o.lat, o.lng) <= o.serviceRadiusKm)
        .min(Comparator.comparingDouble(o -> km(lat, lng, o.lat, o.lng)));
  }
  static double km(double lat1, double lon1, double lat2, double lon2) { // Haversine
    double dLat = Math.toRadians(lat2 - lat1), dLon = Math.toRadians(lon2 - lon1);
    double a = Math.pow(Math.sin(dLat / 2), 2) + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) * Math.pow(Math.sin(dLon / 2), 2);
    return 6371.0 * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
  }
  /** Columns: name,municipality,city,district,state,type,lat,lng,radiusKm,address (header row required). */
  public int importCsv(InputStream in) throws IOException {
    int n = 0;
    try (BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
      r.readLine();
      for (String line; (line = r.readLine()) != null; ) {
        if (line.isBlank()) continue;
        String[] c = line.split(",", -1); Office o = new Office();
        o.name = c[0]; o.municipality = c[1]; o.city = c[2]; o.district = c[3]; o.state = c[4]; o.type = c[5];
        o.lat = Double.parseDouble(c[6]); o.lng = Double.parseDouble(c[7]); o.serviceRadiusKm = Double.parseDouble(c[8]); o.address = c[9];
        repo.save(o); n++;
      }
    }
    return n;
  }
}
