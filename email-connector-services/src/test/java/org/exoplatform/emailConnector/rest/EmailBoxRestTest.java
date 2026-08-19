
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
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Date;
import java.util.ArrayList;
import java.util.List;

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

import org.exoplatform.emailConnector.model.Email;
import org.exoplatform.emailConnector.model.EmailAttachment;
import org.exoplatform.emailConnector.model.EmailCategory;
import org.exoplatform.emailConnector.model.EmailSearchResult;
import org.exoplatform.emailConnector.model.EmailSearchResultPage;
import org.exoplatform.emailConnector.model.EmailSender;
import org.exoplatform.emailConnector.model.EmailRecipient;
import org.exoplatform.emailConnector.service.EmailBoxService;

import io.meeds.spring.web.security.PortalAuthenticationManager;
import io.meeds.spring.web.security.WebSecurityConfiguration;
import jakarta.servlet.Filter;
import lombok.SneakyThrows;

@SpringBootTest(classes = { EmailBoxRest.class, PortalAuthenticationManager.class })
@ContextConfiguration(classes = { WebSecurityConfiguration.class })
@AutoConfigureWebMvc
@AutoConfigureMockMvc(addFilters = false)
@ExtendWith(MockitoExtension.class)
public class EmailBoxRestTest {

  private static final String EMAIL_BOX_PATH = "/email-box";  // NOSONAR

  private static final String SIMPLE_USER    = "simple";

  private static final String TEST_PASSWORD  = "testPassword";

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
  private EmailBoxService       emailBoxService;

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
  void getEmailBox() throws Exception {
    ResultActions response = mockMvc.perform(get(EMAIL_BOX_PATH).with(testSimpleUser()));
    response.andExpect(status().isOk());
  }

  @Test
  void synchronizeUserEmails() throws Exception {
    ResultActions response = mockMvc.perform(post(EMAIL_BOX_PATH + "/synchronization").with(testSimpleUser()));
    response.andExpect(status().isOk());
  }

  @Test
  void broadcastAccessWebmail() throws Exception {
    ResultActions response = mockMvc.perform(post(EMAIL_BOX_PATH + "/webmail/broadcast").with(testSimpleUser()));
    verify(emailBoxService).broadcastAccessWebmail(SIMPLE_USER);
    response.andExpect(status().isOk());
  }

  @Test
  void updateEmailReadStatus() throws Exception {
    ResultActions response = mockMvc.perform(patch(EMAIL_BOX_PATH + "?readStatus=true").with(testSimpleUser()));
    response.andExpect(status().isBadRequest());
    List<Long> emailIds = new ArrayList<Long>();
    response = mockMvc.perform(patch(EMAIL_BOX_PATH + "?readStatus=true").with(testSimpleUser())
                                                                         .content(asJsonString(emailIds))
                                                                         .contentType(MediaType.APPLICATION_JSON)
                                                                         .accept(MediaType.APPLICATION_JSON));
    response.andExpect(status().isNotFound());
    emailIds = List.of(123L, 456L, 789L);
    response = mockMvc.perform(patch(EMAIL_BOX_PATH + "?readStatus=true").with(testSimpleUser())
                                                                         .content(asJsonString(emailIds))
                                                                         .contentType(MediaType.APPLICATION_JSON)
                                                                         .accept(MediaType.APPLICATION_JSON));
    response.andExpect(status().isOk());
  }

  @Test
  void updateEmailStarredStatus() throws Exception {
    ResultActions response = mockMvc.perform(patch(EMAIL_BOX_PATH + "/starred?starred=true").with(testSimpleUser()));
    response.andExpect(status().isBadRequest());
    List<Long> emailIds = new ArrayList<Long>();
    response = mockMvc.perform(patch(EMAIL_BOX_PATH + "/starred?starred=true").with(testSimpleUser())
                                                                              .content(asJsonString(emailIds))
                                                                              .contentType(MediaType.APPLICATION_JSON)
                                                                              .accept(MediaType.APPLICATION_JSON));
    response.andExpect(status().isNotFound());
    emailIds = List.of(123L, 456L, 789L);
    // The count of remote failures is the one part of this endpoint's contract the front end
    // reads: it drives the rollback of the optimistic star. Pin the payload, not just the status.
    when(emailBoxService.updateEmailStarredStatus(emailIds, SIMPLE_USER, true, true)).thenReturn(2);
    response = mockMvc.perform(patch(EMAIL_BOX_PATH + "/starred?starred=true").with(testSimpleUser())
                                                                              .content(asJsonString(emailIds))
                                                                              .contentType(MediaType.APPLICATION_JSON)
                                                                              .accept(MediaType.APPLICATION_JSON));
    response.andExpect(status().isOk()).andExpect(jsonPath("$.failedUpdates").value(2));
    verify(emailBoxService).updateEmailStarredStatus(emailIds, SIMPLE_USER, true, true);
  }

