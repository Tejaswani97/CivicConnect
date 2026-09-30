package com.cleanstreet;
import java.util.*;

/** Valid status transitions and who may perform them. */
public final class Workflow {
  private static final Map<String, String> RULES = new HashMap<>();
  static {
    put("REPORTED>VERIFIED", "OFFICER"); put("REPORTED>REJECTED", "OFFICER"); put("VERIFIED>REJECTED", "OFFICER");
    for (String s : List.of("VERIFIED", "ASSIGNED", "ACCEPTED", "IN_PROGRESS", "REOPENED")) put(s + ">ASSIGNED", "OFFICER"); // assign / reassign
    put("ASSIGNED>ACCEPTED", "CREW"); put("ACCEPTED>IN_PROGRESS", "CREW"); put("IN_PROGRESS>COMPLETED", "CREW");
    put("COMPLETED>RESOLVED", "OFFICER"); put("RESOLVED>REOPENED", "CITIZEN"); put("COMPLETED>REOPENED", "OFFICER");
  }
  private static void put(String k, String role) { RULES.put(k, role); }
  /** ADMIN may perform any valid transition. */
  public static boolean allowed(String role, String from, String to) {
    String r = RULES.get(from + ">" + to); return r != null && (r.equals(role) || "ADMIN".equals(role));
  }
  public static boolean valid(String from, String to) { return RULES.containsKey(from + ">" + to); }
}
