package ru.coffeediary;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/** Local rate limiting; deliberately does not trust client-supplied forwarding headers. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class LoginThrottle extends OncePerRequestFilter {
  private record Window(long start, int requests) {}

  private final ConcurrentHashMap<String, Window> windows = new ConcurrentHashMap<>();

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    if (request.getMethod().equals("POST")
        && request.getRequestURI().equals("/api/v1/auth/login")) {
      long now = System.currentTimeMillis();
      windows.entrySet().removeIf(entry -> now - entry.getValue().start() > 60_000);
      Window window =
          windows.compute(
              request.getRemoteAddr(),
              (key, previous) ->
                  previous == null
                      ? new Window(now, 1)
                      : new Window(previous.start(), previous.requests() + 1));
      if (window.requests() > 10) {
        response.setStatus(429);
        response.setHeader("Retry-After", "60");
        response.setContentType("application/json;charset=UTF-8");
        response
            .getWriter()
            .write(
                "{\"code\":\"RATE_LIMITED\",\"message\":\"Слишком много попыток. Повторите через"
                    + " минуту.\",\"fieldErrors\":{}}");
        return;
      }
    }
    chain.doFilter(request, response);
  }
}
