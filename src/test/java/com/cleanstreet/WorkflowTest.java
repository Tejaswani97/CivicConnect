package com.cleanstreet;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class WorkflowTest {
  @Test void happyPathIsAllowedForTheRightRoles() {
    assertTrue(Workflow.allowed("OFFICER", "REPORTED", "VERIFIED")); assertTrue(Workflow.allowed("OFFICER", "VERIFIED", "ASSIGNED"));
    assertTrue(Workflow.allowed("CREW", "ASSIGNED", "ACCEPTED")); assertTrue(Workflow.allowed("CREW", "ACCEPTED", "IN_PROGRESS"));
    assertTrue(Workflow.allowed("CREW", "IN_PROGRESS", "COMPLETED")); assertTrue(Workflow.allowed("OFFICER", "COMPLETED", "RESOLVED"));
    assertTrue(Workflow.allowed("CITIZEN", "RESOLVED", "REOPENED"));
  }
  @Test void skippingStepsIsRejected() { assertFalse(Workflow.allowed("CREW", "ASSIGNED", "COMPLETED")); assertFalse(Workflow.allowed("OFFICER", "REPORTED", "RESOLVED")); assertFalse(Workflow.valid("REPORTED", "ASSIGNED")); }
  @Test void wrongRoleIsRejected() { assertFalse(Workflow.allowed("CITIZEN", "REPORTED", "VERIFIED")); assertFalse(Workflow.allowed("CREW", "COMPLETED", "RESOLVED")); assertFalse(Workflow.allowed("OFFICER", "RESOLVED", "REOPENED")); }
  @Test void phoneValidationAndMasking() {
    assertEquals("+919876543210", ApiController.phone("98765 43210")); assertEquals("+14155552671", ApiController.phone("+14155552671"));
    assertThrows(Exception.class, () -> ApiController.phone("12345")); assertEquals("+91 XXXXXX3210", ApiController.mask("+919876543210"));
  }
}
