package com.cleanstreet;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.*;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import java.util.List;

@Configuration
public class Config implements WebMvcConfigurer {
  @Value("${app.upload-dir}") String dir;
  @Bean BCryptPasswordEncoder encoder() { return new BCryptPasswordEncoder(); }

  /** Imports offices.csv on first run and creates demo accounts (password demo123) when app.seed-demo-users=true. */
  @Bean CommandLineRunner seed(OfficeRepo offices, OfficeService svc, UserRepo users, BCryptPasswordEncoder enc, @Value("${app.seed-demo-users:true}") boolean demo) {
    return args -> {
      if (offices.count() == 0) svc.importCsv(getClass().getResourceAsStream("/offices.csv"));
      if (!demo || users.count() > 0) return;
      String hash = enc.encode("demo123"); int n = 0;
      for (Office o : offices.findAll()) { n++;
        add(users, hash, o.city + " Officer", n * 10, "OFFICER", o.id);
        add(users, hash, o.city + " Crew A", n * 10 + 1, "CREW", o.id);
        add(users, hash, o.city + " Crew B", n * 10 + 2, "CREW", o.id);
      }
      add(users, hash, "Administrator", 99999, "ADMIN", null);
    };
  }
  private void add(UserRepo r, String hash, String name, int k, String role, Long office) {
    User u = new User(); u.name = name; u.phone = "+9190000" + String.format("%05d", k); u.role = role; u.officeId = office; u.verified = true; u.passwordHash = hash; r.save(u);
  }
}
