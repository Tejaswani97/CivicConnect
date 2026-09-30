package com.cleanstreet;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class OfficeServiceTest {
  static Office o(String n, double lat, double lng, double r, boolean active) { Office x = new Office(); x.name = n; x.lat = lat; x.lng = lng; x.serviceRadiusKm = r; x.active = active; return x; }
  final List<Office> list = List.of(o("Visakhapatnam", 17.6868, 83.2185, 30, true), o("Vijayawada", 16.5062, 80.6480, 25, true), o("Anakapalle", 17.6910, 83.0037, 12, true));

  // Haversine gives the great-circle (straight-line) distance over the Earth's surface, NOT road distance.
  // Visakhapatnam (17.6868, 83.2185) to Vijayawada (16.5062, 80.6480) is ~303 km in a straight line;
  // the driving route is longer (roughly 350 km). Expected value independently cross-checked with a separate calculation.
  @Test void haversineVisakhapatnamToVijayawadaIsAbout303KmStraightLine() { assertEquals(303.1, OfficeService.km(17.6868, 83.2185, 16.5062, 80.6480), 1.0); }
  @Test void vizagComplaintGoesToVisakhapatnamNotVijayawada() { assertEquals("Visakhapatnam", OfficeService.nearest(list, 17.72, 83.30).orElseThrow().name); }
  @Test void anakapalleComplaintGoesToAnakapalle() { assertEquals("Anakapalle", OfficeService.nearest(list, 17.69, 83.01).orElseThrow().name); }
  @Test void pointOutsideEveryServiceAreaHasNoOffice() { assertTrue(OfficeService.nearest(list, 21.25, 81.63).isEmpty()); } // Raipur
  @Test void inactiveOfficesAreIgnored() { assertTrue(OfficeService.nearest(List.of(o("X", 17.6868, 83.2185, 30, false)), 17.69, 83.2).isEmpty()); }
}
