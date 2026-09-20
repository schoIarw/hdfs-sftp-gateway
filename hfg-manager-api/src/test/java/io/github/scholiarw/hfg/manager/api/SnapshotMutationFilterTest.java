package io.github.scholiarw.hfg.manager.api;

import static org.mockito.Mockito.*;

import java.security.Principal;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class SnapshotMutationFilterTest {
  @Test
  void queuesSuccessfulConfigurationMutations() throws Exception {
    SnapshotPublicationService publications = mock(SnapshotPublicationService.class);
    SnapshotMutationFilter filter = new SnapshotMutationFilter(publications);
    MockHttpServletRequest request = new MockHttpServletRequest("PUT", "/api/v1/users/user-1/status");
    request.setUserPrincipal((Principal) () -> "admin");
    MockHttpServletResponse response = new MockHttpServletResponse();

    filter.doFilter(request, response, (ignoredRequest, actualResponse) ->
        ((MockHttpServletResponse) actualResponse).setStatus(204));

    verify(publications)
        .requestAll("admin", "configuration change: PUT /api/v1/users/user-1/status");
  }

  @Test
  void ignoresFailedMutationsAndManualPublication() throws Exception {
    SnapshotPublicationService publications = mock(SnapshotPublicationService.class);
    SnapshotMutationFilter filter = new SnapshotMutationFilter(publications);
    MockHttpServletRequest failed = new MockHttpServletRequest("DELETE", "/api/v1/directories/id");
    MockHttpServletResponse failedResponse = new MockHttpServletResponse();
    filter.doFilter(failed, failedResponse, (request, response) ->
        ((MockHttpServletResponse) response).setStatus(409));

    MockHttpServletRequest manual =
        new MockHttpServletRequest("POST", "/api/v1/control/snapshots/group/publish");
    filter.doFilter(manual, new MockHttpServletResponse(), (request, response) -> {});

    verifyNoInteractions(publications);
  }
}
