package com.cleanstreet;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.*;
interface UserRepo extends JpaRepository<User, Long> { Optional<User> findByPhone(String p); Optional<User> findByToken(String t); List<User> findByRoleAndOfficeId(String r, Long o); }
interface OfficeRepo extends JpaRepository<Office, Long> {}
interface ComplaintRepo extends JpaRepository<Complaint, Long> { Optional<Complaint> findByNumber(String n); List<Complaint> findAllByOrderByCreatedAtDesc(); List<Complaint> findByReporterIdOrderByCreatedAtDesc(Long id); List<Complaint> findByOfficeIdOrderByCreatedAtDesc(Long id); List<Complaint> findByStaffIdOrderByCreatedAtDesc(Long id); }
interface UpdateRepo extends JpaRepository<StatusUpdate, Long> { List<StatusUpdate> findByComplaintIdOrderByCreatedAtAsc(Long id); }

interface AuditRepo extends JpaRepository<AuditEvent, Long> { List<AuditEvent> findTop100ByOrderByCreatedAtDesc(); }
