package ru.coffeediary;

import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.userdetails.*;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.csrf.HttpSessionCsrfTokenRepository;

@Configuration
public class Security {
  @Bean
  PasswordEncoder encoder() {
    return new BCryptPasswordEncoder(12);
  }

  @Bean
  UserDetailsService users(JdbcTemplate db) {
    return login -> {
      var rows = db.queryForList("select * from app_user where login=?", login);
      if (rows.isEmpty()) throw new UsernameNotFoundException("Unknown user");
      var u = rows.getFirst();
      return User.withUsername(login)
          .password(u.get("password_hash").toString())
          .roles("OWNER")
          .build();
    };
  }

  @Bean
  CommandLineRunner bootstrap(
      JdbcTemplate db,
      PasswordEncoder encoder,
      @Value("${app.owner-login}") String login,
      @Value("${app.owner-password}") String password) {
    return args -> {
      if (db.queryForObject("select count(*) from app_user", Integer.class) == 0) {
        if (password.length() < 12)
          throw new IllegalStateException("OWNER_PASSWORD must contain at least 12 characters");
        UUID id = UUID.randomUUID();
        db.update(
            "insert into app_user(id,login,password_hash) values(?,?,?)",
            id,
            login,
            encoder.encode(password));
        UUID profileId = UUID.randomUUID();
        db.update(
            "insert into brew_profile(id,owner_id,name,default_cup_volume_ml,recipe,grinder,brewer)"
                + " values(?,?,?,250,?::jsonb,?,?)",
            profileId,
            id,
            "Race M1 + Kitfort КТ-7570",
            "{\"method\":\"AUTO_POUR_OVER\",\"milkSugar\":\"NONE\"}",
            "MHW-3BOMBER Race M1", "Kitfort КТ-7570");
        db.update("update app_user set default_brew_profile_id=? where id=?", profileId, id);
      }
    };
  }

  @Bean
  SecurityFilterChain chain(HttpSecurity http) throws Exception {
    return http.authorizeHttpRequests(
            a ->
                a.requestMatchers(
                        "/api/v1/auth/csrf",
                        "/api/v1/auth/login",
                        "/",
                        "/index.html",
                        "/assets/**",
                        "/favicon.ico")
                    .permitAll()
                    .anyRequest()
                    .authenticated())
        .csrf(c -> c.csrfTokenRepository(new HttpSessionCsrfTokenRepository()))
        .formLogin(
            f ->
                f.loginProcessingUrl("/api/v1/auth/login")
                    .successHandler(
                        (q, r, a) -> {
                          r.setContentType("application/json");
                          r.getWriter().write("{\"ok\":true}");
                        })
                    .failureHandler((q, r, e) -> r.sendError(401)))
        .logout(
            l ->
                l.logoutUrl("/api/v1/auth/logout")
                    .logoutSuccessHandler((q, r, a) -> r.setStatus(204)))
        .exceptionHandling(e -> e.authenticationEntryPoint((q, r, x) -> r.sendError(401)))
        .build();
  }
}
