package com.xai.trident.controller;

import com.xai.trident.config.SecurityConfig;
import com.xai.trident.config.SecurityConfig.JwtTokenProvider;
import com.xai.trident.repository.ContactRepository;
import com.xai.trident.repository.FaxLogRepository;
import com.xai.trident.repository.FaxMetadataRepository;
import com.xai.trident.repository.UserRepository;
import com.xai.trident.service.FaxEngineService;
import com.xai.trident.service.PdfProcessingService;
import com.xai.trident.upload.FaxUploadService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.security.test.context.support.WithAnonymousUser;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Role-gating tests for {@link AdminController}. The class is annotated
 * {@code @PreAuthorize("hasRole('ADMIN')")} <em>and</em> matched by the
 * SecurityConfig URL pattern {@code /api/admin/** → hasRole("ADMIN")}.
 * These tests verify both layers actually enforce the rule:
 *
 * <ul>
 *   <li>Anonymous → 401 (URL-pattern reject before the controller is hit).</li>
 *   <li>Authenticated with ROLE_USER → 403 (filter chain returns 403 for
 *       authenticated-but-unauthorized).</li>
 *   <li>Authenticated with ROLE_ADMIN → 200, mocked service returns data.</li>
 * </ul>
 *
 * <p>The happy-path assertion uses {@code GET /api/admin/contacts} because
 * it's a single-line delegate to {@link ContactRepository#findAll()} — easy
 * to mock, no Redis SCAN or aggregation surface to stub. Other admin
 * endpoints share the same gating, so verifying one is sufficient evidence
 * the role wiring works (the per-endpoint response shapes are out of scope
 * for the role test and belong with each endpoint's targeted coverage).
 */
@WebMvcTest(AdminController.class)
@Import(SecurityConfig.class)
@TestPropertySource(properties = {
        "jwt.secret=test-secret-must-be-at-least-32-bytes-long-yes-it-is"
})
public class AdminControllerTest {

    @Autowired
    private MockMvc mockMvc;

    // Mocks for SecurityConfig's dependency graph (see AuthControllerTest
    // for the same pattern; comments not repeated here).
    // RateLimitAspect intentionally NOT @MockBean'd here — see
    // AuthControllerTest for the rationale.
    @MockBean private JwtTokenProvider jwtTokenProvider;
    @MockBean private UserRepository userRepository;
    @MockBean(name = "redisTemplate") @SuppressWarnings("rawtypes")
    private RedisTemplate redisTemplate;

    // AdminController's own dependencies.
    @MockBean private FaxEngineService faxEngineService;
    @MockBean private FaxLogRepository faxLogRepository;
    @MockBean private ContactRepository contactRepository;
    @MockBean private FaxMetadataRepository faxMetadataRepository;
    @MockBean private PdfProcessingService pdfProcessingService;
    @MockBean private FaxUploadService faxUploadService;

    @Test
    @WithAnonymousUser
    void anonymous_gets_401_on_admin_endpoint() throws Exception {
        mockMvc.perform(get("/api/admin/contacts"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @WithMockUser(roles = "USER")
    void userRole_gets_403_on_admin_endpoint() throws Exception {
        // ROLE_USER passes authentication but fails authorization for
        // /api/admin/**. Both the URL-pattern matcher in SecurityConfig and
        // the class-level @PreAuthorize on AdminController enforce this —
        // either alone would 403; together they're belt and braces.
        mockMvc.perform(get("/api/admin/contacts"))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void adminRole_gets_200_on_contacts() throws Exception {
        when(contactRepository.findAll()).thenReturn(List.of());

        mockMvc.perform(get("/api/admin/contacts"))
                .andExpect(status().isOk())
                // Empty list, not 404. Audit 2.16: empty pages return 200,
                // 404 is reserved for "no resource at this URL."
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$.length()").value(0));
    }
}
