
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

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureWebMvc;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
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

import org.exoplatform.emailConnector.model.EmailSignature;
import org.exoplatform.emailConnector.model.EmailSignatureLogo;
import org.exoplatform.emailConnector.model.UserEmailSetting;
import org.exoplatform.emailConnector.service.EmailSignatureService;
import org.exoplatform.emailConnector.service.UserEmailSettingService;

import io.meeds.spring.web.security.PortalAuthenticationManager;
import io.meeds.spring.web.security.WebSecurityConfiguration;

import jakarta.servlet.Filter;
import lombok.SneakyThrows;

@SpringBootTest(classes = { UserEmailSettingRest.class, PortalAuthenticationManager.class })
@ContextConfiguration(classes = { WebSecurityConfiguration.class })
@AutoConfigureWebMvc
@AutoConfigureMockMvc(addFilters = false)
@ExtendWith(MockitoExtension.class)
public class UserEmailSettingRestTest {

  private static final String USER_EMAIL_SETTING_PATH = "/user-email-setting"; // NOSONAR

  private static final String SIMPLE_USER             = "simple";

  private static final String TEST_PASSWORD           = "testPassword";

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

  @MockitoBean
  private UserEmailSettingService userEmailSettingService;

  @MockitoBean
  private EmailSignatureService   emailSignatureService;

  @Autowired
  private SecurityFilterChain     filterChain;

  @Autowired
  private WebApplicationContext   context;

  private MockMvc                 mockMvc;

  @BeforeEach
  void setup() {
    mockMvc = MockMvcBuilders.webAppContextSetup(context).addFilters(filterChain.getFilters().toArray(new Filter[0])).build();
  }

  @Test
  void connectUserEmailSetting() throws Exception {
    ResultActions response = mockMvc.perform(put(USER_EMAIL_SETTING_PATH
        + "?broadcast=false").with(testSimpleUser())
                             .content(asJsonString(userEmailSetting()))
                             .contentType(MediaType.APPLICATION_JSON)
                             .accept(MediaType.APPLICATION_JSON));
    response.andExpect(status().isOk());
  }

  @Test
  void deleteUserEmailSetting() throws Exception {
    ResultActions response = mockMvc.perform(delete(USER_EMAIL_SETTING_PATH).with(testSimpleUser()));
    response.andExpect(status().isOk());
  }

  @Test
  void getUserEmailSetting() throws Exception {
    ResultActions response = mockMvc.perform(get(USER_EMAIL_SETTING_PATH).with(testSimpleUser()));
    response.andExpect(status().isOk());
  }

  @Test
  void getUserEmailConnectors() throws Exception {
    ResultActions response = mockMvc.perform(get(USER_EMAIL_SETTING_PATH).with(testSimpleUser()));
    response.andExpect(status().isOk());
  }

  /**
   * The signature round trip at the HTTP level: reading answers the service's
   * model, storing hands the body to the service under the caller's own name.
   *
   * @throws Exception when the mock HTTP plumbing misbehaves
   */
  @Test
  void getAndSaveEmailSignature() throws Exception {
    when(emailSignatureService.getEmailSignature(SIMPLE_USER)).thenReturn(new EmailSignature(true, null, "<p>me</p>", false, null));
    mockMvc.perform(get(USER_EMAIL_SETTING_PATH + "/signature").with(testSimpleUser()))
           .andExpect(status().isOk());
    mockMvc.perform(put(USER_EMAIL_SETTING_PATH + "/signature").with(testSimpleUser())
                                                               .content(asJsonString(new EmailSignature(true,
                                                                                                        "<p>mine</p>",
                                                                                                        null,
                                                                                                        false, null)))
                                                               .contentType(MediaType.APPLICATION_JSON))
           .andExpect(status().isOk());
    verify(emailSignatureService).saveEmailSignature(eq(SIMPLE_USER), any(EmailSignature.class));
  }

  /**
   * The service's size-cap refusal comes back as the 400 the exception contract
   * promises, message code and all — what lets the settings screen say WHY.
   *
   * @throws Exception when the mock HTTP plumbing misbehaves
   */
  @Test
  void aTooLongSignatureAnswers400() throws Exception {
    doThrow(new IllegalArgumentException("emailConnector.signature.tooLong")).when(emailSignatureService)
                                                                             .saveEmailSignature(eq(SIMPLE_USER),
                                                                                                 any(EmailSignature.class));
    mockMvc.perform(put(USER_EMAIL_SETTING_PATH + "/signature").with(testSimpleUser())
                                                               .content(asJsonString(new EmailSignature(true,
                                                                                                        "<p>huge</p>",
                                                                                                        null,
                                                                                                        false, null)))
                                                               .contentType(MediaType.APPLICATION_JSON))
           .andExpect(status().isBadRequest());
  }

  /**
   * The signature image: bytes with an honest content type when there is one,
   * a plain 404 when there is none — never a broken 200.
   *
   * @throws Exception when the mock HTTP plumbing misbehaves
   */
  @Test
  void getSignatureImage() throws Exception {
    when(emailSignatureService.getSignatureLogo(SIMPLE_USER)).thenReturn(new EmailSignatureLogo(new byte[] { 1, 2 },
                                                                                                "image/png",
                                                                                                "logo"));
    mockMvc.perform(get(USER_EMAIL_SETTING_PATH + "/signature/image").with(testSimpleUser()))
           .andExpect(status().isOk());
    when(emailSignatureService.getSignatureLogo(SIMPLE_USER)).thenReturn(null);
    mockMvc.perform(get(USER_EMAIL_SETTING_PATH + "/signature/image").with(testSimpleUser()))
           .andExpect(status().isNotFound());
  }

  /**
   * Replacing and resetting the signature image, including the 400 a dead
   * upload id earns.
   *
   * @throws Exception when the mock HTTP plumbing misbehaves
   */
  @Test
  void saveAndDeleteSignatureImage() throws Exception {
    mockMvc.perform(put(USER_EMAIL_SETTING_PATH + "/signature/image?uploadId=up1").with(testSimpleUser()))
           .andExpect(status().isOk());
    verify(emailSignatureService).saveSignatureLogo(SIMPLE_USER, "up1");
    doThrow(new IllegalArgumentException("emailConnector.signature.logo.uploadGone")).when(emailSignatureService)
                                                                                     .saveSignatureLogo(SIMPLE_USER, "gone");
    mockMvc.perform(put(USER_EMAIL_SETTING_PATH + "/signature/image?uploadId=gone").with(testSimpleUser()))
           .andExpect(status().isBadRequest());
    mockMvc.perform(delete(USER_EMAIL_SETTING_PATH + "/signature/image").with(testSimpleUser()))
           .andExpect(status().isOk());
    verify(emailSignatureService).deleteSignatureLogo(SIMPLE_USER);
  }

  private RequestPostProcessor testSimpleUser() {
    return user(SIMPLE_USER).password(TEST_PASSWORD).authorities(new SimpleGrantedAuthority("users"));
  }

  private UserEmailSetting userEmailSetting() {
    return new UserEmailSetting("1", "testEmail", "testPassword", null, null, 0, 0L, null, null, null, true);
  }

  @SneakyThrows
  private String asJsonString(final Object obj) {
    return OBJECT_MAPPER.writeValueAsString(obj);
  }
}
