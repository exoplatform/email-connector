
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

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Date;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureWebMvc;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import org.exoplatform.commons.exception.ObjectNotFoundException;

import com.fasterxml.jackson.core.json.JsonReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import org.exoplatform.emailConnector.exception.DelegationRevokedException;
import org.exoplatform.emailConnector.model.SendMode;
import org.exoplatform.emailConnector.exception.SendModeUnavailableException;
import org.exoplatform.emailConnector.exception.SendModeMissingException;
import org.exoplatform.emailConnector.exception.MailboxRightMissingException;
import org.exoplatform.emailConnector.exception.ReadReceiptConflictException;
import org.exoplatform.emailConnector.exception.ScheduledSendConflictException;
import org.exoplatform.emailConnector.model.ReadReceiptAction;
import org.exoplatform.emailConnector.model.ReadReceiptPrompt;
import org.exoplatform.emailConnector.model.ReadReceiptState;
import org.exoplatform.emailConnector.service.ReadReceiptService;
import org.exoplatform.emailConnector.model.Email;
import org.exoplatform.emailConnector.model.ScheduledEmail;
import org.exoplatform.emailConnector.model.ScheduledSendStatus;
import org.exoplatform.emailConnector.model.EmailAttachment;
import org.exoplatform.emailConnector.model.EmailCategory;
import org.exoplatform.emailConnector.model.EmailSearchResult;
import org.exoplatform.emailConnector.model.EmailSearchResultPage;
import org.exoplatform.emailConnector.model.EmailSender;
import org.exoplatform.emailConnector.model.DraftMailbox;
import org.exoplatform.emailConnector.model.EmailRecipient;
import org.exoplatform.emailConnector.model.ForwardedAttachments;
import org.exoplatform.emailConnector.model.MailFolder;
import org.exoplatform.emailConnector.model.MailboxRights;
import org.exoplatform.emailConnector.model.RestoreOutcome;
import org.exoplatform.emailConnector.model.ThreadAiSummary;
import org.exoplatform.emailConnector.rest.model.ScheduleRequest;
import org.exoplatform.emailConnector.service.EmailBoxService;
import org.exoplatform.emailConnector.service.EmailScheduledSendService;

import io.meeds.spring.web.security.PortalAuthenticationManager;
import io.meeds.spring.web.security.WebSecurityConfiguration;

import jakarta.servlet.Filter;
import lombok.SneakyThrows;