  @Test
  void deleteEmail() throws Exception {
    ResultActions response = mockMvc.perform(delete(EMAIL_BOX_PATH).with(testSimpleUser()));
    response.andExpect(status().isBadRequest());
    List<Long> emailIds = new ArrayList<Long>();
    response = mockMvc.perform(delete(EMAIL_BOX_PATH).with(testSimpleUser())
                                                     .content(asJsonString(emailIds))
                                                     .contentType(MediaType.APPLICATION_JSON)
                                                     .accept(MediaType.APPLICATION_JSON));
    response.andExpect(status().isNotFound());
    emailIds = List.of(123L, 456L, 789L);
    response = mockMvc.perform(delete(EMAIL_BOX_PATH).with(testSimpleUser())
                                                     .content(asJsonString(emailIds))
                                                     .contentType(MediaType.APPLICATION_JSON)
                                                     .accept(MediaType.APPLICATION_JSON));
    response.andExpect(status().isOk());
  }

  @Test
  void archiveEmail() throws Exception {
    ResultActions response = mockMvc.perform(delete(EMAIL_BOX_PATH + "/archive").with(testSimpleUser()));
    response.andExpect(status().isBadRequest());
    List<Long> emailIds = new ArrayList<Long>();
    response = mockMvc.perform(delete(EMAIL_BOX_PATH + "/archive").with(testSimpleUser())
                                                                  .content(asJsonString(emailIds))
                                                                  .contentType(MediaType.APPLICATION_JSON)
                                                                  .accept(MediaType.APPLICATION_JSON));
    response.andExpect(status().isNotFound());
    emailIds = List.of(123L, 456L, 789L);
    response = mockMvc.perform(delete(EMAIL_BOX_PATH + "/archive").with(testSimpleUser())
                                                                  .content(asJsonString(emailIds))
                                                                  .contentType(MediaType.APPLICATION_JSON)
                                                                  .accept(MediaType.APPLICATION_JSON));
    response.andExpect(status().isOk());
  }

  @Test
  void sendEmail() throws Exception {
    ResultActions response = mockMvc.perform(post(EMAIL_BOX_PATH + "/send").with(testSimpleUser()));
    response.andExpect(status().isBadRequest());
    Email email = new Email();
    response = mockMvc.perform(post(EMAIL_BOX_PATH + "/send").with(testSimpleUser())
                                                             .content(asJsonString(email))
                                                             .contentType(MediaType.APPLICATION_JSON)
                                                             .accept(MediaType.APPLICATION_JSON));
    response.andExpect(status().isNotFound());
    email.setTo(List.of(mock(EmailRecipient.class)));
    response = mockMvc.perform(post(EMAIL_BOX_PATH + "/send").with(testSimpleUser())
                                                             .content(asJsonString(email))
                                                             .contentType(MediaType.APPLICATION_JSON)
                                                             .accept(MediaType.APPLICATION_JSON));
    response.andExpect(status().isOk());
  }

  @Test
  void getFavoriteEmailById() throws Exception {
    // An id the drawer holds but the mailbox no longer has: the entry is dropped, not an error.
    ResultActions response = mockMvc.perform(get(EMAIL_BOX_PATH + "/favorites/121").with(testSimpleUser()));
    response.andExpect(status().isNotFound());
    verify(emailBoxService).getOwnedEmailById(121L, SIMPLE_USER);

    Email email = new Email();
    email.setId(121L);
    email.setSubject("Quarterly report");
    when(emailBoxService.getOwnedEmailById(121L, SIMPLE_USER)).thenReturn(email);
    response = mockMvc.perform(get(EMAIL_BOX_PATH + "/favorites/121").with(testSimpleUser()));
    response.andExpect(status().isOk()).andExpect(jsonPath("$.id").value(121)).andExpect(jsonPath("$.subject").value("Quarterly report"));

    // Somebody else's mail is reported missing rather than forbidden, so a favorite id
    // never confirms that the email exists.
    doThrow(IllegalAccessException.class).when(emailBoxService).getOwnedEmailById(anyLong(), anyString());
    response = mockMvc.perform(get(EMAIL_BOX_PATH + "/favorites/121").with(testSimpleUser()));
    response.andExpect(status().isNotFound());
  }

