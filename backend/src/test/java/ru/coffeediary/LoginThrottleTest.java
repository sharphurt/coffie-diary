package ru.coffeediary;

import static org.junit.jupiter.api.Assertions.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class LoginThrottleTest {
  @Test
  void limitsEleventhAttemptAndDoesNotTrustForwardedAddress() throws Exception {
    var filter = new LoginThrottle();
    var passed = new AtomicInteger();
    for (int i = 0; i < 11; i++) {
      var request = new MockHttpServletRequest("POST", "/api/v1/auth/login");
      request.setRemoteAddr("192.0.2.1");
      request.addHeader("X-Forwarded-For", "198.51.100." + i);
      var response = new MockHttpServletResponse();
      filter.doFilter(request, response, (req, res) -> passed.incrementAndGet());
      if (i == 10) {
        assertEquals(429, response.getStatus());
        assertEquals("60", response.getHeader("Retry-After"));
        assertTrue(response.getContentAsString().contains("RATE_LIMITED"));
      }
    }
    assertEquals(10, passed.get());
    var other = new MockHttpServletRequest("POST", "/api/v1/auth/login");
    other.setRemoteAddr("192.0.2.2");
    filter.doFilter(other, new MockHttpServletResponse(), (req, res) -> passed.incrementAndGet());
    assertEquals(11, passed.get());
  }

  @Test
  void ordinaryRequestsDoNotConsumeLoginQuota() throws Exception {
    var filter = new LoginThrottle();
    var passed = new AtomicInteger();
    for (int i = 0; i < 20; i++) {
      filter.doFilter(new MockHttpServletRequest("GET", "/api/v1/auth/me"),
          new MockHttpServletResponse(), (req, res) -> passed.incrementAndGet());
    }
    filter.doFilter(new MockHttpServletRequest("POST", "/api/v1/auth/login"),
        new MockHttpServletResponse(), (req, res) -> passed.incrementAndGet());
    assertEquals(21, passed.get());
  }
}
