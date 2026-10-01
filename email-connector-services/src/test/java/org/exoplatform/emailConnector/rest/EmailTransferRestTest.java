/**
 * Copyright (C) 2026 eXo Platform SAS
 *
 *  This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <gnu.org/licenses>.
 */package org.exoplatform.emailConnector.rest;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureWebMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import org.exoplatform.emailConnector.exception.DelegationRevokedException;
import org.exoplatform.emailConnector.exception.MailboxRightMissingException;
import org.exoplatform.emailConnector.model.ExportCheck;
import org.exoplatform.emailConnector.model.MailImportState;
import org.exoplatform.emailConnector.model.MailboxRights;
import org.exoplatform.emailConnector.model.SyncStatus;
import org.exoplatform.emailConnector.service.EmailBoxService;
import org.exoplatform.emailConnector.service.EmailExportService;
import org.exoplatform.emailConnector.service.EmailImportService;
import org.exoplatform.emailConnector.service.RawEmailSink;

import io.meeds.spring.web.security.PortalAuthenticationManager;
import io.meeds.spring.web.security.WebSecurityConfiguration;

import jakarta.servlet.Filter;

/**
 * The export and import endpoints (EXO-90845, EXO-90846). Who may read or write is the
 * services' to decide; what is pinned here is that the caller is the authenticated user,
 * that every answer of the services keeps its status, and that an export goes out as a
 * file under a safe name, never as content a browser would render.
 */
@SpringBootTest(classes = { EmailTransferRest.class, PortalAuthenticationManager.class })
@ContextConfiguration(classes = { WebSecurityConfiguration.class })
@TestPropertySource(properties = "spring.jackson.deserialization.fail-on-null-for-primitives=false")
@AutoConfigureWebMvc
@AutoConfigureMockMvc(addFilters = false)
@ExtendWith(MockitoExtension.class)
class EmailTransferRestTest {

  private static final String   SIMPLE_USER   = "simple";

  private static final String   TEST_PASSWORD = "testPassword";

  @MockitoBean
  private EmailExportService    emailExportService;

  @MockitoBean
  private EmailImportService    emailImportService;

  @Autowired
  private SecurityFilterChain   filterChain;

  @Autowired
  private WebApplicationContext context;

  private MockMvc               mockMvc;

  /** Builds the MockMvc with the platform's security filters. */
  @BeforeEach
  void setup() {
    mockMvc = MockMvcBuilders.webAppContextSetup(context).addFilters(filterChain.getFilters().toArray(new Filter[0])).build();
  }

  /**
   * The zip check answers the count and the cap for the caller's selection, and keeps
   * every status: not found, malformed, refused, ended share.
   *
   * @throws Exception when the request cannot be performed
   */
  @Test
  void theZipCheckKeepsEveryStatus() throws Exception {
    when(emailExportService.checkZip(SIMPLE_USER, List.of("INBOX:1", "CUSTOM:3:2"))).thenReturn(new ExportCheck(2, 200));
    mockMvc.perform(get("/email-box/export/zip/check").param("mails", "INBOX:1", "CUSTOM:3:2").with(testSimpleUser()))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.count").value(2))
           .andExpect(jsonPath("$.max").value(200));
    mockMvc.perform(get("/email-box/export/zip/check").param("mails", "INBOX:9").with(testSimpleUser())).andExpect(status().isNotFound());
    when(emailExportService.checkZip(SIMPLE_USER, List.of("bad"))).thenThrow(new IllegalArgumentException(EmailExportService.EXPORT_INVALID_SELECTION));
    mockMvc.perform(get("/email-box/export/zip/check").param("mails", "bad").with(testSimpleUser())).andExpect(status().isBadRequest());
    when(emailExportService.checkZip(SIMPLE_USER, List.of("CUSTOM:1:1"))).thenThrow(new MailboxRightMissingException(MailboxRights.READ));
    mockMvc.perform(get("/email-box/export/zip/check").param("mails", "CUSTOM:1:1").with(testSimpleUser())).andExpect(status().isForbidden());
    when(emailExportService.checkZip(SIMPLE_USER, List.of("CUSTOM:2:1"))).thenThrow(new DelegationRevokedException(DelegationRevokedException.REVOKED));
    mockMvc.perform(get("/email-box/export/zip/check").param("mails", "CUSTOM:2:1").with(testSimpleUser())).andExpect(status().isGone());
  }