  @Test
  void getAttachmentByMailRemoteIdAnId() throws Exception {
    ResultActions response = mockMvc.perform(get(EMAIL_BOX_PATH + "/attachments/2122121/2").with(testSimpleUser()));
    response.andExpect(status().isNotFound());
    EmailAttachment emailAttachment = mock(EmailAttachment.class);
    when(emailBoxService.getAttachmentByMailRemoteIdAnIdAndUserId(anyLong(),
                                                                  anyString(),
                                                                  anyString())).thenReturn(emailAttachment);
    when(emailAttachment.getName()).thenReturn("attachment.pdf");
    when(emailAttachment.getMimeType()).thenReturn("application/pdf");
    response = mockMvc.perform(get(EMAIL_BOX_PATH + "/attachments/2122121/2").with(testSimpleUser()));
    response.andExpect(status().isOk());
  }

  @Test
  void searchCachedEmails() throws Exception {
    // The unified search bar's read. Its novel behaviour is the refusal: every other
    // caught IllegalAccessException in this controller becomes a 401, this one becomes
    // an EMPTY PAGE, because most users have no mailbox connected and the platform
    // asks every connector on every search.
    EmailSearchResult hit = new EmailSearchResult(121L,
                                                 "INBOX",
                                                 "Quarterly budget",
                                                 new EmailSender("Bob", "bob@example.com", null, null),
                                                 new Date(),
                                                 false,
                                                 true,
                                                 true,
                                                 "the budget is attached");
    when(emailBoxService.searchCachedEmails(SIMPLE_USER, "budget", false, 5)).thenReturn(new EmailSearchResultPage(List.of(hit),
                                                                                                                  1));
    ResultActions response = mockMvc.perform(get(EMAIL_BOX_PATH + "/search/cached?q=budget").with(testSimpleUser()));
    response.andExpect(status().isOk())
            .andExpect(jsonPath("$.totalMatches").value(1))
            .andExpect(jsonPath("$.results[0].subject").value("Quarterly budget"))
            .andExpect(jsonPath("$.results[0].starred").value(true));

    // The Favorites filter has to reach the service, not just the query string.
    when(emailBoxService.searchCachedEmails(SIMPLE_USER, "budget", true, 5)).thenReturn(new EmailSearchResultPage(List.of(),
                                                                                                                 0));
    mockMvc.perform(get(EMAIL_BOX_PATH + "/search/cached?q=budget&favorites=true").with(testSimpleUser()))
           .andExpect(status().isOk());
    verify(emailBoxService).searchCachedEmails(SIMPLE_USER, "budget", true, 5);

    // No mailbox connected: an empty section, not a 401.
    doThrow(IllegalAccessException.class).when(emailBoxService).searchCachedEmails(anyString(), anyString(), anyBoolean(), anyInt());
    response = mockMvc.perform(get(EMAIL_BOX_PATH + "/search/cached?q=budget").with(testSimpleUser()));
    response.andExpect(status().isOk()).andExpect(jsonPath("$.totalMatches").value(0)).andExpect(jsonPath("$.results").isEmpty());

    // A blank query is the service's own message code, surfaced as a 400.
    doThrow(new IllegalArgumentException("emailConnector.search.criteriaRequired")).when(emailBoxService)
                                                                                  .searchCachedEmails(anyString(),
                                                                                                      anyString(),
                                                                                                      anyBoolean(),
                                                                                                      anyInt());
    response = mockMvc.perform(get(EMAIL_BOX_PATH + "/search/cached?q=%20").with(testSimpleUser()));
    response.andExpect(status().isBadRequest());
  }

