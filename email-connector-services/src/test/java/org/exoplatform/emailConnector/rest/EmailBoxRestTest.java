
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
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
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

import org.exoplatform.commons.exception.ObjectNotFoundException;

import com.fasterxml.jackson.core.json.JsonReadFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import org.exoplatform.emailConnector.model.Email;
import org.exoplatform.emailConnector.model.EmailAttachment;
import org.exoplatform.emailConnector.model.EmailRecipient;
import org.exoplatform.emailConnector.model.ForwardedAttachments;
import org.exoplatform.emailConnector.model.MailFolder;
import org.exoplatform.emailConnector.model.ThreadAiSummary;
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
    verify(emailBoxService).updateEmailReadStatus(emailIds, SIMPLE_USER, MailFolder.INBOX, true, true);
    // And the row's own folder when it is not the inbox: a read flag pushed against the
    // wrong folder lands on whichever message carries that number there.
    mockMvc.perform(patch(EMAIL_BOX_PATH + "?readStatus=true&folder=ARCHIVE").with(testSimpleUser())
                                                                            .content(asJsonString(emailIds))
                                                                            .contentType(MediaType.APPLICATION_JSON)
                                                                            .accept(MediaType.APPLICATION_JSON))
           .andExpect(status().isOk());
    verify(emailBoxService).updateEmailReadStatus(emailIds, SIMPLE_USER, MailFolder.ARCHIVE, true, true);
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
    response = mockMvc.perform(patch(EMAIL_BOX_PATH + "/starred?starred=true").with(testSimpleUser())
                                                                              .content(asJsonString(emailIds))
                                                                              .contentType(MediaType.APPLICATION_JSON)
                                                                              .accept(MediaType.APPLICATION_JSON));
    response.andExpect(status().isOk());
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
    // No folder on the query: the endpoint reads INBOX, which is what every client
    // written before the mailbox held other folders meant.
    verify(emailBoxService).deleteEmail(emailIds, SIMPLE_USER, MailFolder.INBOX);
  }

  /**
   * The folder travels from the caller to the service untouched — the whole of
   * EXO-89367 at this layer. A delete fired from the Sent list must reach the service
   * as SENT, or it is answered against the inbox, where that UID is another message.
   */
  @Test
  void deleteEmailCarriesTheRowsOwnFolder() throws Exception {
    List<Long> emailIds = List.of(123L);
    mockMvc.perform(delete(EMAIL_BOX_PATH + "?folder=SENT").with(testSimpleUser())
                                                          .content(asJsonString(emailIds))
                                                          .contentType(MediaType.APPLICATION_JSON)
                                                          .accept(MediaType.APPLICATION_JSON))
           .andExpect(status().isOk());
    verify(emailBoxService).deleteEmail(emailIds, SIMPLE_USER, MailFolder.SENT);
    verify(emailBoxService, never()).deleteEmail(anyList(), anyString(), eq(MailFolder.INBOX));
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
    response = mockMvc.perform(delete(EMAIL_BOX_PATH + "/archive?folder=SENT").with(testSimpleUser())
                                                                  .content(asJsonString(emailIds))
                                                                  .contentType(MediaType.APPLICATION_JSON)
                                                                  .accept(MediaType.APPLICATION_JSON));
    response.andExpect(status().isOk());
    verify(emailBoxService).archiveEmail(emailIds, SIMPLE_USER, MailFolder.SENT);
  }

  /**
   * The two Trash actions are their OWN endpoints, not flags on the delete — which is
   * what this pins: the restore is a POST to /trash/restore and the permanent delete a
   * DELETE on /trash, and each reaches its own service method with the ids it was given.
   */
  @Test
  void restoreEmail() throws Exception {
    ResultActions response = mockMvc.perform(post(EMAIL_BOX_PATH + "/trash/restore").with(testSimpleUser()));
    response.andExpect(status().isBadRequest());
    response = mockMvc.perform(post(EMAIL_BOX_PATH + "/trash/restore").with(testSimpleUser())
                                                                     .content(asJsonString(new ArrayList<Long>()))
                                                                     .contentType(MediaType.APPLICATION_JSON)
                                                                     .accept(MediaType.APPLICATION_JSON));
    response.andExpect(status().isNotFound());
    List<Long> emailIds = List.of(123L, 456L);
    response = mockMvc.perform(post(EMAIL_BOX_PATH + "/trash/restore").with(testSimpleUser())
                                                                     .content(asJsonString(emailIds))
                                                                     .contentType(MediaType.APPLICATION_JSON)
                                                                     .accept(MediaType.APPLICATION_JSON));
    response.andExpect(status().isOk());
    verify(emailBoxService).restoreEmail(emailIds, SIMPLE_USER);
    // A restore must never reach the permanent delete, whatever else changes here.
    verify(emailBoxService, never()).purgeEmail(anyList(), anyString());
  }

  @Test
  void purgeEmail() throws Exception {
    ResultActions response = mockMvc.perform(delete(EMAIL_BOX_PATH + "/trash").with(testSimpleUser()));
    response.andExpect(status().isBadRequest());
    response = mockMvc.perform(delete(EMAIL_BOX_PATH + "/trash").with(testSimpleUser())
                                                                .content(asJsonString(new ArrayList<Long>()))
                                                                .contentType(MediaType.APPLICATION_JSON)
                                                                .accept(MediaType.APPLICATION_JSON));
    // An empty body is a 404, never "everything": there is no empty-the-trash here.
    response.andExpect(status().isNotFound());
    List<Long> emailIds = List.of(123L, 456L);
    response = mockMvc.perform(delete(EMAIL_BOX_PATH + "/trash").with(testSimpleUser())
                                                                .content(asJsonString(emailIds))
                                                                .contentType(MediaType.APPLICATION_JSON)
                                                                .accept(MediaType.APPLICATION_JSON));
    response.andExpect(status().isOk());
    verify(emailBoxService).purgeEmail(emailIds, SIMPLE_USER);
    // And a permanent delete must never be answered by the ordinary one, which would
    // move the messages to the Trash they are already in and report success.
    verify(emailBoxService, never()).deleteEmail(anyList(), anyString(), anyString());
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
  void sendDraft() throws Exception {
    ResultActions response = mockMvc.perform(post(EMAIL_BOX_PATH + "/drafts/draft-1/send").with(testSimpleUser()));
    response.andExpect(status().isBadRequest());
    Email draft = new Email();
    response = mockMvc.perform(post(EMAIL_BOX_PATH + "/drafts/draft-1/send").with(testSimpleUser())
                                                                           .content(asJsonString(draft))
                                                                           .contentType(MediaType.APPLICATION_JSON)
                                                                           .accept(MediaType.APPLICATION_JSON));
    response.andExpect(status().isBadRequest());
    draft.setTo(List.of(mock(EmailRecipient.class)));
    response = mockMvc.perform(post(EMAIL_BOX_PATH + "/drafts/draft-1/send").with(testSimpleUser())
                                                                           .content(asJsonString(draft))
                                                                           .contentType(MediaType.APPLICATION_JSON)
                                                                           .accept(MediaType.APPLICATION_JSON));
    response.andExpect(status().isOk());
    // The path names the draft, whatever the body claims.
    ArgumentCaptor<Email> sent = ArgumentCaptor.forClass(Email.class);
    verify(emailBoxService).sendDraft(sent.capture(), anyString());
    org.junit.jupiter.api.Assertions.assertEquals("draft-1", sent.getValue().getDraftLocalId());
  }

  @Test
  void sendDraftAnswersNotFoundForADraftThatIsGone() throws Exception {
    Email draft = new Email();
    draft.setTo(List.of(mock(EmailRecipient.class)));
    doThrow(new ObjectNotFoundException("emailConnector.drafts.send.gone")).when(emailBoxService)
                                                                          .sendDraft(any(Email.class), anyString());
    ResultActions response = mockMvc.perform(post(EMAIL_BOX_PATH + "/drafts/gone/send").with(testSimpleUser())
                                                                                      .content(asJsonString(draft))
                                                                                      .contentType(MediaType.APPLICATION_JSON)
                                                                                      .accept(MediaType.APPLICATION_JSON));
    response.andExpect(status().isNotFound());
  }

  @Test
  void getAttachmentByMailRemoteIdAnId() throws Exception {
    ResultActions response = mockMvc.perform(get(EMAIL_BOX_PATH + "/attachments/2122121/2").with(testSimpleUser()));
    response.andExpect(status().isNotFound());
    EmailAttachment emailAttachment = mock(EmailAttachment.class);
    when(emailBoxService.getAttachmentByMailRemoteIdAnIdAndUserId(anyLong(),
                                                                  anyString(),
                                                                  anyString(),
                                                                  anyString())).thenReturn(emailAttachment);
    when(emailAttachment.getName()).thenReturn("attachment.pdf");
    when(emailAttachment.getMimeType()).thenReturn("application/pdf");
    response = mockMvc.perform(get(EMAIL_BOX_PATH + "/attachments/2122121/2").with(testSimpleUser()));
    response.andExpect(status().isOk());
  }

  /**
   * The forward's own address under a draft's attachments: it answers the draft plus the
   * files that were left behind, and it is NOT the upload endpoint one segment up.
   * <p>
   * That last part is worth an assertion rather than a reading of the annotations. A
   * literal segment sitting where {@code POST /drafts/{id}/attachments} already lives is
   * exactly the shape that resolves to the wrong handler when something changes, and the
   * wrong handler here would read the query string as an upload and answer 400 on a
   * forward that is perfectly valid.
   *
   * @throws Exception when the mocked plumbing misbehaves
   */
  @Test
  void addForwardedAttachments() throws Exception {
    String path = EMAIL_BOX_PATH + "/drafts/draft-1/attachments/forwarded";
    // No message named at all: the caller has to say what is being forwarded.
    mockMvc.perform(post(path).with(testSimpleUser())).andExpect(status().isBadRequest());
    // Named, but the user has no draft under that id or no such message in that folder.
    mockMvc.perform(post(path + "?mailRemoteId=1212&folder=INBOX").with(testSimpleUser())).andExpect(status().isNotFound());

    when(emailBoxService.addForwardedAttachments(anyString(), anyString(), anyLong(),
                                                 any())).thenReturn(new ForwardedAttachments(new Email(),
                                                                                             List.of("too-big.zip")));
    ResultActions response = mockMvc.perform(post(path + "?mailRemoteId=1212&folder=INBOX").with(testSimpleUser()));

    response.andExpect(status().isOk());
    org.junit.jupiter.api.Assertions.assertTrue(response.andReturn().getResponse().getContentAsString().contains("too-big.zip"),
                                                "the sender is told which file the forward will not carry");
    verify(emailBoxService, never()).addDraftAttachment(anyString(), anyString(), any());
  }

  /**
   * A conversation nobody has summarised answers 404, not 200 with nothing in it.
   * <p>
   * This is the answer EVERY conversation gives on a deployment with no producer
   * installed, which is most of them, so it is the normal path rather than an error one
   * — and 404 is what lets the client tell "there is none" from "there is one and it is
   * empty" without inspecting a body.
   *
   * @throws Exception when the mocked plumbing misbehaves
   */
  @Test
  void getThreadAiSummaryWithoutOneAnswersNotFound() throws Exception {
    mockMvc.perform(get(EMAIL_BOX_PATH + "/thread/thread-1/ai-summary").with(testSimpleUser()))
           .andExpect(status().isNotFound());
  }

  /**
   * A stored summary comes back with its words and its staleness — the two things a
   * reader has to render, and the only two it is given.
   *
   * @throws Exception when the mocked plumbing misbehaves
   */
  @Test
  void getThreadAiSummaryAnswersTheSummaryAndItsStaleness() throws Exception {
    when(emailBoxService.getThreadAiSummary("thread-1",
                                            SIMPLE_USER)).thenReturn(new ThreadAiSummary("They agreed on Thursday.",
                                                                                         true,
                                                                                         new java.util.Date()));

    ResultActions response = mockMvc.perform(get(EMAIL_BOX_PATH + "/thread/thread-1/ai-summary").with(testSimpleUser()));

    response.andExpect(status().isOk());
    String body = response.andReturn().getResponse().getContentAsString();
    org.junit.jupiter.api.Assertions.assertTrue(body.contains("They agreed on Thursday."));
    org.junit.jupiter.api.Assertions.assertTrue(body.contains("\"stale\":true"),
                                                "a reader has to be able to say the summary is behind the conversation");
  }

  /**
   * Asking for one is accepted, not fulfilled: 202 and not 200, because nothing on this
   * side can promise a summary will be written at all.
   *
   * @throws Exception when the mocked plumbing misbehaves
   */
  @Test
  void refreshThreadAiSummaryIsAcceptedRatherThanFulfilled() throws Exception {
    mockMvc.perform(post(EMAIL_BOX_PATH + "/thread/thread-1/ai-summary/refresh").with(testSimpleUser()))
           .andExpect(status().isAccepted());

    verify(emailBoxService).requestThreadAiSummary("thread-1", SIMPLE_USER);
  }

  /**
   * A mailbox the user may not read answers neither its summaries nor requests for
   * them, and answers the same 401 the conversation read itself answers.
   *
   * @throws Exception when the mocked plumbing misbehaves
   */
  @Test
  void anUnreadableMailboxAnswersUnauthorizedOnBothSummaryEndpoints() throws Exception {
    when(emailBoxService.getThreadAiSummary(anyString(), anyString())).thenThrow(new IllegalAccessException());
    doThrow(new IllegalAccessException()).when(emailBoxService).requestThreadAiSummary(anyString(), anyString());

    mockMvc.perform(get(EMAIL_BOX_PATH + "/thread/thread-1/ai-summary").with(testSimpleUser()))
           .andExpect(status().isUnauthorized());
    mockMvc.perform(post(EMAIL_BOX_PATH + "/thread/thread-1/ai-summary/refresh").with(testSimpleUser()))
           .andExpect(status().isUnauthorized());
  }

  private RequestPostProcessor testSimpleUser() {
    return user(SIMPLE_USER).password(TEST_PASSWORD).authorities(new SimpleGrantedAuthority("users"));
  }

  @SneakyThrows
  private String asJsonString(final Object obj) {
    return OBJECT_MAPPER.writeValueAsString(obj);
  }
}
