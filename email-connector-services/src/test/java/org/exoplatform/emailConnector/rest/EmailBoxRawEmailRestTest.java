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
 */
package org.exoplatform.emailConnector.rest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureWebMvc;
import org.springframework.http.HttpHeaders;
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
import org.exoplatform.emailConnector.model.MailboxRights;
import org.exoplatform.emailConnector.model.RawEmailSource;
import org.exoplatform.emailConnector.service.EmailBoxService;
import org.exoplatform.emailConnector.service.EmailScheduledSendService;
import org.exoplatform.emailConnector.service.EmailSecurityService;
import org.exoplatform.emailConnector.service.RawEmailSink;
import org.exoplatform.emailConnector.service.ReadReceiptService;

import io.meeds.spring.web.security.PortalAuthenticationManager;
import io.meeds.spring.web.security.WebSecurityConfiguration;

import jakarta.servlet.Filter;

/**
 * The two raw-source endpoints of a message (EXO-90842): "Show original" and the
 * {@code .eml} download. Who may read is the service's to decide; what is pinned here is
 * that every answer of the service keeps its status, that the caller is the
 * authenticated user, and that the download goes out as a file, never as content a
 * browser would render.
 */
@SpringBootTest(classes = { EmailBoxRest.class, PortalAuthenticationManager.class })
@ContextConfiguration(classes = { WebSecurityConfiguration.class })
@TestPropertySource(properties = "spring.jackson.deserialization.fail-on-null-for-primitives=false")
@AutoConfigureWebMvc
@AutoConfigureMockMvc(addFilters = false)
@ExtendWith(MockitoExtension.class)
class EmailBoxRawEmailRestTest {

  private static final String       PATH          = "/email-box/4242";

  private static final String       SIMPLE_USER   = "simple";

  private static final String       TEST_PASSWORD = "testPassword";

  private static final String       RAW           = "Subject: Hi\r\n\r\n<b>body</b>\r\n";

  @MockitoBean
  private EmailBoxService           emailBoxService;

  @MockitoBean
  private EmailScheduledSendService emailScheduledSendService;

  @MockitoBean
  private ReadReceiptService        readReceiptService;

  @MockitoBean
  private EmailSecurityService      emailSecurityService;

  @Autowired
  private SecurityFilterChain       filterChain;

  @Autowired
  private WebApplicationContext     context;

  private MockMvc                   mockMvc;

  /** Builds the MockMvc with the platform's security filters. */
  @BeforeEach
  void setup() {
    mockMvc = MockMvcBuilders.webAppContextSetup(context).addFilters(filterChain.getFilters().toArray(new Filter[0])).build();
  }

  /**
   * The source is answered as JSON for the authenticated caller and the folder asked
   * for, never cached by the browser.
   *
   * @throws Exception when the request cannot be performed
   */
  @Test
  void theSourceIsAnsweredForTheCaller() throws Exception {
    RawEmailSource source = new RawEmailSource();
    source.setHeaders("Subject: Hi");
    source.setSource(RAW);
    source.setSize(RAW.length());
    when(emailBoxService.getRawEmailSource(4242L, SIMPLE_USER, "CUSTOM:3")).thenReturn(source);

    mockMvc.perform(get(PATH + "/source").param("folder", "CUSTOM:3").with(testSimpleUser()))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.headers").value("Subject: Hi"))
           .andExpect(jsonPath("$.source").value(RAW))
           .andExpect(jsonPath("$.truncated").value(false))
           .andExpect(header().string(HttpHeaders.CACHE_CONTROL, org.hamcrest.Matchers.containsString("no-store")));
  }