  @Test
  void getEmailCategories() throws Exception {
    when(emailBoxService.getEmailCategories(anyString(), any())).thenReturn(List.of(new EmailCategory(11L, "Important")));
    ResultActions response = mockMvc.perform(get(EMAIL_BOX_PATH + "/categories").with(testSimpleUser()));
    response.andExpect(status().isOk())
            .andExpect(jsonPath("$[0].id").value(11))
            .andExpect(jsonPath("$[0].name").value("Important"));

    doThrow(IllegalAccessException.class).when(emailBoxService).getEmailCategories(anyString(), any());
    response = mockMvc.perform(get(EMAIL_BOX_PATH + "/categories").with(testSimpleUser()));
    response.andExpect(status().isUnauthorized());
  }

  @Test
  void getAvailableEmailCategories() throws Exception {
    when(emailBoxService.getAvailableEmailCategories(anyString(),
                                                     any())).thenReturn(List.of(new EmailCategory(11L, "Important")));
    ResultActions response = mockMvc.perform(get(EMAIL_BOX_PATH + "/categories/available").with(testSimpleUser()));
    response.andExpect(status().isOk()).andExpect(jsonPath("$[0].id").value(11));
  }

  @Test
  void linkEmailsToCategory() throws Exception {
    ResultActions response = mockMvc.perform(post(EMAIL_BOX_PATH + "/categories/11").with(testSimpleUser()));
    response.andExpect(status().isBadRequest());

    when(emailBoxService.linkEmailsToCategory(anyList(), anyLong(), anyString())).thenReturn(2);
    response = mockMvc.perform(post(EMAIL_BOX_PATH + "/categories/11").with(testSimpleUser())
                                                                     .content(asJsonString(List.of(123L, 456L)))
                                                                     .contentType(MediaType.APPLICATION_JSON)
                                                                     .accept(MediaType.APPLICATION_JSON));
    response.andExpect(status().isOk()).andExpect(jsonPath("$.linked").value(2));

    // Unknown category: the service's message code is surfaced as a 400, not a 500.
    doThrow(new IllegalArgumentException("emailConnector.category.notFound")).when(emailBoxService)
                                                                             .linkEmailsToCategory(anyList(),
                                                                                                   anyLong(),
                                                                                                   anyString());
    response = mockMvc.perform(post(EMAIL_BOX_PATH + "/categories/11").with(testSimpleUser())
                                                                     .content(asJsonString(List.of(123L)))
                                                                     .contentType(MediaType.APPLICATION_JSON)
                                                                     .accept(MediaType.APPLICATION_JSON));
    response.andExpect(status().isBadRequest());

    doThrow(IllegalAccessException.class).when(emailBoxService).linkEmailsToCategory(anyList(), anyLong(), anyString());
    response = mockMvc.perform(post(EMAIL_BOX_PATH + "/categories/11").with(testSimpleUser())
                                                                     .content(asJsonString(List.of(123L)))
                                                                     .contentType(MediaType.APPLICATION_JSON)
                                                                     .accept(MediaType.APPLICATION_JSON));
    response.andExpect(status().isUnauthorized());
  }

  @Test
  void unlinkEmailsFromCategory() throws Exception {
    ResultActions response = mockMvc.perform(delete(EMAIL_BOX_PATH + "/categories/11").with(testSimpleUser()));
    response.andExpect(status().isBadRequest());

    when(emailBoxService.unlinkEmailsFromCategory(anyList(), anyLong(), anyString())).thenReturn(1);
    response = mockMvc.perform(delete(EMAIL_BOX_PATH + "/categories/11").with(testSimpleUser())
                                                                       .content(asJsonString(List.of(123L)))
                                                                       .contentType(MediaType.APPLICATION_JSON)
                                                                       .accept(MediaType.APPLICATION_JSON));
    response.andExpect(status().isOk()).andExpect(jsonPath("$.unlinked").value(1));

    doThrow(IllegalAccessException.class).when(emailBoxService).unlinkEmailsFromCategory(anyList(), anyLong(), anyString());
    response = mockMvc.perform(delete(EMAIL_BOX_PATH + "/categories/11").with(testSimpleUser())
                                                                       .content(asJsonString(List.of(123L)))
                                                                       .contentType(MediaType.APPLICATION_JSON)
                                                                       .accept(MediaType.APPLICATION_JSON));
    response.andExpect(status().isUnauthorized());
  }

  private RequestPostProcessor testSimpleUser() {
    return user(SIMPLE_USER).password(TEST_PASSWORD).authorities(new SimpleGrantedAuthority("users"));
  }

  @SneakyThrows
  private String asJsonString(final Object obj) {
    return OBJECT_MAPPER.writeValueAsString(obj);
  }
}