  /**
   * The zip goes out as an attachment named emails.zip, unsniffed and uncached, with the
   * bytes the service wrote; a refusal or an absence sends no file.
   *
   * @throws Exception when the request cannot be performed
   */
  @Test
  void theZipIsADownload() throws Exception {
    when(emailExportService.writeZip(eq(SIMPLE_USER), eq(List.of("INBOX:1")), any(RawEmailSink.class))).thenAnswer(invocation -> {
      OutputStream out = ((RawEmailSink) invocation.getArgument(2)).open(null, -1);
      out.write("PK".getBytes(StandardCharsets.US_ASCII));
      return true;
    });
    String disposition = mockMvc.perform(get("/email-box/export/zip").param("mails", "INBOX:1").with(testSimpleUser()))
                                .andExpect(status().isOk())
                                .andExpect(header().string(HttpHeaders.CONTENT_TYPE, "application/zip"))
                                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, org.hamcrest.Matchers.containsString("no-store")))
                                .andExpect(content().bytes("PK".getBytes(StandardCharsets.US_ASCII)))
                                .andReturn()
                                .getResponse()
                                .getHeader(HttpHeaders.CONTENT_DISPOSITION);
    assertTrue(disposition.startsWith("attachment;") && disposition.contains("emails.zip"), disposition);

    mockMvc.perform(get("/email-box/export/zip").param("mails", "INBOX:2").with(testSimpleUser()))
           .andExpect(status().isNotFound())
           .andExpect(header().doesNotExist(HttpHeaders.CONTENT_DISPOSITION));
    when(emailExportService.writeZip(eq(SIMPLE_USER), eq(List.of("INBOX:3")), any())).thenThrow(new IllegalArgumentException(EmailBoxService.EXPORT_TOO_MANY));
    mockMvc.perform(get("/email-box/export/zip").param("mails", "INBOX:3").with(testSimpleUser())).andExpect(status().isBadRequest());
    when(emailExportService.writeZip(eq(SIMPLE_USER), eq(List.of("INBOX:4")), any())).thenThrow(new IllegalAccessException("refused"));
    mockMvc.perform(get("/email-box/export/zip").param("mails", "INBOX:4").with(testSimpleUser())).andExpect(status().isForbidden());
    when(emailExportService.writeZip(eq(SIMPLE_USER), eq(List.of("INBOX:5")), any())).thenThrow(new IllegalStateException("down"));
    mockMvc.perform(get("/email-box/export/zip").param("mails", "INBOX:5").with(testSimpleUser())).andExpect(status().isInternalServerError());
  }

  /**
   * The mbox goes out as application/mbox under the folder's name made safe; its check
   * and its download keep every status.
   *
   * @throws Exception when the request cannot be performed
   */
  @Test
  void theMboxIsADownloadNamedAfterTheFolder() throws Exception {
    when(emailExportService.writeMbox(eq(SIMPLE_USER), eq("CUSTOM:3"), any(RawEmailSink.class))).thenAnswer(invocation -> {
      OutputStream out = ((RawEmailSink) invocation.getArgument(2)).open("Invoices\r\nX-Injected: 1/2", -1);
      out.write("From x\n".getBytes(StandardCharsets.US_ASCII));
      return true;
    });
    String disposition = mockMvc.perform(get("/email-box/export/mbox").param("folder", "CUSTOM:3").with(testSimpleUser()))
                                .andExpect(status().isOk())
                                .andExpect(header().string(HttpHeaders.CONTENT_TYPE, "application/mbox"))
                                .andExpect(header().doesNotExist("X-Injected"))
                                .andReturn()
                                .getResponse()
                                .getHeader(HttpHeaders.CONTENT_DISPOSITION);
    assertTrue(disposition.contains("Invoices__X-Injected_ 1_2.mbox"), disposition);

    mockMvc.perform(get("/email-box/export/mbox").with(testSimpleUser())).andExpect(status().isNotFound());
    when(emailExportService.writeMbox(eq(SIMPLE_USER), eq("SENT"), any())).thenThrow(new IllegalArgumentException(EmailBoxService.EXPORT_TOO_MANY));
    mockMvc.perform(get("/email-box/export/mbox").param("folder", "SENT").with(testSimpleUser())).andExpect(status().isBadRequest());
    when(emailExportService.writeMbox(eq(SIMPLE_USER), eq("CUSTOM:1"), any())).thenThrow(new MailboxRightMissingException(MailboxRights.READ));
    mockMvc.perform(get("/email-box/export/mbox").param("folder", "CUSTOM:1").with(testSimpleUser())).andExpect(status().isForbidden());

    when(emailExportService.checkMbox(SIMPLE_USER, "INBOX")).thenReturn(new ExportCheck(12, 20000));
    mockMvc.perform(get("/email-box/export/mbox/check").with(testSimpleUser()))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.count").value(12));
    mockMvc.perform(get("/email-box/export/mbox/check").param("folder", "CUSTOM:404").with(testSimpleUser()))
           .andExpect(status().isNotFound());
    when(emailExportService.checkMbox(SIMPLE_USER, "CUSTOM:2")).thenThrow(new DelegationRevokedException(DelegationRevokedException.REVOKED));
    mockMvc.perform(get("/email-box/export/mbox/check").param("folder", "CUSTOM:2").with(testSimpleUser())).andExpect(status().isGone());
  }

  /**
   * The import starts for the caller with the folder and the uploads given, and keeps
   * every refusal's status: a request that cannot run, a run already going, a missing
   * right, an ended share.
   *
   * @throws Exception when the request cannot be performed
   */
  @Test
  void theImportKeepsEveryStatus() throws Exception {
    MailImportState started = new MailImportState();
    started.setStatus(SyncStatus.IN_PROGRESS);
    when(emailImportService.startImport(SIMPLE_USER, "INBOX", List.of("u1", "u2"))).thenReturn(started);
    mockMvc.perform(post("/email-box/import").contentType(MediaType.APPLICATION_JSON)
                                             .content("{\"folder\":\"INBOX\",\"uploadIds\":[\"u1\",\"u2\"]}")
                                             .with(testSimpleUser()))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.status").value("IN_PROGRESS"));

    when(emailImportService.startImport(eq(SIMPLE_USER), eq("TRASH"), anyList())).thenThrow(new IllegalArgumentException(EmailBoxService.IMPORT_FOLDER_REFUSED));
    perform("TRASH").andExpect(status().isBadRequest());
    when(emailImportService.startImport(eq(SIMPLE_USER), eq("SENT"), anyList())).thenThrow(new IllegalStateException(EmailImportService.IMPORT_ALREADY_RUNNING));
    perform("SENT").andExpect(status().isConflict());
    when(emailImportService.startImport(eq(SIMPLE_USER), eq("CUSTOM:1"), anyList())).thenThrow(new MailboxRightMissingException(MailboxRights.INSERT));
    perform("CUSTOM:1").andExpect(status().isForbidden());
    when(emailImportService.startImport(eq(SIMPLE_USER), eq("CUSTOM:2"), anyList())).thenThrow(new DelegationRevokedException(DelegationRevokedException.REVOKED));
    perform("CUSTOM:2").andExpect(status().isGone());
  }

  /**
   * The import's state is the caller's, never cached.
   *
   * @throws Exception when the request cannot be performed
   */
  @Test
  void theImportStatusIsTheCallers() throws Exception {
    MailImportState state = new MailImportState();
    state.setAdded(4);
    when(emailImportService.getImportState(SIMPLE_USER)).thenReturn(state);
    mockMvc.perform(get("/email-box/import/status").with(testSimpleUser()))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.added").value(4))
           .andExpect(header().string(HttpHeaders.CACHE_CONTROL, org.hamcrest.Matchers.containsString("no-store")));
  }

  /**
   * Posts an import into a folder.
   *
   * @param folder the folder key
   * @return the result actions
   * @throws Exception when the request cannot be performed
   */
  private org.springframework.test.web.servlet.ResultActions perform(String folder) throws Exception {
    return mockMvc.perform(post("/email-box/import").contentType(MediaType.APPLICATION_JSON)
                                                    .content("{\"folder\":\"" + folder + "\",\"uploadIds\":[\"u\"]}")
                                                    .with(testSimpleUser()));
  }

  /**
   * The simple user every request is made as.
   *
   * @return the request post-processor
   */
  private RequestPostProcessor testSimpleUser() {
    return user(SIMPLE_USER).password(TEST_PASSWORD).authorities(new SimpleGrantedAuthority("users"));
  }
}