  /**
   * Every answer of the service keeps its status on the source: none is 404, a refusal
   * or a missing right is 403, an ended share is 410, a mail server fault is 500. The
   * folder defaults to INBOX.
   *
   * @throws Exception when the request cannot be performed
   */
  @Test
  void theSourceKeepsEveryStatus() throws Exception {
    mockMvc.perform(get(PATH + "/source").with(testSimpleUser())).andExpect(status().isNotFound());

    when(emailBoxService.getRawEmailSource(4242L, SIMPLE_USER, "INBOX")).thenThrow(new IllegalAccessException("refused"));
    mockMvc.perform(get(PATH + "/source").with(testSimpleUser())).andExpect(status().isForbidden());

    when(emailBoxService.getRawEmailSource(4242L, SIMPLE_USER, "CUSTOM:1")).thenThrow(new MailboxRightMissingException(MailboxRights.READ));
    mockMvc.perform(get(PATH + "/source").param("folder", "CUSTOM:1").with(testSimpleUser())).andExpect(status().isForbidden());

    when(emailBoxService.getRawEmailSource(4242L, SIMPLE_USER, "CUSTOM:2")).thenThrow(new DelegationRevokedException(DelegationRevokedException.REVOKED));
    mockMvc.perform(get(PATH + "/source").param("folder", "CUSTOM:2").with(testSimpleUser())).andExpect(status().isGone());

    when(emailBoxService.getRawEmailSource(4242L, SIMPLE_USER, "SENT")).thenThrow(new IllegalStateException("down"));
    mockMvc.perform(get(PATH + "/source").param("folder", "SENT").with(testSimpleUser())).andExpect(status().isInternalServerError());
  }

  /**
   * The download goes out as {@code message/rfc822}, as an attachment named after the
   * subject made safe -- a subject carrying a line break and quotes cannot add a header
   * or break out of the file name -- with the bytes the service wrote, unsniffed and
   * uncached.
   *
   * @throws Exception when the request cannot be performed
   */
  @Test
  void theDownloadIsAFileNamedAfterTheSafeSubject() throws Exception {
    when(emailBoxService.writeRawEmail(eq(4242L), eq(SIMPLE_USER), eq("INBOX"), any(RawEmailSink.class))).thenAnswer(invocation -> {
      RawEmailSink sink = invocation.getArgument(3);
      OutputStream out = sink.open("Re: \"Q3\"\r\nX-Injected: yes", RAW.length());
      out.write(RAW.getBytes(StandardCharsets.UTF_8));
      return true;
    });

    String disposition = mockMvc.perform(get(PATH + "/eml").with(testSimpleUser()))
                                .andExpect(status().isOk())
                                .andExpect(header().string(HttpHeaders.CONTENT_TYPE, "message/rfc822"))
                                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                                .andExpect(header().doesNotExist("X-Injected"))
                                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, org.hamcrest.Matchers.containsString("no-store")))
                                .andExpect(content().bytes(RAW.getBytes(StandardCharsets.UTF_8)))
                                .andReturn()
                                .getResponse()
                                .getHeader(HttpHeaders.CONTENT_DISPOSITION);
    assertTrue(disposition.startsWith("attachment;"), disposition);
    assertTrue(disposition.contains("Re_ _Q3___X-Injected_ yes.eml"), disposition);
    assertFalse(disposition.contains("\r") || disposition.contains("\n"), disposition);
  }

  /**
   * Every answer of the service keeps its status on the download too, and nothing is
   * sent as a file when the message is not found.
   *
   * @throws Exception when the request cannot be performed
   */
  @Test
  void theDownloadKeepsEveryStatus() throws Exception {
    mockMvc.perform(get(PATH + "/eml").with(testSimpleUser()))
           .andExpect(status().isNotFound())
           .andExpect(header().doesNotExist(HttpHeaders.CONTENT_DISPOSITION));

    when(emailBoxService.writeRawEmail(anyLong(), anyString(), eq("INBOX"), any())).thenThrow(new IllegalAccessException("refused"));
    mockMvc.perform(get(PATH + "/eml").with(testSimpleUser())).andExpect(status().isForbidden());

    when(emailBoxService.writeRawEmail(anyLong(), anyString(), eq("CUSTOM:1"), any())).thenThrow(new MailboxRightMissingException(MailboxRights.READ));
    mockMvc.perform(get(PATH + "/eml").param("folder", "CUSTOM:1").with(testSimpleUser())).andExpect(status().isForbidden());

    when(emailBoxService.writeRawEmail(anyLong(), anyString(), eq("CUSTOM:2"), any())).thenThrow(new DelegationRevokedException(DelegationRevokedException.REVOKED));
    mockMvc.perform(get(PATH + "/eml").param("folder", "CUSTOM:2").with(testSimpleUser())).andExpect(status().isGone());

    when(emailBoxService.writeRawEmail(anyLong(), anyString(), eq("SENT"), any())).thenThrow(new IllegalStateException("down"));
    mockMvc.perform(get(PATH + "/eml").param("folder", "SENT").with(testSimpleUser())).andExpect(status().isInternalServerError());
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
