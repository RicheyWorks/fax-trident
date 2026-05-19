package com.xai.trident.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xai.trident.config.SecurityConfig;
import com.xai.trident.config.SecurityConfig.JwtTokenProvider;
import com.xai.trident.repository.ContactRepository;
import com.xai.trident.repository.FaxLogRepository;
import com.xai.trident.repository.FaxMetadataRepository;
import com.xai.trident.repository.UserRepository;
import com.xai.trident.service.ContactSuggestionService;
import com.xai.trident.service.FaxEngineService;
import com.xai.trident.upload.FaxUploadService;
import com.xai.trident.upload.UploadNotFoundException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.test.context.support.WithAnonymousUser;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Slice tests for {@link FaxController}. Covers the user-facing fax
 * surface ({@code /api/fax/**}, gated by {@code hasRole('USER')}).
 *
 * <ul>
 *   <li>Anonymous requests → 401 (URL-pattern reject; no need to even
 *       reach the controller).</li>
 *   <li>Authenticated POST /send with a valid body and a resolvable
 *       uploadId → 200 with {@code faxId} in the response.</li>
 *   <li>Send with a blank uploadId → 400 (bean validation on the DTO).</li>
 *   <li>Multipart POST /uploads → 200 with the {@code uploadId} the
 *       server minted.</li>
 *   <li>Unknown uploadId on /send → 404, surfaced through
 *       {@code UploadExceptionHandler} (audit 1.5).</li>
 *   <li>Paged endpoint with no results → 200 + empty content, not 404
 *       (audit 2.16 regression test).</li>
 * </ul>
 */
@WebMvcTest(FaxController.class)
@Import({SecurityConfig.class, com.xai.trident.upload.UploadExceptionHandler.class})
@TestPropertySource(properties = {
        "jwt.secret=test-secret-must-be-at-least-32-bytes-long-yes-it-is"
})
public class FaxControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    // Mocks for SecurityConfig's dependency graph (see AuthControllerTest
    // for the same pattern; comments not repeated here).
    //
    // Do NOT @MockBean RateLimitAspect — mocking the @Aspect registers it
    // into the AOP proxy chain, and the mock's @Around silently returns
    // null without calling proceed(), turning every @RateLimit-annotated
    // endpoint into a 200-with-empty-body black hole. The slice's default
    // bean policy excludes @Aspect components, so leaving it out makes
    // @RateLimit inert metadata — exactly what we want here.
    @MockBean private JwtTokenProvider jwtTokenProvider;
    @MockBean private UserRepository userRepository;
    @MockBean(name = "redisTemplate") @SuppressWarnings("rawtypes")
    private RedisTemplate redisTemplate;

    // FaxController's own dependencies.
    @MockBean private FaxEngineService faxEngineService;
    @MockBean private FaxLogRepository faxLogRepository;
    @MockBean private ContactRepository contactRepository;
    @MockBean private FaxMetadataRepository faxMetadataRepository;
    @MockBean private ContactSuggestionService contactSuggestionService;
    @MockBean private FaxUploadService faxUploadService;

    @Test
    @WithAnonymousUser
    void anonymous_get_fax_logs_returns_401() throws Exception {
        mockMvc.perform(get("/api/fax/logs/by-number/+12025550123"))
                .andExpect(status().isUnauthorized());
    }

    // All POST tests use .with(csrf()) — see AuthControllerTest header
    // comment for the rationale.

    @Test
    @WithMockUser(roles = "USER")
    void send_with_valid_body_and_resolvable_uploadId_returns_200_with_faxId() throws Exception {
        when(faxUploadService.resolveToString("upload-abc"))
                .thenReturn("/var/uploads/upload-abc.pdf");
        when(faxEngineService.sendFaxAsync(anyString(), anyString()))
                .thenReturn(CompletableFuture.completedFuture(null));

        String body = objectMapper.writeValueAsString(Map.of(
                "faxNumber", "+12025550123",
                "uploadId", "upload-abc"));

        mockMvc.perform(post("/api/fax/send")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                // FaxController mints a UUID-prefixed faxId on every call —
                // assert shape rather than value.
                .andExpect(jsonPath("$.faxId").exists())
                .andExpect(jsonPath("$.message").value("Fax send request queued successfully"));
    }

    @Test
    @WithMockUser(roles = "USER")
    void send_with_blank_uploadId_returns_400() throws Exception {
        // FaxRequestDTO declares @NotBlank on both fields. Empty uploadId
        // fails validation before reaching the controller body.
        String body = objectMapper.writeValueAsString(Map.of(
                "faxNumber", "+12025550123",
                "uploadId", ""));

        mockMvc.perform(post("/api/fax/send")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest());
    }

    @Test
    @WithMockUser(roles = "USER")
    void send_with_unknown_uploadId_returns_404() throws Exception {
        // UploadExceptionHandler maps UploadNotFoundException → 404. This is
        // the audit-1.5 chokepoint: traversal attempts ("../etc/passwd")
        // surface as the same 404 as legitimate "unknown id" so probes
        // can't differentiate.
        when(faxUploadService.resolveToString("does-not-exist"))
                .thenThrow(new UploadNotFoundException("upload not found"));

        String body = objectMapper.writeValueAsString(Map.of(
                "faxNumber", "+12025550123",
                "uploadId", "does-not-exist"));

        mockMvc.perform(post("/api/fax/send")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isNotFound());
    }

    @Test
    @WithMockUser(roles = "USER")
    void upload_returns_200_with_uploadId() throws Exception {
        when(faxUploadService.store(any())).thenReturn("upload-xyz");

        MockMultipartFile file = new MockMultipartFile(
                "file", "doc.pdf", "application/pdf",
                "%PDF-1.4 fake".getBytes());

        mockMvc.perform(multipart("/api/fax/uploads").file(file).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.uploadId").value("upload-xyz"));
    }

    @Test
    @WithMockUser(roles = "USER")
    void logs_by_number_returns_200_with_empty_page_not_404() throws Exception {
        // Audit 2.16 regression test: empty results are 200 with empty
        // content, never 404. 404 is reserved for "no resource at this URL"
        // (a single missing entity like getMetadata/{id}). A search that
        // legitimately finds nothing is a successful query returning zero
        // rows.
        when(faxLogRepository.findByFaxNumber(anyString(), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(), Pageable.ofSize(10), 0));

        mockMvc.perform(get("/api/fax/logs/by-number/+19999999999"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isArray())
                .andExpect(jsonPath("$.content.length()").value(0))
                .andExpect(jsonPath("$.totalElements").value(0));
    }
}