@SpringBootTest(classes = { EmailBoxRest.class, PortalAuthenticationManager.class })
@ContextConfiguration(classes = { WebSecurityConfiguration.class })
@TestPropertySource(properties = "spring.jackson.deserialization.fail-on-null-for-primitives=false")
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
                              .disable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
                              .build();
    OBJECT_MAPPER.registerModule(new JavaTimeModule());
  }

  @MockitoBean
  private EmailBoxService       emailBoxService;

  @MockitoBean
  private EmailScheduledSendService emailScheduledSendService;

  @MockitoBean
  private ReadReceiptService    readReceiptService;

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

  /**
   * The listing takes a folder key of a mailbox somebody shared with the caller exactly
   * as it takes one of their own: the key is a folder-registry id, and whose mailbox
   * that folder belongs to is the service's to answer -- the transport neither knows
   * nor checks (EXO-90499).
   *
   * @throws Exception when the request cannot be performed
   */
  @Test
  void theListingTakesADelegatedFolderKey() throws Exception {
    mockMvc.perform(get(EMAIL_BOX_PATH).param("folder", "CUSTOM:8").with(testSimpleUser())).andExpect(status().isOk());

    verify(emailBoxService).getEmailBox(SIMPLE_USER, "CUSTOM:8", false);
  }

  /**
   * A write the mail server does not let the caller make in a shared mailbox answers
   * <b>403 with the missing right's code</b>, not a bare refusal: the REST layer maps
   * {@code IllegalAccessException} to 403 (EXO-90627), and what the delegated
   * subclass adds is the message the interface needs to say WHICH right is missing and
   * to correct chrome that went stale (EXO-90499).
   *
   * @throws Exception when the request cannot be performed
   */
  @Test
  void aWriteTheShareDoesNotAllowAnswersTheMissingRightsCode() throws Exception {
    when(emailBoxService.updateEmailReadStatus(anyList(), anyString(), anyString(), anyBoolean(), anyBoolean()))
                                                                                                               .thenThrow(new MailboxRightMissingException(MailboxRights.KEEP_SEEN));

    mockMvc.perform(patch(EMAIL_BOX_PATH).param("readStatus", "true")
                                         .param("folder", "CUSTOM:8")
                                         .contentType(MediaType.APPLICATION_JSON)
                                         .content("[1212]")
                                         .with(testSimpleUser()))
           .andExpect(status().isForbidden())
           .andExpect(status().reason(MailboxRightMissingException.CODE_PREFIX + "s"));
  }

  /**
   * A share that has been withdrawn while the drawer was open is <b>410 Gone</b> with
   * its own code -- a different answer from "you may not do that", because the client's
   * next move is different: go back to the caller's own mailbox rather than re-offer
   * the action (EXO-90499).
   *
   * @throws Exception when the request cannot be performed
   */
  @Test
  void aWithdrawnShareAnswersGone() throws Exception {
    when(emailBoxService.getEmailBox(anyString(), anyString(), anyBoolean()))
                                                                            .thenThrow(new DelegationRevokedException(DelegationRevokedException.REVOKED));

    mockMvc.perform(get(EMAIL_BOX_PATH).param("folder", "CUSTOM:8").with(testSimpleUser()))
           .andExpect(status().isGone())
           .andExpect(status().reason(DelegationRevokedException.REVOKED));
  }

  /**
   * EXO-90590 -- the mail drawer's search box in a shared mailbox whose share was
   * withdrawn meanwhile is <b>410 Gone</b> with the share's code, as the listing is, so
   * the drawer leaves that mailbox and says why rather than showing a failed search.
   *
   * @throws Exception when the request cannot be performed
   */
  @Test
  void aSearchInAWithdrawnShareAnswersGone() throws Exception {
    when(emailBoxService.searchEmails(anyString(), anyString(), any(), any(), anyBoolean(), anyBoolean(), any(), anyString(), anyInt()))
                                                                                                                                   .thenThrow(new DelegationRevokedException(DelegationRevokedException.REVOKED));

    mockMvc.perform(get(EMAIL_BOX_PATH + "/search").param("query", "budget").param("folder", "CUSTOM:8").with(testSimpleUser()))
           .andExpect(status().isGone())
           .andExpect(status().reason(DelegationRevokedException.REVOKED));
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

  /**
   * EXO-90414: an explicit read counts as an opening, as it always has; a read the
   * reader made on its own (broadcast=false) does not, on the full answer or on the
   * not-modified one.
   *
   * @throws Exception when the request cannot be performed
   */
  @Test
  void getRemoteEmailByIdBroadcastsTheOpeningUnlessAskedNotTo() throws Exception {
    Email email = new Email();
    email.setId(7L);
    when(emailBoxService.getEmailByMailRemoteIdAndUserId(anyLong(), anyString(), anyString(), anyBoolean(), anyBoolean(), anyBoolean(), anyBoolean()))
      .thenReturn(email);

    mockMvc.perform(get(EMAIL_BOX_PATH + "/7").with(testSimpleUser())).andExpect(status().isOk());
    verify(emailBoxService).getEmailByMailRemoteIdAndUserId(7L, SIMPLE_USER, "INBOX", true, true, true, true);

    mockMvc.perform(get(EMAIL_BOX_PATH + "/7").param("broadcast", "false").with(testSimpleUser())).andExpect(status().isOk());
    verify(emailBoxService).getEmailByMailRemoteIdAndUserId(7L, SIMPLE_USER, "INBOX", true, true, true, false);

    String eTag = "\"" + java.util.Objects.hash(7L, "INBOX", SIMPLE_USER) + "\"";
    mockMvc.perform(get(EMAIL_BOX_PATH + "/7").param("broadcast", "false").header("If-None-Match", eTag).with(testSimpleUser()))
           .andExpect(status().isNotModified());
    verify(emailBoxService, never()).broadcastOpenEmail(anyString());

    mockMvc.perform(get(EMAIL_BOX_PATH + "/7").header("If-None-Match", eTag).with(testSimpleUser()))
           .andExpect(status().isNotModified());
    verify(emailBoxService).broadcastOpenEmail(SIMPLE_USER);
  }

  /**
   * EXO-90414: the deferred opening signal broadcasts once, for the acting user only,
   * and a user whose mailbox is not theirs to read is refused.
   *
   * @throws Exception when the request cannot be performed
   */
  @Test
  void broadcastOpenEmail() throws Exception {
    mockMvc.perform(post(EMAIL_BOX_PATH + "/open/broadcast").with(testSimpleUser())).andExpect(status().isOk());
    verify(emailBoxService).broadcastOpenEmail(SIMPLE_USER);

    doThrow(new IllegalAccessException("not allowed")).when(emailBoxService).broadcastOpenEmail(SIMPLE_USER);
    mockMvc.perform(post(EMAIL_BOX_PATH + "/open/broadcast").with(testSimpleUser())).andExpect(status().isForbidden());
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
    // The count of remote failures is the one part of this endpoint's contract the front end
    // reads: it drives the rollback of the optimistic star. Pin the payload, not just the status.
    when(emailBoxService.updateEmailStarredStatus(emailIds, SIMPLE_USER, MailFolder.INBOX, true, true)).thenReturn(2);
    response = mockMvc.perform(patch(EMAIL_BOX_PATH + "/starred?starred=true").with(testSimpleUser())
                                                                              .content(asJsonString(emailIds))
                                                                              .contentType(MediaType.APPLICATION_JSON)
                                                                              .accept(MediaType.APPLICATION_JSON));
    response.andExpect(status().isOk()).andExpect(jsonPath("$.failedUpdates").value(2));
    verify(emailBoxService).updateEmailStarredStatus(emailIds, SIMPLE_USER, MailFolder.INBOX, true, true);
  }

  /**
   * EXO-90550 -- the folder travels to the service, and its refusals keep their codes:
   * a folder the star is not offered in is a 400, a missing right a 403, a share gone a
   * 410.
   */
  @Test
  void aStarInAFolderIsAddressedThereAndItsRefusalsKeepTheirCodes() throws Exception {
    List<Long> emailIds = List.of(7L);
    when(emailBoxService.updateEmailStarredStatus(emailIds, SIMPLE_USER, "CUSTOM:10", true, true)).thenReturn(0);
    mockMvc.perform(patch(EMAIL_BOX_PATH + "/starred?starred=true&folder=CUSTOM:10").with(testSimpleUser())
                                                                                    .content(asJsonString(emailIds))
                                                                                    .contentType(MediaType.APPLICATION_JSON)
                                                                                    .accept(MediaType.APPLICATION_JSON))
           .andExpect(status().isOk());
    verify(emailBoxService).updateEmailStarredStatus(emailIds, SIMPLE_USER, "CUSTOM:10", true, true);

    when(emailBoxService.updateEmailStarredStatus(emailIds, SIMPLE_USER, "SENT", true, true))
        .thenThrow(new IllegalArgumentException("emailConnector.star.folderNotSupported"));
    mockMvc.perform(patch(EMAIL_BOX_PATH + "/starred?starred=true&folder=SENT").with(testSimpleUser())
                                                                               .content(asJsonString(emailIds))
                                                                               .contentType(MediaType.APPLICATION_JSON)
                                                                               .accept(MediaType.APPLICATION_JSON))
           .andExpect(status().isBadRequest());
    when(emailBoxService.updateEmailStarredStatus(emailIds, SIMPLE_USER, "CUSTOM:11", true, true))
        .thenThrow(new MailboxRightMissingException(MailboxRights.WRITE));
    mockMvc.perform(patch(EMAIL_BOX_PATH + "/starred?starred=true&folder=CUSTOM:11").with(testSimpleUser())
                                                                                    .content(asJsonString(emailIds))
                                                                                    .contentType(MediaType.APPLICATION_JSON)
                                                                                    .accept(MediaType.APPLICATION_JSON))
           .andExpect(status().isForbidden());
    when(emailBoxService.updateEmailStarredStatus(emailIds, SIMPLE_USER, "CUSTOM:12", true, true))
        .thenThrow(new DelegationRevokedException(DelegationRevokedException.REVOKED));
    mockMvc.perform(patch(EMAIL_BOX_PATH + "/starred?starred=true&folder=CUSTOM:12").with(testSimpleUser())
                                                                                    .content(asJsonString(emailIds))
                                                                                    .contentType(MediaType.APPLICATION_JSON)
                                                                                    .accept(MediaType.APPLICATION_JSON))
           .andExpect(status().isGone());
  }

  /**
   * EXO-90551 -- a send from a shared mailbox names its share and answers what became of
   * the owner's copy; a share that is not the caller's is a 404 and one gone a 410,
   * nothing sent; a send from the caller's own mailbox answers no ownerCopy at all.
   */
  @Test
  void aSendFromASharedMailboxAnswersTheOwnersCopy() throws Exception {
    Email email = new Email();
    email.setTo(List.of(new EmailRecipient()));
    when(emailBoxService.sendEmail(any(Email.class), eq(SIMPLE_USER), eq(5L), isNull())).thenReturn(EmailBoxService.OwnerCopy.FILED);
    mockMvc.perform(post(EMAIL_BOX_PATH + "/send?delegationId=5").with(testSimpleUser())
                                                                 .content(asJsonString(email))
                                                                 .contentType(MediaType.APPLICATION_JSON)
                                                                 .accept(MediaType.APPLICATION_JSON))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.ownerCopy").value("FILED"));
    when(emailBoxService.sendEmail(any(Email.class), eq(SIMPLE_USER), eq(6L), isNull())).thenThrow(new ObjectNotFoundException("emailConnector.delegation.notFound"));
    mockMvc.perform(post(EMAIL_BOX_PATH + "/send?delegationId=6").with(testSimpleUser())
                                                                 .content(asJsonString(email))
                                                                 .contentType(MediaType.APPLICATION_JSON))
           .andExpect(status().isNotFound());
    when(emailBoxService.sendEmail(any(Email.class), eq(SIMPLE_USER), eq(7L), isNull())).thenThrow(new DelegationRevokedException(DelegationRevokedException.REVOKED));
    mockMvc.perform(post(EMAIL_BOX_PATH + "/send?delegationId=7").with(testSimpleUser())
                                                                 .content(asJsonString(email))
                                                                 .contentType(MediaType.APPLICATION_JSON))
           .andExpect(status().isGone());
    mockMvc.perform(post(EMAIL_BOX_PATH + "/send").with(testSimpleUser())
                                                  .content(asJsonString(email))
                                                  .contentType(MediaType.APPLICATION_JSON)
                                                  .accept(MediaType.APPLICATION_JSON))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.ownerCopy").doesNotExist());
    // The composer's draft path answers the same (EXO-90551).
    when(emailBoxService.sendDraft(any(Email.class), eq(SIMPLE_USER), eq(5L), isNull())).thenReturn(EmailBoxService.OwnerCopy.SKIPPED);
    mockMvc.perform(post(EMAIL_BOX_PATH + "/drafts/d1/send?delegationId=5").with(testSimpleUser())
                                                                           .content(asJsonString(email))
                                                                           .contentType(MediaType.APPLICATION_JSON)
                                                                           .accept(MediaType.APPLICATION_JSON))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.ownerCopy").value("SKIPPED"));
    when(emailBoxService.sendDraft(any(Email.class), eq(SIMPLE_USER), eq(7L), isNull())).thenThrow(new DelegationRevokedException(DelegationRevokedException.REVOKED));
    mockMvc.perform(post(EMAIL_BOX_PATH + "/drafts/d1/send?delegationId=7").with(testSimpleUser())
                                                                           .content(asJsonString(email))
                                                                           .contentType(MediaType.APPLICATION_JSON))
           .andExpect(status().isGone());
  }

  /**
   * EXO-90583 -- the shape asked for a mail in the owner's name travels to the service
   * as the request spells it, beside the share; the guard's refusals come back with
   * their codes: 403 naming the shape the consent does not cover, 400 for a shape that
   * cannot be used -- the owner's server's refusal included -- on both send endpoints.
   */
  @Test
  void aSendInTheOwnersNameCarriesItsShapeAndItsRefusals() throws Exception {
    Email email = new Email();
    email.setTo(List.of(new EmailRecipient()));
    when(emailBoxService.sendEmail(any(Email.class), eq(SIMPLE_USER), eq(5L), eq("ON_BEHALF"))).thenReturn(EmailBoxService.OwnerCopy.FILED);
    mockMvc.perform(post(EMAIL_BOX_PATH + "/send?delegationId=5&sendMode=ON_BEHALF").with(testSimpleUser())
                                                                                    .content(asJsonString(email))
                                                                                    .contentType(MediaType.APPLICATION_JSON)
                                                                                    .accept(MediaType.APPLICATION_JSON))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.ownerCopy").value("FILED"));
    when(emailBoxService.sendEmail(any(Email.class), eq(SIMPLE_USER), eq(6L), eq("AS"))).thenThrow(new SendModeMissingException(SendMode.AS));
    mockMvc.perform(post(EMAIL_BOX_PATH + "/send?delegationId=6&sendMode=AS").with(testSimpleUser())
                                                                             .content(asJsonString(email))
                                                                             .contentType(MediaType.APPLICATION_JSON))
           .andExpect(status().isForbidden())
           .andExpect(status().reason("emailConnector.sendMode.missing.AS"));
    when(emailBoxService.sendEmail(any(Email.class), eq(SIMPLE_USER), eq(7L), eq("AS"))).thenThrow(new SendModeUnavailableException(SendModeUnavailableException.REFUSED_BY_SERVER));
    mockMvc.perform(post(EMAIL_BOX_PATH + "/send?delegationId=7&sendMode=AS").with(testSimpleUser())
                                                                             .content(asJsonString(email))
                                                                             .contentType(MediaType.APPLICATION_JSON))
           .andExpect(status().isBadRequest())
           .andExpect(status().reason(SendModeUnavailableException.REFUSED_BY_SERVER));
    when(emailBoxService.sendDraft(any(Email.class), eq(SIMPLE_USER), isNull(), eq("AS"))).thenThrow(new SendModeMissingException(SendMode.AS));
    mockMvc.perform(post(EMAIL_BOX_PATH + "/drafts/d1/send?sendMode=AS").with(testSimpleUser())
                                                                        .content(asJsonString(email))
                                                                        .contentType(MediaType.APPLICATION_JSON))
           .andExpect(status().isForbidden())
           .andExpect(status().reason("emailConnector.sendMode.missing.AS"));
    when(emailBoxService.sendDraft(any(Email.class), eq(SIMPLE_USER), eq(8L), eq("ON_BEHALF"))).thenThrow(new SendModeUnavailableException(SendModeUnavailableException.REFUSED_BY_SERVER));
    mockMvc.perform(post(EMAIL_BOX_PATH + "/drafts/d1/send?delegationId=8&sendMode=ON_BEHALF").with(testSimpleUser())
                                                                                              .content(asJsonString(email))
                                                                                              .contentType(MediaType.APPLICATION_JSON))
           .andExpect(status().isBadRequest())
           .andExpect(status().reason(SendModeUnavailableException.REFUSED_BY_SERVER));
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

  /**
   * Stack review #437-3 -- a refusal by message code (a move between two mailboxes) is
   * a 400 carrying the code, never the 500 an uncaught refusal would be, on delete as on
   * archive.
   */
  @Test
  void aRefusalByMessageCodeIsABadRequest() throws Exception {
    List<Long> emailIds = List.of(123L);
    when(emailBoxService.deleteEmail(emailIds, SIMPLE_USER, "CUSTOM:8")).thenThrow(new IllegalArgumentException("emailConnector.folder.crossMailbox"));
    when(emailBoxService.archiveEmail(emailIds, SIMPLE_USER, "CUSTOM:8")).thenThrow(new IllegalArgumentException("emailConnector.folder.crossMailbox"));

    mockMvc.perform(delete(EMAIL_BOX_PATH + "?folder=CUSTOM:8").with(testSimpleUser())
                                                              .content(asJsonString(emailIds))
                                                              .contentType(MediaType.APPLICATION_JSON))
           .andExpect(status().isBadRequest())
           .andExpect(status().reason("emailConnector.folder.crossMailbox"));
    mockMvc.perform(delete(EMAIL_BOX_PATH + "/archive?folder=CUSTOM:8").with(testSimpleUser())
                                                                      .content(asJsonString(emailIds))
                                                                      .contentType(MediaType.APPLICATION_JSON))
           .andExpect(status().isBadRequest());
  }

  /**
   * EXO-90548, live on Stalwart -- a shared mailbox with no Trash, Archive or Spam to
   * file into: the conversation delete and spam and the archive are 400s carrying the
   * missing destination's own code, which the interface can say.
   */
  @Test
  void aMissingSharedDestinationIsABadRequestWithItsOwnCode() throws Exception {
    List<Long> emailIds = List.of(123L);
    when(emailBoxService.deleteConversations(emailIds, SIMPLE_USER, "CUSTOM:8")).thenThrow(new IllegalArgumentException("emailConnector.delegation.noTrash"));
    when(emailBoxService.archiveEmail(emailIds, SIMPLE_USER, "CUSTOM:8")).thenThrow(new IllegalArgumentException("emailConnector.delegation.noArchive"));
    when(emailBoxService.markConversationsAsJunk(emailIds, SIMPLE_USER, "CUSTOM:8")).thenThrow(new IllegalArgumentException("emailConnector.delegation.noJunk"));

    mockMvc.perform(delete(EMAIL_BOX_PATH + "?folder=CUSTOM:8&conversation=true").with(testSimpleUser())
                                                                                .content(asJsonString(emailIds))
                                                                                .contentType(MediaType.APPLICATION_JSON))
           .andExpect(status().isBadRequest())
           .andExpect(status().reason("emailConnector.delegation.noTrash"));
    mockMvc.perform(delete(EMAIL_BOX_PATH + "/archive?folder=CUSTOM:8").with(testSimpleUser())
                                                                      .content(asJsonString(emailIds))
                                                                      .contentType(MediaType.APPLICATION_JSON))
           .andExpect(status().isBadRequest())
           .andExpect(status().reason("emailConnector.delegation.noArchive"));
    mockMvc.perform(post(EMAIL_BOX_PATH + "/junk?folder=CUSTOM:8&conversation=true").with(testSimpleUser())
                                                                                   .content(asJsonString(emailIds))
                                                                                   .contentType(MediaType.APPLICATION_JSON))
           .andExpect(status().isBadRequest())
           .andExpect(status().reason("emailConnector.delegation.noJunk"));
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
    when(emailBoxService.restore(emailIds, SIMPLE_USER, MailFolder.TRASH)).thenReturn(new RestoreOutcome(0, List.of(456L)));
    response = mockMvc.perform(post(EMAIL_BOX_PATH + "/trash/restore").with(testSimpleUser())
                                                                     .content(asJsonString(emailIds))
                                                                     .contentType(MediaType.APPLICATION_JSON)
                                                                     .accept(MediaType.APPLICATION_JSON));
    response.andExpect(status().isOk())
            .andExpect(jsonPath("$.failedRestores").value(0))
            // The answer says which ids went back to Sent rather than to the inbox
            // (EXO-89942): the client shows them there before the server lists them.
            .andExpect(jsonPath("$.restoredToSent[0]").value(456));
    verify(emailBoxService).restore(emailIds, SIMPLE_USER, MailFolder.TRASH);
    // A restore must never reach the permanent delete, whatever else changes here.
    verify(emailBoxService, never()).purgeEmail(anyList(), anyString());
  }

  /**
   * The conversation flag is what turns a delete of the listed ids into a delete of
   * their whole conversations, Sent half included (EXO-89942). Off, or omitted, the
   * endpoint reaches the single-message delete every earlier client meant.
   */
  @Test
  void deleteEmailWithTheConversationFlagReachesTheConversationDelete() throws Exception {
    List<Long> emailIds = List.of(123L);
    mockMvc.perform(delete(EMAIL_BOX_PATH + "?conversation=true").with(testSimpleUser())
                                                                .content(asJsonString(emailIds))
                                                                .contentType(MediaType.APPLICATION_JSON)
                                                                .accept(MediaType.APPLICATION_JSON))
           .andExpect(status().isOk());
    verify(emailBoxService).deleteConversations(emailIds, SIMPLE_USER, MailFolder.INBOX);
    verify(emailBoxService, never()).deleteEmail(anyList(), anyString(), anyString());

    mockMvc.perform(delete(EMAIL_BOX_PATH + "?folder=SENT&conversation=false").with(testSimpleUser())
                                                                             .content(asJsonString(emailIds))
                                                                             .contentType(MediaType.APPLICATION_JSON)
                                                                             .accept(MediaType.APPLICATION_JSON))
           .andExpect(status().isOk());
    verify(emailBoxService).deleteEmail(emailIds, SIMPLE_USER, MailFolder.SENT);
    verify(emailBoxService, never()).deleteConversations(anyList(), anyString(), eq(MailFolder.SENT));
  }

  /**
   * The folder a conversation is read from travels to the service on both thread
   * reads (EXO-89942): opened from the Trash, the reader gets the Trash copies. Without
   * it the service reads the conversation as it always did.
   */
  @Test
  void theThreadReadsCarryTheFolderTheyAreReadFrom() throws Exception {
    mockMvc.perform(get(EMAIL_BOX_PATH + "/thread/thread-1?folder=TRASH").with(testSimpleUser())).andExpect(status().isOk());
    verify(emailBoxService).getThread("thread-1", SIMPLE_USER, MailFolder.TRASH);

    mockMvc.perform(get(EMAIL_BOX_PATH + "/thread/thread-1").with(testSimpleUser())).andExpect(status().isOk());
    verify(emailBoxService).getThread("thread-1", SIMPLE_USER, null);

    mockMvc.perform(get(EMAIL_BOX_PATH + "/thread/thread-1/complete?folder=JUNK").with(testSimpleUser())).andExpect(status().isOk());
    verify(emailBoxService).completeThread("thread-1", SIMPLE_USER, MailFolder.JUNK);
  }

  /**
   * The same flag on "Mark as spam", with the same two outcomes.
   */
  @Test
  void markAsJunkWithTheConversationFlagReachesTheConversationJunk() throws Exception {
    List<Long> emailIds = List.of(123L);
    mockMvc.perform(post(EMAIL_BOX_PATH + "/junk").with(testSimpleUser())
                                                  .param("conversation", "true")
                                                  .content(asJsonString(emailIds))
                                                  .contentType(MediaType.APPLICATION_JSON)
                                                  .accept(MediaType.APPLICATION_JSON))
           .andExpect(status().isOk());
    verify(emailBoxService).markConversationsAsJunk(emailIds, SIMPLE_USER, MailFolder.INBOX);
    verify(emailBoxService, never()).markAsJunk(anyList(), anyString(), anyString());
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

  /**
   * "Mark as spam" is its own POST, folder-addressed exactly as the delete is, and
   * reaches its own service method — never the delete, never the archive: the three
   * take the same rows to three different fates, and a route that aliased one to
   * another would report success on the wrong outcome.
   */
  @Test
  void markAsJunk() throws Exception {
    ResultActions response = mockMvc.perform(post(EMAIL_BOX_PATH + "/junk").with(testSimpleUser()));
    response.andExpect(status().isBadRequest());
    response = mockMvc.perform(post(EMAIL_BOX_PATH + "/junk").with(testSimpleUser())
                                                             .content(asJsonString(new ArrayList<Long>()))
                                                             .contentType(MediaType.APPLICATION_JSON)
                                                             .accept(MediaType.APPLICATION_JSON));
    response.andExpect(status().isNotFound());
    List<Long> emailIds = List.of(123L, 456L);
    response = mockMvc.perform(post(EMAIL_BOX_PATH + "/junk").with(testSimpleUser())
                                                             .content(asJsonString(emailIds))
                                                             .contentType(MediaType.APPLICATION_JSON)
                                                             .accept(MediaType.APPLICATION_JSON));
    response.andExpect(status().isOk());
    // INBOX when the folder is omitted, as for the delete.
    verify(emailBoxService).markAsJunk(emailIds, SIMPLE_USER, MailFolder.INBOX);
    response = mockMvc.perform(post(EMAIL_BOX_PATH + "/junk").with(testSimpleUser())
                                                             .param("folder", MailFolder.SENT)
                                                             .content(asJsonString(emailIds))
                                                             .contentType(MediaType.APPLICATION_JSON)
                                                             .accept(MediaType.APPLICATION_JSON));
    response.andExpect(status().isOk());
    verify(emailBoxService).markAsJunk(emailIds, SIMPLE_USER, MailFolder.SENT);
    verify(emailBoxService, never()).deleteEmail(anyList(), anyString(), anyString());
    verify(emailBoxService, never()).archiveEmail(anyList(), anyString(), anyString());
  }

  /**
   * "Not spam" is the Junk folder's own restore, on its own route: the ids it takes
   * are numbered within the Junk folder, and the Trash restore addressed with them
   * would act on whatever message holds those numbers in the Trash.
   */
  @Test
  void restoreFromJunk() throws Exception {
    ResultActions response = mockMvc.perform(post(EMAIL_BOX_PATH + "/junk/restore").with(testSimpleUser()));
    response.andExpect(status().isBadRequest());
    response = mockMvc.perform(post(EMAIL_BOX_PATH + "/junk/restore").with(testSimpleUser())
                                                                     .content(asJsonString(new ArrayList<Long>()))
                                                                     .contentType(MediaType.APPLICATION_JSON)
                                                                     .accept(MediaType.APPLICATION_JSON));
    response.andExpect(status().isNotFound());
    List<Long> emailIds = List.of(123L, 456L);
    when(emailBoxService.restore(emailIds, SIMPLE_USER, MailFolder.JUNK)).thenReturn(new RestoreOutcome(1, List.of()));
    response = mockMvc.perform(post(EMAIL_BOX_PATH + "/junk/restore").with(testSimpleUser())
                                                                     .content(asJsonString(emailIds))
                                                                     .contentType(MediaType.APPLICATION_JSON)
                                                                     .accept(MediaType.APPLICATION_JSON));
    response.andExpect(status().isOk())
            .andExpect(jsonPath("$.failedJunkRestores").value(1))
            .andExpect(jsonPath("$.restoredToSent").isEmpty());
    verify(emailBoxService).restore(emailIds, SIMPLE_USER, MailFolder.JUNK);
    verify(emailBoxService, never()).restore(anyList(), anyString(), eq(MailFolder.TRASH));
    verify(emailBoxService, never()).purgeEmail(anyList(), anyString());
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
    email.setTo(List.of(new EmailRecipient()));
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
    verify(emailBoxService).sendDraft(sent.capture(), anyString(), isNull(), isNull());
    org.junit.jupiter.api.Assertions.assertEquals("draft-1", sent.getValue().getDraftLocalId());
  }

  @Test
  void sendDraftAnswersNotFoundForADraftThatIsGone() throws Exception {
    Email draft = new Email();
    draft.setTo(List.of(mock(EmailRecipient.class)));
    doThrow(new ObjectNotFoundException("emailConnector.drafts.send.gone")).when(emailBoxService)
                                                                          .sendDraft(any(Email.class), anyString(), isNull(), isNull());
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

  @Test
  void searchCachedEmails() throws Exception {
    // The unified search bar's read. Its novel behaviour is the refusal: every other
    // caught IllegalAccessException in this controller becomes a 403, this one becomes
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

    // No mailbox connected: an empty section, not a 403.
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
    response.andExpect(status().isForbidden());
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

    when(emailBoxService.linkEmailsToCategory(anyList(), anyLong(), anyString(), anyString())).thenReturn(2);
    response = mockMvc.perform(post(EMAIL_BOX_PATH + "/categories/11").with(testSimpleUser())
                                                                     .content(asJsonString(List.of(123L, 456L)))
                                                                     .contentType(MediaType.APPLICATION_JSON)
                                                                     .accept(MediaType.APPLICATION_JSON));
    response.andExpect(status().isOk()).andExpect(jsonPath("$.linked").value(2));
    // No folder: the UIDs are the inbox's, as they always were.
    verify(emailBoxService).linkEmailsToCategory(List.of(123L, 456L), 11L, SIMPLE_USER, "INBOX");

    // Unknown category: the service's message code is surfaced as a 400, not a 500.
    doThrow(new IllegalArgumentException("emailConnector.category.notFound")).when(emailBoxService)
                                                                             .linkEmailsToCategory(anyList(),
                                                                                                   anyLong(),
                                                                                                   anyString(),
                                                                                                   anyString());
    response = mockMvc.perform(post(EMAIL_BOX_PATH + "/categories/11").with(testSimpleUser())
                                                                     .content(asJsonString(List.of(123L)))
                                                                     .contentType(MediaType.APPLICATION_JSON)
                                                                     .accept(MediaType.APPLICATION_JSON));
    response.andExpect(status().isBadRequest());

    doThrow(IllegalAccessException.class).when(emailBoxService)
                                         .linkEmailsToCategory(anyList(), anyLong(), anyString(), anyString());
    response = mockMvc.perform(post(EMAIL_BOX_PATH + "/categories/11").with(testSimpleUser())
                                                                     .content(asJsonString(List.of(123L)))
                                                                     .contentType(MediaType.APPLICATION_JSON)
                                                                     .accept(MediaType.APPLICATION_JSON));
    response.andExpect(status().isForbidden());
  }

  /**
   * EXO-90421 -- a mail dragged onto a category from another folder than the inbox: its
   * UIDs reach the service with the folder they are numbered in, and a folder the
   * service refuses is a 400 carrying its message code.
   */
  @Test
  void linkEmailsToCategoryInAFolder() throws Exception {
    when(emailBoxService.linkEmailsToCategory(anyList(), anyLong(), anyString(), anyString())).thenReturn(1);
    ResultActions response = mockMvc.perform(post(EMAIL_BOX_PATH + "/categories/11?folder=CUSTOM:3").with(testSimpleUser())
                                                                                                    .content(asJsonString(List.of(5L)))
                                                                                                    .contentType(MediaType.APPLICATION_JSON)
                                                                                                    .accept(MediaType.APPLICATION_JSON));
    response.andExpect(status().isOk()).andExpect(jsonPath("$.linked").value(1));
    verify(emailBoxService).linkEmailsToCategory(List.of(5L), 11L, SIMPLE_USER, "CUSTOM:3");

    doThrow(new IllegalArgumentException("emailConnector.folder.notBrowsable")).when(emailBoxService)
                                                                               .linkEmailsToCategory(anyList(),
                                                                                                     anyLong(),
                                                                                                     anyString(),
                                                                                                     eq("ALL_MAIL"));
    response = mockMvc.perform(post(EMAIL_BOX_PATH + "/categories/11?folder=ALL_MAIL").with(testSimpleUser())
                                                                                     .content(asJsonString(List.of(5L)))
                                                                                     .contentType(MediaType.APPLICATION_JSON)
                                                                                     .accept(MediaType.APPLICATION_JSON));
    response.andExpect(status().isBadRequest())
            .andExpect(status().reason("emailConnector.folder.notBrowsable"));
  }

  @Test
  void unlinkEmailsFromCategory() throws Exception {
    ResultActions response = mockMvc.perform(delete(EMAIL_BOX_PATH + "/categories/11").with(testSimpleUser()));
    response.andExpect(status().isBadRequest());

    when(emailBoxService.unlinkEmailsFromCategory(anyList(), anyLong(), anyString(), anyString())).thenReturn(1);
    response = mockMvc.perform(delete(EMAIL_BOX_PATH + "/categories/11").with(testSimpleUser())
                                                                       .content(asJsonString(List.of(123L)))
                                                                       .contentType(MediaType.APPLICATION_JSON)
                                                                       .accept(MediaType.APPLICATION_JSON));
    response.andExpect(status().isOk()).andExpect(jsonPath("$.unlinked").value(1));
    verify(emailBoxService).unlinkEmailsFromCategory(List.of(123L), 11L, SIMPLE_USER, "INBOX");

    doThrow(IllegalAccessException.class).when(emailBoxService)
                                         .unlinkEmailsFromCategory(anyList(), anyLong(), anyString(), anyString());
    response = mockMvc.perform(delete(EMAIL_BOX_PATH + "/categories/11").with(testSimpleUser())
                                                                       .content(asJsonString(List.of(123L)))
                                                                       .contentType(MediaType.APPLICATION_JSON)
                                                                       .accept(MediaType.APPLICATION_JSON));
    response.andExpect(status().isForbidden());
  }

  /**
   * EXO-90421 -- a category taken off a mail of another folder than the inbox: its UIDs
   * reach the service with that folder, and a folder the service refuses is a 400.
   */
  @Test
  void unlinkEmailsFromCategoryInAFolder() throws Exception {
    when(emailBoxService.unlinkEmailsFromCategory(anyList(), anyLong(), anyString(), anyString())).thenReturn(1);
    ResultActions response = mockMvc.perform(delete(EMAIL_BOX_PATH + "/categories/11?folder=ARCHIVE").with(testSimpleUser())
                                                                                                     .content(asJsonString(List.of(5L)))
                                                                                                     .contentType(MediaType.APPLICATION_JSON)
                                                                                                     .accept(MediaType.APPLICATION_JSON));
    response.andExpect(status().isOk()).andExpect(jsonPath("$.unlinked").value(1));
    verify(emailBoxService).unlinkEmailsFromCategory(List.of(5L), 11L, SIMPLE_USER, "ARCHIVE");

    doThrow(new IllegalArgumentException("emailConnector.folder.notBrowsable")).when(emailBoxService)
                                                                               .unlinkEmailsFromCategory(anyList(),
                                                                                                         anyLong(),
                                                                                                         anyString(),
                                                                                                         eq("ALL_MAIL"));
    response = mockMvc.perform(delete(EMAIL_BOX_PATH + "/categories/11?folder=ALL_MAIL").with(testSimpleUser())
                                                                                       .content(asJsonString(List.of(5L)))
                                                                                       .contentType(MediaType.APPLICATION_JSON)
                                                                                       .accept(MediaType.APPLICATION_JSON));
    response.andExpect(status().isBadRequest())
            .andExpect(status().reason("emailConnector.folder.notBrowsable"));
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
   * them, and answers the same 403 the conversation read itself answers.
   *
   * @throws Exception when the mocked plumbing misbehaves
   */
  @Test
  void anUnreadableMailboxAnswersForbiddenOnBothSummaryEndpoints() throws Exception {
    when(emailBoxService.getThreadAiSummary(anyString(), anyString())).thenThrow(new IllegalAccessException());
    doThrow(new IllegalAccessException()).when(emailBoxService).requestThreadAiSummary(anyString(), anyString());

    mockMvc.perform(get(EMAIL_BOX_PATH + "/thread/thread-1/ai-summary").with(testSimpleUser()))
           .andExpect(status().isForbidden());
    mockMvc.perform(post(EMAIL_BOX_PATH + "/thread/thread-1/ai-summary/refresh").with(testSimpleUser()))
           .andExpect(status().isForbidden());
  }

  private RequestPostProcessor testSimpleUser() {
    return user(SIMPLE_USER).password(TEST_PASSWORD).authorities(new SimpleGrantedAuthority("users"));
  }

  @SneakyThrows
  private String asJsonString(final Object obj) {
    return OBJECT_MAPPER.writeValueAsString(obj);
  }

  /**
   * The folder list is one GET, refresh included: the walk the user asks for reaches
   * the service as the flag, and the default is no walk.
   */
  @Test
  void getFolders() throws Exception {
    mockMvc.perform(get(EMAIL_BOX_PATH + "/folders").with(testSimpleUser())).andExpect(status().isOk());
    verify(emailBoxService).getFolders(SIMPLE_USER, false);
    mockMvc.perform(get(EMAIL_BOX_PATH + "/folders").param("refresh", "true").with(testSimpleUser())).andExpect(status().isOk());
    verify(emailBoxService).getFolders(SIMPLE_USER, true);
  }

  /**
   * The opt-in is a PATCH on the folder's id, and the cap's refusal reaches the client
   * as a 400 carrying the message code the screen shows.
   */
  @Test
  void setFolderSync() throws Exception {
    mockMvc.perform(patch(EMAIL_BOX_PATH + "/folders/5").param("sync", "true").with(testSimpleUser()))
           .andExpect(status().isOk());
    verify(emailBoxService).setCustomFolderSync(SIMPLE_USER, 5L, true);
    mockMvc.perform(patch(EMAIL_BOX_PATH + "/folders/5").param("sync", "false").with(testSimpleUser()))
           .andExpect(status().isOk());
    verify(emailBoxService).setCustomFolderSync(SIMPLE_USER, 5L, false);
    doThrow(new IllegalArgumentException("emailConnector.folder.tooMany")).when(emailBoxService)
                                                                          .setCustomFolderSync(SIMPLE_USER, 6L, true);
    mockMvc.perform(patch(EMAIL_BOX_PATH + "/folders/6").param("sync", "true").with(testSimpleUser()))
           .andExpect(status().isBadRequest())
           .andExpect(status().reason("emailConnector.folder.tooMany"));
    mockMvc.perform(patch(EMAIL_BOX_PATH + "/folders/5").with(testSimpleUser())).andExpect(status().isBadRequest());
  }

  /**
   * The on-demand refresh of one folder is its own POST, and a folder that is not
   * mirrored is a 400 rather than a silent no-op.
   */
  @Test
  void synchronizeFolder() throws Exception {
    mockMvc.perform(post(EMAIL_BOX_PATH + "/folders/5/synchronization").with(testSimpleUser())).andExpect(status().isOk());
    verify(emailBoxService).synchronizeCustomFolder(SIMPLE_USER, 5L);
    doThrow(new IllegalArgumentException("emailConnector.folder.notMirrored")).when(emailBoxService)
                                                                              .synchronizeCustomFolder(SIMPLE_USER, 7L);
    mockMvc.perform(post(EMAIL_BOX_PATH + "/folders/7/synchronization").with(testSimpleUser()))
           .andExpect(status().isBadRequest());
  }

  /**
   * "Move to..." is folder-addressed exactly as the delete is, names its target, and
   * reaches its own service method -- never the archive, never the delete.
   */
  @Test
  void moveEmails() throws Exception {
    mockMvc.perform(post(EMAIL_BOX_PATH + "/move").param("target", "CUSTOM:5").with(testSimpleUser()))
           .andExpect(status().isBadRequest());
    mockMvc.perform(post(EMAIL_BOX_PATH + "/move").param("target", "CUSTOM:5")
                                                  .with(testSimpleUser())
                                                  .content(asJsonString(new ArrayList<Long>()))
                                                  .contentType(MediaType.APPLICATION_JSON)
                                                  .accept(MediaType.APPLICATION_JSON))
           .andExpect(status().isNotFound());
    List<Long> emailIds = List.of(123L, 456L);
    mockMvc.perform(post(EMAIL_BOX_PATH + "/move").param("target", "CUSTOM:5")
                                                  .with(testSimpleUser())
                                                  .content(asJsonString(emailIds))
                                                  .contentType(MediaType.APPLICATION_JSON)
                                                  .accept(MediaType.APPLICATION_JSON))
           .andExpect(status().isOk());
    verify(emailBoxService).moveToFolder(emailIds, SIMPLE_USER, MailFolder.INBOX, "CUSTOM:5");
    mockMvc.perform(post(EMAIL_BOX_PATH + "/move").param("target", "CUSTOM:5")
                                                  .param("folder", MailFolder.SENT)
                                                  .with(testSimpleUser())
                                                  .content(asJsonString(emailIds))
                                                  .contentType(MediaType.APPLICATION_JSON)
                                                  .accept(MediaType.APPLICATION_JSON))
           .andExpect(status().isOk());
    verify(emailBoxService).moveToFolder(emailIds, SIMPLE_USER, MailFolder.SENT, "CUSTOM:5");
    verify(emailBoxService, never()).archiveEmail(anyList(), anyString(), anyString());
    verify(emailBoxService, never()).deleteEmail(anyList(), anyString(), anyString());
    doThrow(new IllegalArgumentException("emailConnector.folder.unknown")).when(emailBoxService)
                                                                          .moveToFolder(emailIds, SIMPLE_USER, MailFolder.INBOX, "CUSTOM:99");
    mockMvc.perform(post(EMAIL_BOX_PATH + "/move").param("target", "CUSTOM:99")
                                                  .with(testSimpleUser())
                                                  .content(asJsonString(emailIds))
                                                  .contentType(MediaType.APPLICATION_JSON)
                                                  .accept(MediaType.APPLICATION_JSON))
           .andExpect(status().isBadRequest())
           .andExpect(status().reason("emailConnector.folder.unknown"));
  }

  /**
   * The undo of a move is its own endpoint, addressed by Message-ID, and reaches its
   * own service method with the two folders the way the client names them: the one the
   * messages are in now, and the one they go back to (the inbox when unsaid).
   */
  @Test
  void undoMoveEmails() throws Exception {
    mockMvc.perform(post(EMAIL_BOX_PATH + "/move/undo").param("folder", "CUSTOM:5").with(testSimpleUser()))
           .andExpect(status().isBadRequest());
    mockMvc.perform(post(EMAIL_BOX_PATH + "/move/undo").param("folder", "CUSTOM:5")
                                                       .with(testSimpleUser())
                                                       .content(asJsonString(new ArrayList<String>()))
                                                       .contentType(MediaType.APPLICATION_JSON)
                                                       .accept(MediaType.APPLICATION_JSON))
           .andExpect(status().isNotFound());
    List<String> mailHeaderIds = List.of("<a@host>", "<b@host>");
    mockMvc.perform(post(EMAIL_BOX_PATH + "/move/undo").param("folder", "CUSTOM:5")
                                                       .with(testSimpleUser())
                                                       .content(asJsonString(mailHeaderIds))
                                                       .contentType(MediaType.APPLICATION_JSON)
                                                       .accept(MediaType.APPLICATION_JSON))
           .andExpect(status().isOk());
    verify(emailBoxService).undoMove(mailHeaderIds, SIMPLE_USER, "CUSTOM:5", MailFolder.INBOX);
    mockMvc.perform(post(EMAIL_BOX_PATH + "/move/undo").param("folder", "CUSTOM:5")
                                                       .param("target", MailFolder.SENT)
                                                       .with(testSimpleUser())
                                                       .content(asJsonString(mailHeaderIds))
                                                       .contentType(MediaType.APPLICATION_JSON)
                                                       .accept(MediaType.APPLICATION_JSON))
           .andExpect(status().isOk());
    verify(emailBoxService).undoMove(mailHeaderIds, SIMPLE_USER, "CUSTOM:5", MailFolder.SENT);
    verify(emailBoxService, never()).moveToFolder(anyList(), anyString(), anyString(), anyString());
    doThrow(new IllegalArgumentException("emailConnector.folder.notMirrored")).when(emailBoxService)
                                                                              .undoMove(mailHeaderIds, SIMPLE_USER, "CUSTOM:99", MailFolder.INBOX);
    mockMvc.perform(post(EMAIL_BOX_PATH + "/move/undo").param("folder", "CUSTOM:99")
                                                       .with(testSimpleUser())
                                                       .content(asJsonString(mailHeaderIds))
                                                       .contentType(MediaType.APPLICATION_JSON)
                                                       .accept(MediaType.APPLICATION_JSON))
           .andExpect(status().isBadRequest())
           .andExpect(status().reason("emailConnector.folder.notMirrored"));
    doThrow(new IllegalAccessException("not yours")).when(emailBoxService)
                                                    .undoMove(mailHeaderIds, SIMPLE_USER, "CUSTOM:7", MailFolder.INBOX);
    mockMvc.perform(post(EMAIL_BOX_PATH + "/move/undo").param("folder", "CUSTOM:7")
                                                       .with(testSimpleUser())
                                                       .content(asJsonString(mailHeaderIds))
                                                       .contentType(MediaType.APPLICATION_JSON)
                                                       .accept(MediaType.APPLICATION_JSON))
           .andExpect(status().isForbidden());
  }

  // ---------------------------------------------------------------------------------
  // Scheduled send (EXO-90434)
  // ---------------------------------------------------------------------------------

  /**
   * Scheduling answers the scheduled mail, for the caller only: the owner is the
   * request's remote user, and the draft is the one the path names, whatever the body
   * claims.
   *
   * @throws Exception if the request fails
   */
  @Test
  void schedulingADraftIsTheCallersAndNamedByThePath() throws Exception {
    ScheduledEmail scheduled = new ScheduledEmail();
    scheduled.setDraftLocalId("draft-1");
    scheduled.setStatus(ScheduledSendStatus.SCHEDULED);
    when(emailScheduledSendService.schedule(any(Email.class), eq(1_900_000_000_000L), eq("Europe/Paris"), eq(SIMPLE_USER)))
                                                                                                                            .thenReturn(scheduled);
    Email draft = new Email();
    draft.setDraftLocalId("another-draft");
    mockMvc.perform(post(EMAIL_BOX_PATH + "/drafts/draft-1/schedule").with(testSimpleUser())
                                                                     .contentType(MediaType.APPLICATION_JSON)
                                                                     .content(asJsonString(new ScheduleRequest(draft,
                                                                                                               1_900_000_000_000L,
                                                                                                               "Europe/Paris"))))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.draftLocalId").value("draft-1"))
           .andExpect(jsonPath("$.status").value("SCHEDULED"));
    ArgumentCaptor<Email> scheduledDraft = ArgumentCaptor.forClass(Email.class);
    verify(emailScheduledSendService).schedule(scheduledDraft.capture(), eq(1_900_000_000_000L), eq("Europe/Paris"), eq(SIMPLE_USER));
    assertEquals("draft-1", scheduledDraft.getValue().getDraftLocalId(), "the path names the draft");
  }

  /**
   * Each refusal of a scheduling has its status: 400 with the code for a bad request,
   * 403 for a mailbox the caller may not use, 404 for a draft they do not have, 409 for
   * one already scheduled or being sent, 500 for a server copy that would not go.
   *
   * @throws Exception if a request fails
   */
  @Test
  void aRefusedSchedulingAnswersItsStatus() throws Exception {
    String body = asJsonString(new ScheduleRequest(new Email(), 1_900_000_000_000L, "UTC"));
    Object[][] cases = { { new IllegalArgumentException("emailConnector.scheduled.date.tooSoon"), 400 },
        { new IllegalAccessException("no"), 403 }, { new ObjectNotFoundException("gone"), 404 },
        { new ScheduledSendConflictException(ScheduledSendConflictException.LOCKED), 409 },
        { new IllegalStateException("emailConnector.scheduled.serverCopyRemains"), 500 } };
    for (Object[] testCase : cases) {
      doThrow((Exception) testCase[0]).when(emailScheduledSendService).schedule(any(Email.class), anyLong(), anyString(), anyString());
      mockMvc.perform(post(EMAIL_BOX_PATH + "/drafts/draft-1/schedule").with(testSimpleUser())
                                                                       .contentType(MediaType.APPLICATION_JSON)
                                                                       .content(body))
             .andExpect(status().is((int) testCase[1]));
    }
    mockMvc.perform(post(EMAIL_BOX_PATH + "/drafts/draft-1/schedule").with(testSimpleUser())
                                                                     .contentType(MediaType.APPLICATION_JSON)
                                                                     .content(asJsonString(new ScheduleRequest(new Email(), null, "UTC"))))
           .andExpect(status().isBadRequest());
  }

  /**
   * EXO-90584 -- a scheduling, or an edit of a scheduled mail, in the shared mailbox
   * owner's name that her consent does not cover answers 403 WITH the code naming the
   * shape, so the composer can say why and fall back to the caller's own name; a name
   * that cannot be used answers 400 with its code; an edit whose mailbox is no longer
   * shared answers 410.
   *
   * @throws Exception if a request fails
   */
  @Test
  void aSchedulingInANameTheConsentDoesNotCoverAnswersItsCode() throws Exception {
    String body = asJsonString(new ScheduleRequest(new Email(), 1_900_000_000_000L, "UTC"));
    doThrow(new SendModeMissingException(SendMode.AS)).when(emailScheduledSendService)
                                                      .schedule(any(Email.class), anyLong(), anyString(), anyString());
    mockMvc.perform(post(EMAIL_BOX_PATH + "/drafts/draft-1/schedule").with(testSimpleUser())
                                                                     .contentType(MediaType.APPLICATION_JSON)
                                                                     .content(body))
           .andExpect(status().isForbidden())
           .andExpect(status().reason("emailConnector.sendMode.missing.AS"));
    doThrow(new SendModeUnavailableException(SendModeUnavailableException.REFUSED_BY_SERVER)).when(emailScheduledSendService)
                                                                                             .schedule(any(Email.class), anyLong(), anyString(), anyString());
    mockMvc.perform(post(EMAIL_BOX_PATH + "/drafts/draft-1/schedule").with(testSimpleUser())
                                                                     .contentType(MediaType.APPLICATION_JSON)
                                                                     .content(body))
           .andExpect(status().isBadRequest())
           .andExpect(status().reason(SendModeUnavailableException.REFUSED_BY_SERVER));

    String edit = asJsonString(new ScheduleRequest(new Email(), null, null, List.of()));
    doThrow(new SendModeMissingException(SendMode.ON_BEHALF)).when(emailScheduledSendService)
                                                             .updateContent(anyString(), any(Email.class), any(), any(), any(), anyString());
    mockMvc.perform(put(EMAIL_BOX_PATH + "/scheduled/draft-1/content").with(testSimpleUser())
                                                                      .contentType(MediaType.APPLICATION_JSON)
                                                                      .content(edit))
           .andExpect(status().isForbidden())
           .andExpect(status().reason("emailConnector.sendMode.missing.ON_BEHALF"));
    doThrow(new DelegationRevokedException(DelegationRevokedException.REVOKED)).when(emailScheduledSendService)
                                                                               .updateContent(anyString(), any(Email.class), any(), any(), any(), anyString());
    mockMvc.perform(put(EMAIL_BOX_PATH + "/scheduled/draft-1/content").with(testSimpleUser())
                                                                      .contentType(MediaType.APPLICATION_JSON)
                                                                      .content(edit))
           .andExpect(status().isGone());
  }

  /**
   * An update of a scheduled mail's content in place reaches the service as the caller,
   * with the draft, the files to take off and the optional date; each refusal answers
   * its status, and a request without a draft is a 400 (EXO-90434).
   *
   * @throws Exception if a request fails
   */
  @Test
  void anUpdateOfAScheduledMailsContentAnswersItsStatusForTheCallerOnly() throws Exception {
    Email draft = new Email();
    draft.setSubject("New subject");
    String body = asJsonString(new ScheduleRequest(draft, null, null, List.of(7L)));
    mockMvc.perform(put(EMAIL_BOX_PATH + "/scheduled/draft-1/content").with(testSimpleUser())
                                                                      .contentType(MediaType.APPLICATION_JSON)
                                                                      .content(body))
           .andExpect(status().isOk());
    ArgumentCaptor<Email> sent = ArgumentCaptor.forClass(Email.class);
    verify(emailScheduledSendService).updateContent(eq("draft-1"), sent.capture(), eq(List.of(7L)), isNull(), isNull(), eq(SIMPLE_USER));
    assertEquals("New subject", sent.getValue().getSubject());

    Object[][] cases = { { new IllegalArgumentException("emailConnector.scheduled.recipientsMandatory"), 400 },
        { new IllegalAccessException("no"), 403 }, { new ObjectNotFoundException("gone"), 404 },
        { new ScheduledSendConflictException(ScheduledSendConflictException.SENDING), 409 } };
    for (Object[] testCase : cases) {
      doThrow((Exception) testCase[0]).when(emailScheduledSendService)
                                      .updateContent(anyString(), any(Email.class), any(), any(), any(), anyString());
      mockMvc.perform(put(EMAIL_BOX_PATH + "/scheduled/draft-1/content").with(testSimpleUser())
                                                                        .contentType(MediaType.APPLICATION_JSON)
                                                                        .content(body))
             .andExpect(status().is((int) testCase[1]));
    }
    mockMvc.perform(put(EMAIL_BOX_PATH + "/scheduled/draft-1/content").with(testSimpleUser())
                                                                      .contentType(MediaType.APPLICATION_JSON)
                                                                      .content(asJsonString(new ScheduleRequest(null, null, null))))
           .andExpect(status().isBadRequest());
  }

  /**
   * Reschedule, cancel and send now answer 200/204, 404 for a mail the caller has not
   * scheduled and 409 for one being sent -- each for the caller only.
   *
   * @throws Exception if a request fails
   */
  @Test
  void theScheduledActionsAnswerTheirStatusForTheCallerOnly() throws Exception {
    String body = asJsonString(new ScheduleRequest(null, 1_900_000_000_000L, "UTC"));
    mockMvc.perform(put(EMAIL_BOX_PATH + "/scheduled/draft-1").with(testSimpleUser())
                                                              .contentType(MediaType.APPLICATION_JSON)
                                                              .content(body))
           .andExpect(status().isOk());
    verify(emailScheduledSendService).reschedule("draft-1", 1_900_000_000_000L, "UTC", SIMPLE_USER);
    mockMvc.perform(delete(EMAIL_BOX_PATH + "/scheduled/draft-1").with(testSimpleUser())).andExpect(status().isNoContent());
    verify(emailScheduledSendService).cancel("draft-1", SIMPLE_USER);
    mockMvc.perform(post(EMAIL_BOX_PATH + "/scheduled/draft-1/send").with(testSimpleUser())).andExpect(status().isOk());
    verify(emailScheduledSendService).sendNow("draft-1", SIMPLE_USER);

    doThrow(new ObjectNotFoundException("gone")).when(emailScheduledSendService).cancel("gone", SIMPLE_USER);
    mockMvc.perform(delete(EMAIL_BOX_PATH + "/scheduled/gone").with(testSimpleUser())).andExpect(status().isNotFound());
    doThrow(new ScheduledSendConflictException(ScheduledSendConflictException.SENDING)).when(emailScheduledSendService)
                                                                                        .sendNow("busy", SIMPLE_USER);
    mockMvc.perform(post(EMAIL_BOX_PATH + "/scheduled/busy/send").with(testSimpleUser())).andExpect(status().isConflict());
    doThrow(new ScheduledSendConflictException(ScheduledSendConflictException.SENDING)).when(emailScheduledSendService)
                                                                                        .cancel("busy", SIMPLE_USER);
    mockMvc.perform(delete(EMAIL_BOX_PATH + "/scheduled/busy").with(testSimpleUser())).andExpect(status().isConflict());
    doThrow(new IllegalArgumentException("emailConnector.scheduled.date.tooFar")).when(emailScheduledSendService)
                                                                                 .reschedule("far", 1_900_000_000_000L, "UTC", SIMPLE_USER);
    mockMvc.perform(put(EMAIL_BOX_PATH + "/scheduled/far").with(testSimpleUser())
                                                          .contentType(MediaType.APPLICATION_JSON)
                                                          .content(body))
           .andExpect(status().isBadRequest());
  }

  /**
   * The list is the caller's, its page size bounded; the count is the caller's.
   *
   * @throws Exception if a request fails
   */
  @Test
  void theScheduledListAndCountAreTheCallers() throws Exception {
    mockMvc.perform(get(EMAIL_BOX_PATH + "/scheduled?offset=0&limit=500").with(testSimpleUser())).andExpect(status().isOk());
    verify(emailScheduledSendService).getScheduledEmails(SIMPLE_USER, 0, 100);
    when(emailScheduledSendService.countScheduledEmails(SIMPLE_USER)).thenReturn(3L);
    mockMvc.perform(get(EMAIL_BOX_PATH + "/scheduled/count").with(testSimpleUser()))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$").value(3));
  }

  /**
   * EXO-90595 -- the mailbox a draft belongs to reaches the composer as statuses: a first
   * save naming a share that is not the caller's answers 400 with its code (never the
   * "draft gone" 404), a schedule of a draft whose mailbox is no longer shared 410, a
   * send naming another mailbox than the draft's 400 with its code; and the draft's
   * mailbox reads 200 with its owner, or 204 for the caller's own.
   *
   * @throws Exception if a request fails
   */
  @Test
  void aDraftsMailboxRefusalsAnswerTheirStatuses() throws Exception {
    doThrow(new IllegalArgumentException(EmailBoxService.MAILBOX_NOT_FOUND_CODE)).when(emailBoxService)
                                                                                 .saveDraft(any(Email.class), anyString(), anyBoolean());
    mockMvc.perform(post(EMAIL_BOX_PATH + "/drafts").with(testSimpleUser())
                                                    .contentType(MediaType.APPLICATION_JSON)
                                                    .content(asJsonString(new Email())))
           .andExpect(status().isBadRequest())
           .andExpect(status().reason(EmailBoxService.MAILBOX_NOT_FOUND_CODE));
    when(emailBoxService.getDraftMailbox("draft-1", SIMPLE_USER)).thenReturn(new DraftMailbox(100L, "Anne", "anne@example.org", false));
    mockMvc.perform(get(EMAIL_BOX_PATH + "/drafts/draft-1/mailbox").with(testSimpleUser()))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.delegationId").value(100))
           .andExpect(jsonPath("$.shared").value(false));
    mockMvc.perform(get(EMAIL_BOX_PATH + "/drafts/draft-2/mailbox").with(testSimpleUser())).andExpect(status().isNoContent());
    doThrow(new DelegationRevokedException(DelegationRevokedException.REVOKED)).when(emailScheduledSendService)
                                                                               .schedule(any(Email.class), anyLong(), anyString(), anyString());
    mockMvc.perform(post(EMAIL_BOX_PATH + "/drafts/draft-1/schedule").with(testSimpleUser())
                                                                     .contentType(MediaType.APPLICATION_JSON)
                                                                     .content(asJsonString(new ScheduleRequest(new Email(), 1L, "UTC"))))
           .andExpect(status().isGone());
    Email draft = new Email();
    draft.setTo(List.of(new EmailRecipient("Bob", "bob@example.org", null, false)));
    doThrow(new IllegalArgumentException(EmailBoxService.MAILBOX_MISMATCH_CODE)).when(emailBoxService)
                                                                                .sendDraft(any(Email.class), anyString(), eq(101L), isNull());
    mockMvc.perform(post(EMAIL_BOX_PATH + "/drafts/draft-1/send?delegationId=101").with(testSimpleUser())
                                                                                   .contentType(MediaType.APPLICATION_JSON)
                                                                                   .content(asJsonString(draft)))
           .andExpect(status().isBadRequest());
  }

  /**
   * A scheduled draft's lock reaches the composer as 409 on its save, and a draft being
   * sent refuses its discard with 409.
   *
   * @throws Exception if a request fails
   */
  @Test
  void aScheduledDraftsLockAnswersConflict() throws Exception {
    doThrow(new ScheduledSendConflictException(ScheduledSendConflictException.LOCKED)).when(emailBoxService)
                                                                                       .saveDraft(any(Email.class), anyString(), anyBoolean());
    mockMvc.perform(post(EMAIL_BOX_PATH + "/drafts").with(testSimpleUser())
                                                    .contentType(MediaType.APPLICATION_JSON)
                                                    .content(asJsonString(new Email())))
           .andExpect(status().isConflict());
    doThrow(new ScheduledSendConflictException(ScheduledSendConflictException.SENDING)).when(emailBoxService)
                                                                                        .deleteDraft("draft-1", SIMPLE_USER);
    mockMvc.perform(delete(EMAIL_BOX_PATH + "/drafts/draft-1").with(testSimpleUser())).andExpect(status().isConflict());
  }

  /**
   * The answer to a read-receipt request reaches the service for the authenticated
   * caller -- never a user named in the request -- and each refusal gets its status:
   * 204 answered, 404 unknown or not the caller's, 400 not requested / not allowed /
   * no action, 409 already answered, 403 no usable connector, 500 not sent.
   *
   * @throws Exception when the request cannot be performed
   */
  @Test
  void answeringAReadReceiptRequestMapsEveryOutcome() throws Exception {
    String path = EMAIL_BOX_PATH + "/12/read-receipt";
    mockMvc.perform(post(path).with(testSimpleUser())
                              .content("{\"action\":\"SEND\"}")
                              .contentType(MediaType.APPLICATION_JSON))
           .andExpect(status().isNoContent());
    verify(readReceiptService).respond(12L, SIMPLE_USER, ReadReceiptAction.SEND, false);
    mockMvc.perform(post(path).with(testSimpleUser())
                              .content("{\"action\":\"SEND\",\"automatic\":true}")
                              .contentType(MediaType.APPLICATION_JSON))
           .andExpect(status().isNoContent());
    verify(readReceiptService).respond(12L, SIMPLE_USER, ReadReceiptAction.SEND, true);

    doThrow(new ObjectNotFoundException("gone")).when(readReceiptService).respond(12L, SIMPLE_USER, ReadReceiptAction.IGNORE, false);
    expectAnswer(path, "IGNORE").andExpect(status().isNotFound());

    doThrow(new IllegalArgumentException(ReadReceiptService.NOT_REQUESTED)).when(readReceiptService)
                                                                           .respond(13L, SIMPLE_USER, ReadReceiptAction.SEND, false);
    expectAnswer(EMAIL_BOX_PATH + "/13/read-receipt", "SEND").andExpect(status().isBadRequest());

    doThrow(new ReadReceiptConflictException(ReadReceiptConflictException.ALREADY_HANDLED)).when(readReceiptService)
                                                                                         .respond(14L, SIMPLE_USER, ReadReceiptAction.SEND, false);
    expectAnswer(EMAIL_BOX_PATH + "/14/read-receipt", "SEND").andExpect(status().isConflict());

    doThrow(IllegalAccessException.class).when(readReceiptService).respond(15L, SIMPLE_USER, ReadReceiptAction.SEND, false);
    expectAnswer(EMAIL_BOX_PATH + "/15/read-receipt", "SEND").andExpect(status().isForbidden());

    doThrow(new IllegalStateException(ReadReceiptService.SEND_FAILED)).when(readReceiptService)
                                                                     .respond(16L, SIMPLE_USER, ReadReceiptAction.SEND, false);
    expectAnswer(EMAIL_BOX_PATH + "/16/read-receipt", "SEND").andExpect(status().isInternalServerError());

    doThrow(new IllegalArgumentException(ReadReceiptService.INVALID_ACTION)).when(readReceiptService).respond(17L, SIMPLE_USER, null, false);
    mockMvc.perform(post(EMAIL_BOX_PATH + "/17/read-receipt").with(testSimpleUser())
                                                             .content("{}")
                                                             .contentType(MediaType.APPLICATION_JSON))
           .andExpect(status().isBadRequest());
  }

  /**
   * Every read that feeds the reader is decorated with the prompt and the answer for the
   * caller; the JSON carries readReceiptRequested, readReceiptPrompt and
   * readReceiptAnswer, and never the one cached copy's readReceiptState; and a send's
   * payload brings readReceiptRequested in while a readReceiptTo, a readReceiptPrompt or
   * a readReceiptAnswer it may carry are ignored.
   *
   * @throws Exception when the request cannot be performed
   */
  @Test
  void theReaderIsToldWhatToDoAndThePayloadCannotAddressAReceipt() throws Exception {
    Email email = new Email();
    email.setId(12L);
    email.setReadReceiptRequested(true);
    email.setReadReceiptState(ReadReceiptState.SENT);
    when(emailBoxService.getOwnedEmailById(12L, SIMPLE_USER)).thenReturn(email);
    org.mockito.Mockito.doAnswer(invocation -> {
      ((Email) invocation.getArgument(0)).setReadReceiptPrompt(ReadReceiptPrompt.ASK);
      ((Email) invocation.getArgument(0)).setReadReceiptAnswer(ReadReceiptState.IGNORED);
      return null;
    }).when(readReceiptService).decorate(any(Email.class), eq(SIMPLE_USER));
    mockMvc.perform(get(EMAIL_BOX_PATH + "/favorites/12").with(testSimpleUser()))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.readReceiptRequested").value(true))
           .andExpect(jsonPath("$.readReceiptPrompt").value("ASK"))
           .andExpect(jsonPath("$.readReceiptAnswer").value("IGNORED"))
           .andExpect(jsonPath("$.readReceiptState").doesNotExist());

    when(emailBoxService.getEmailByMailRemoteIdAndUserId(34L, SIMPLE_USER, "INBOX", true, true, true, true)).thenReturn(email);
    mockMvc.perform(get(EMAIL_BOX_PATH + "/34").with(testSimpleUser())).andExpect(status().isOk());
    verify(readReceiptService, org.mockito.Mockito.times(2)).decorate(any(Email.class), eq(SIMPLE_USER));
    List<Email> thread = List.of(email);
    when(emailBoxService.getThread("t", SIMPLE_USER, null)).thenReturn(thread);
    mockMvc.perform(get(EMAIL_BOX_PATH + "/thread/t").with(testSimpleUser())).andExpect(status().isOk());
    verify(readReceiptService).decorate(thread, SIMPLE_USER);
    when(emailBoxService.completeThread("t", SIMPLE_USER, null)).thenReturn(thread);
    mockMvc.perform(get(EMAIL_BOX_PATH + "/thread/t/complete").with(testSimpleUser())).andExpect(status().isOk());
    verify(readReceiptService, org.mockito.Mockito.times(2)).decorate(thread, SIMPLE_USER);

    mockMvc.perform(post(EMAIL_BOX_PATH + "/send").with(testSimpleUser())
                                                  .content("{\"to\":[{\"address\":\"bob@example.org\"}],\"readReceiptRequested\":true,"
                                                      + "\"readReceiptTo\":\"eve@tracker.example\",\"readReceiptPrompt\":\"AUTO\","
                                                      + "\"readReceiptAnswer\":\"SENT\"}")
                                                  .contentType(MediaType.APPLICATION_JSON))
           .andExpect(status().isOk());
    ArgumentCaptor<Email> sent = ArgumentCaptor.forClass(Email.class);
    verify(emailBoxService).sendEmail(sent.capture(), eq(SIMPLE_USER), isNull(), isNull());
    org.junit.jupiter.api.Assertions.assertTrue(sent.getValue().isReadReceiptRequested());
    org.junit.jupiter.api.Assertions.assertNull(sent.getValue().getReadReceiptTo(), "read-only: never from a payload");
    org.junit.jupiter.api.Assertions.assertNull(sent.getValue().getReadReceiptPrompt());
    org.junit.jupiter.api.Assertions.assertNull(sent.getValue().getReadReceiptAnswer(),
                                                "read-only: an answer is never claimed by a payload");
  }

  /**
   * Posts an answer.
   *
   * @param path the path
   * @param action the action
   * @return the result
   * @throws Exception when the request cannot be performed
   */
  private ResultActions expectAnswer(String path, String action) throws Exception {
    return mockMvc.perform(post(path).with(testSimpleUser())
                                     .content("{\"action\":\"" + action + "\"}")
                                     .contentType(MediaType.APPLICATION_JSON));
  }
}
