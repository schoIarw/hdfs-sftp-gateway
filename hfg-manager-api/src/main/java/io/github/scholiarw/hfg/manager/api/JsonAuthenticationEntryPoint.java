package io.github.scholiarw.hfg.manager.api;

import jakarta.servlet.http.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;

/**
 * 未认证时返回 401 JSON，并且<b>不发送 WWW-Authenticate 挑战头</b>。
 *
 * <p>Spring Security 默认的 Basic 入口点会带上 {@code WWW-Authenticate: Basic}，浏览器收到后就会弹出 系统原生的账号密码对话框（SPA
 * 自己已经有登录页，这个弹框既多余又会掩盖真正的错误）。前端拿到 401 后 自行清理凭据并跳回登录页。
 */
final class JsonAuthenticationEntryPoint implements AuthenticationEntryPoint {
  @Override
  public void commence(
      HttpServletRequest request,
      HttpServletResponse response,
      AuthenticationException authException)
      throws IOException {
    response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
    response.setContentType("application/json");
    response.setCharacterEncoding(StandardCharsets.UTF_8.name());
    response
        .getWriter()
        .write(
            "{\"status\":401,\"error\":\"Unauthorized\",\"message\":\"登录已失效，请重新登录\",\"path\":\""
                + request.getRequestURI()
                + "\"}");
  }
}
