
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

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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

import org.exoplatform.commons.api.settings.ExoFeatureService;
import org.exoplatform.emailConnector.model.EmailConnector;
import org.exoplatform.emailConnector.model.UserEmailSetting;
import org.exoplatform.emailConnector.service.EmailConnectorService;

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

  private static final String EMAIL_CONNECTOR_PATH = "/emailConnector"; // NOSONAR

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
  private ExoFeatureService     featureService;

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
  void activate() throws Exception {
    ResultActions response = mockMvc.perform(put(EMAIL_CONNECTOR_PATH + "/activate/true").with(testAdminUser()));
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
    ResultActions response = mockMvc.perform(patch(EMAIL_CONNECTOR_PATH + "/1/true").with(testAdminUser()));
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
  void getActiveEmailConnectors() throws Exception {
    ResultActions response = mockMvc.perform(get(EMAIL_CONNECTOR_PATH + "/active").with(testSimpleUser()));
    response.andExpect(status().isOk());
  }

  @Test
  void setUserEmailSetting() throws Exception {
    ResultActions response = mockMvc.perform(put(EMAIL_CONNECTOR_PATH
        + "/userEmailSetting").with(testSimpleUser())
                              .content(asJsonString(userEmailSetting()))
                              .contentType(MediaType.APPLICATION_JSON)
                              .accept(MediaType.APPLICATION_JSON));
    response.andExpect(status().isOk());
  }

  @Test
  void getUserEmailSetting() throws Exception {
    ResultActions response = mockMvc.perform(get(EMAIL_CONNECTOR_PATH + "/userEmailSetting").with(testSimpleUser()));
    response.andExpect(status().isOk());
  }

  @Test
  void deleteUserEmailSetting() throws Exception {
    ResultActions response = mockMvc.perform(delete(EMAIL_CONNECTOR_PATH + "/userEmailSetting").with(testSimpleUser()));
    response.andExpect(status().isOk());
  }

  private RequestPostProcessor testAdminUser() {
    return user(ADMIN_USER).password(TEST_PASSWORD).authorities(new SimpleGrantedAuthority("administrators"));
  }

  private RequestPostProcessor testSimpleUser() {
    return user(SIMPLE_USER).password(TEST_PASSWORD).authorities(new SimpleGrantedAuthority("users"));
  }

  private EmailConnector emailConnector() {
    return new EmailConnector(null, "testName", null, null, null, "testImapUrl", "testPort", false, false, true, "testUploadId");
  }

  private UserEmailSetting userEmailSetting() {
    return new UserEmailSetting("1", "testEmail", "testPassword", null, null, 0, 0L, null, null, null, true);
  }

  @SneakyThrows
  public static String asJsonString(final Object obj) {
    return OBJECT_MAPPER.writeValueAsString(obj);
  }
}
