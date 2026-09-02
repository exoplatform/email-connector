
/**
 * Copyright (C) 2025 eXo Platform SAS
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

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.InputStream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureWebMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import com.fasterxml.jackson.core.json.JsonReadFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import org.exoplatform.emailConnector.model.EmailConnector;
import org.exoplatform.emailConnector.model.EmailSyncExecutorStatus;
import org.exoplatform.emailConnector.service.EmailConnectorService;
import org.exoplatform.emailConnector.service.EmailSyncService;

import io.meeds.spring.web.security.PortalAuthenticationManager;
import io.meeds.spring.web.security.WebSecurityConfiguration;
import jakarta.servlet.Filter;
import lombok.SneakyThrows;

@SpringBootTest(classes = { EmailConnectorRest.class, PortalAuthenticationManager.class })
@ContextConfiguration(classes = { WebSecurityConfiguration.class })
@AutoConfigureWebMvc
@AutoConfigureMockMvc(addFilters = false)
@ExtendWith(MockitoExtension.class)
public class EmailConnectorRestTest {

  private static final String EMAIL_CONNECTOR_PATH = "/connectors"; // NOSONAR

  private static final String SIMPLE_USER          = "simple";

  private static final String ADMIN_USER           = "admin";

  private static final String TEST_PASSWORD        = "testPassword";

  static final ObjectMapper   OBJECT_MAPPER;

  static {
    // Workaround when Jackson is defined in shared library with different
    // version and without artifact jackson-datatype-jsr310
    OBJECT_MAPPER = JsonMapper.builder()
                              .configure(JsonReadFeature.ALLOW_MISSING_VALUES, true)
                              .configure(SerializationFeature.FAIL_ON_EMPTY_BEANS, false)
                              .build();
    OBJECT_MAPPER.registerModule(new JavaTimeModule());
  }

  @MockBean
  private EmailConnectorService emailConnectorService;

  @MockBean
  private EmailSyncService      emailSyncService;

  @Autowired
  private SecurityFilterChain   filterChain;

  @Autowired
  private WebApplicationContext context;

  private MockMvc               mockMvc;

  @BeforeEach
  void setup() {
    mockMvc = MockMvcBuilders.webAppContextSetup(context).addFilters(filterChain.getFilters().toArray(new Filter[0])).build();
  }

  @Test
  void activateEmailFeature() throws Exception {
    ResultActions response =
                           mockMvc.perform(patch(EMAIL_CONNECTOR_PATH + "/feature/activation?active=true").with(testAdminUser()));
    response.andExpect(status().isOk());
  }

  @Test
  void createEmailConnector() throws Exception {
    ResultActions response = mockMvc.perform(post(EMAIL_CONNECTOR_PATH).with(testAdminUser())
                                                                       .content(asJsonString(emailConnector()))
                                                                       .contentType(MediaType.APPLICATION_JSON)
                                                                       .accept(MediaType.APPLICATION_JSON));
    response.andExpect(status().isOk());
  }

  @Test
  void updateEmailConnector() throws Exception {
    ResultActions response = mockMvc.perform(put(EMAIL_CONNECTOR_PATH).with(testAdminUser())
                                                                      .content(asJsonString(emailConnector()))
                                                                      .contentType(MediaType.APPLICATION_JSON)
                                                                      .accept(MediaType.APPLICATION_JSON));
    response.andExpect(status().isOk());
  }

  @Test
  void activateEmailConnector() throws Exception {
    ResultActions response = mockMvc.perform(patch(EMAIL_CONNECTOR_PATH + "/1?active=true").with(testAdminUser()));
    response.andExpect(status().isOk());
  }

  @Test
  void deleteEmailConnector() throws Exception {
    ResultActions response = mockMvc.perform(delete(EMAIL_CONNECTOR_PATH + "/1").with(testAdminUser()));
    response.andExpect(status().isOk());
  }

  @Test
  void getEmailConnectors() throws Exception {
    ResultActions response = mockMvc.perform(get(EMAIL_CONNECTOR_PATH).with(testAdminUser()));
    response.andExpect(status().isOk());
  }

  @Test
  void getEmailBoxSyncPeriod() throws Exception {
    ResultActions response = mockMvc.perform(get(EMAIL_CONNECTOR_PATH + "/sync-period").with(testAdminUser()));
    response.andExpect(status().isOk());
  }

  @Test
  void updateEmailBoxSyncPeriod() throws Exception {
    ResultActions response = mockMvc.perform(put(EMAIL_CONNECTOR_PATH + "/sync-period?minutes=15").with(testAdminUser()));
    response.andExpect(status().isOk());
  }

  @Test
  void updateEmailBoxSyncPeriodRefusesBelowTheFloor() throws Exception {
    doThrow(new IllegalArgumentException("emailConnector.admin.syncSettings.period.outOfRange")).when(emailConnectorService)
                                                                                                 .saveEmailBoxSyncPeriod(1, ADMIN_USER);
    ResultActions response = mockMvc.perform(put(EMAIL_CONNECTOR_PATH + "/sync-period?minutes=1").with(testAdminUser()));
    response.andExpect(status().isBadRequest());
  }

  @Test
  void getEmailSyncThreads() throws Exception {
    when(emailConnectorService.getEmailSyncThreads()).thenReturn(10);
    mockMvc.perform(get(EMAIL_CONNECTOR_PATH + "/sync-threads").with(testAdminUser())).andExpect(status().isOk());
  }

  @Test
  void getEmailSyncThreadsIsForAdministratorsOnly() throws Exception {
    mockMvc.perform(get(EMAIL_CONNECTOR_PATH + "/sync-threads").with(testSimpleUser())).andExpect(status().isForbidden());
  }

  @Test
  void updateEmailSyncThreads() throws Exception {
    mockMvc.perform(put(EMAIL_CONNECTOR_PATH + "/sync-threads?threads=16").with(testAdminUser())).andExpect(status().isOk());
  }

  @Test
  void updateEmailSyncThreadsRefusesAnOutOfRangeValue() throws Exception {
    doThrow(new IllegalArgumentException("emailConnector.admin.syncThreads.outOfRange")).when(emailConnectorService)
                                                                                        .saveEmailSyncThreads(65, ADMIN_USER);
    mockMvc.perform(put(EMAIL_CONNECTOR_PATH + "/sync-threads?threads=65").with(testAdminUser())).andExpect(status().isBadRequest());
  }

  @Test
  void updateEmailSyncThreadsIsForAdministratorsOnly() throws Exception {
    mockMvc.perform(put(EMAIL_CONNECTOR_PATH + "/sync-threads?threads=16").with(testSimpleUser())).andExpect(status().isForbidden());
  }

  @Test
  void getEmailSyncStatus() throws Exception {
    when(emailSyncService.getStatus()).thenReturn(new EmailSyncExecutorStatus("node-1", 3, 0, 10, 3, 0, 0, 1040));
    mockMvc.perform(get(EMAIL_CONNECTOR_PATH + "/sync-status").with(testAdminUser()))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.running").value(3))
           .andExpect(jsonPath("$.connectedMailboxes").value(1040));
  }

  @Test
  void getEmailSyncStatusIsForAdministratorsOnly() throws Exception {
    mockMvc.perform(get(EMAIL_CONNECTOR_PATH + "/sync-status").with(testSimpleUser())).andExpect(status().isForbidden());
  }

  @Test
  void trashSync() throws Exception {
    mockMvc.perform(get(EMAIL_CONNECTOR_PATH + "/trash-sync").with(testAdminUser())).andExpect(status().isOk());
    mockMvc.perform(patch(EMAIL_CONNECTOR_PATH + "/trash-sync?enabled=false").with(testAdminUser())).andExpect(status().isOk());
  }

  @Test
  void junkSync() throws Exception {
    mockMvc.perform(get(EMAIL_CONNECTOR_PATH + "/junk-sync").with(testAdminUser())).andExpect(status().isOk());
    mockMvc.perform(patch(EMAIL_CONNECTOR_PATH + "/junk-sync?enabled=false").with(testAdminUser())).andExpect(status().isOk());
  }

  @Test
  void draftsServer() throws Exception {
    mockMvc.perform(get(EMAIL_CONNECTOR_PATH + "/drafts-server").with(testAdminUser())).andExpect(status().isOk());
    mockMvc.perform(patch(EMAIL_CONNECTOR_PATH + "/drafts-server?enabled=false").with(testAdminUser())).andExpect(status().isOk());
  }

  @Test
  void customFoldersSync() throws Exception {
    mockMvc.perform(get(EMAIL_CONNECTOR_PATH + "/custom-folders-sync").with(testAdminUser())).andExpect(status().isOk());
    mockMvc.perform(patch(EMAIL_CONNECTOR_PATH + "/custom-folders-sync?enabled=false").with(testAdminUser())).andExpect(status().isOk());
  }

  @Test
  void getEmailConnectorIllustration() throws Exception {
    when(emailConnectorService.getEmailConnector(anyLong())).thenReturn(mock(EmailConnector.class));
    when(emailConnectorService.getEmailConnectorImageInputStream(anyLong())).thenReturn(mock(InputStream.class));
    ResultActions response = mockMvc.perform(get(EMAIL_CONNECTOR_PATH + "/1/illustration").with(testSimpleUser()));
    response.andExpect(status().isOk());
  }

  private RequestPostProcessor testAdminUser() {
    return user(ADMIN_USER).password(TEST_PASSWORD).authorities(new SimpleGrantedAuthority("administrators"));
  }

  private RequestPostProcessor testSimpleUser() {
    return user(SIMPLE_USER).password(TEST_PASSWORD).authorities(new SimpleGrantedAuthority("users"));
  }

  private EmailConnector emailConnector() {
    return new EmailConnector(null,
                              "testName",
                              null,
                              null,
                              null,
                              "testImapUrl",
                              "testImapPort",
                              "testSmtpUrl",
                              "testSmtpPort",
                              "testSmtpSecurityType",
                              false,
                              false,
                              true,
                              "testUploadId",
                              "", null);
  }

  @SneakyThrows
  private String asJsonString(final Object obj) {
    return OBJECT_MAPPER.writeValueAsString(obj);
  }
}
