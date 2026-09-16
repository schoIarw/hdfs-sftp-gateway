package io.github.scholiarw.hfg.manager.api;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(AuthController.class)
@Import(SecurityConfiguration.class)
@TestPropertySource(
    properties = {
      "hfg.security.admin.username=test-admin",
      "hfg.security.admin.password=test-password"
    })
class AuthControllerTest {
  @Autowired private MockMvc mockMvc;

  @Test
  void returnsTheAuthenticatedAdministrator() throws Exception {
    mockMvc
        .perform(get("/api/v1/auth/me").with(httpBasic("test-admin", "test-password")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.username").value("test-admin"))
        .andExpect(jsonPath("$.roles[0]").value("ADMIN"));
  }

  @Test
  void rejectsAnInvalidPassword() throws Exception {
    mockMvc
        .perform(get("/api/v1/auth/me").with(httpBasic("test-admin", "wrong-password")))
        .andExpect(status().isUnauthorized());
  }
}
