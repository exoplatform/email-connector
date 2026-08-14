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
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.ByteArrayInputStream;
import java.util.Date;
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
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import com.fasterxml.jackson.core.json.JsonReadFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import org.exoplatform.commons.file.model.FileItem;
import org.exoplatform.emailConnector.carddav.CardDavException;
import org.exoplatform.emailConnector.carddav.CardDavPublishQueuedException;
import org.exoplatform.emailConnector.model.ContactOrigin;
import org.exoplatform.emailConnector.model.ContactPublishQueue;
import org.exoplatform.emailConnector.model.ContactPublishQueueEntry;
import org.exoplatform.emailConnector.model.EmailContact;
import org.exoplatform.emailConnector.model.EmailContactSource;
import org.exoplatform.emailConnector.model.EmailContactSuggestion;
import org.exoplatform.emailConnector.model.ContactImportState;
import org.exoplatform.emailConnector.model.SyncStatus;
import org.exoplatform.emailConnector.service.EmailContactCardDavSyncService;
import org.exoplatform.emailConnector.service.EmailContactService;
import org.exoplatform.emailConnector.service.EmailContactVCardService;

import io.meeds.spring.web.security.PortalAuthenticationManager;
import io.meeds.spring.web.security.WebSecurityConfiguration;
import jakarta.servlet.Filter;
import lombok.SneakyThrows;

@SpringBootTest(classes = { EmailContactRest.class, PortalAuthenticationManager.class })
@ContextConfiguration(classes = { WebSecurityConfiguration.class })
@AutoConfigureWebMvc
@AutoConfigureMockMvc(addFilters = false)
@ExtendWith(MockitoExtension.class)
public class EmailContactRestTest {

  private static final String   CONTACTS_PATH = "/contacts"; // NOSONAR

  private static final String   SIMPLE_USER   = "simple";

  static final ObjectMapper     OBJECT_MAPPER;

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
  private EmailContactService   emailContactService;

  @MockBean
  private EmailContactCardDavSyncService emailContactCardDavSyncService;

  @MockBean
  private EmailContactVCardService       emailContactVCardService;

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
  void getContactsAnswersOk() throws Exception {
    mockMvc.perform(get(CONTACTS_PATH).with(testSimpleUser())).andExpect(status().isOk());
  }

  @Test
  void getContactsWithABogusSourceAnswersBadRequest() throws Exception {
    when(emailContactService.getContacts(anyString(), eq(List.of("bogus")), any(), anyBoolean(), anyInt(), anyInt()))
                                                                                              .thenThrow(new IllegalArgumentException(EmailContactService.CONTACT_INVALID_SOURCE));
    mockMvc.perform(get(CONTACTS_PATH + "?source=bogus").with(testSimpleUser())).andExpect(status().isBadRequest());
  }

  @Test
  void severalSourcesReachTheServiceTogether() throws Exception {
    // Repeated parameters, because the chips they come from are multi-select. Bound
    // to a single value, two selected chips reached the service as one -- and the
    // service, given more than it could express, answered with the whole store.
    mockMvc.perform(get(CONTACTS_PATH + "?source=collected&source=manual").with(testSimpleUser())).andExpect(status().isOk());

    verify(emailContactService).getContacts(anyString(), eq(List.of("collected", "manual")), any(), anyBoolean(), anyInt(), anyInt());
  }

  @Test
  void theFavoritesFlagTravelsToTheService() throws Exception {
    // The Favorites chip's query parameter, and its default: absent means false,
    // so every existing caller keeps its unfiltered list.
    mockMvc.perform(get(CONTACTS_PATH + "?favorites=true").with(testSimpleUser())).andExpect(status().isOk());
    verify(emailContactService).getContacts(anyString(), any(), any(), eq(true), anyInt(), anyInt());

    mockMvc.perform(get(CONTACTS_PATH).with(testSimpleUser())).andExpect(status().isOk());
    verify(emailContactService).getContacts(anyString(), any(), any(), eq(false), anyInt(), anyInt());
  }

  @Test
  void suggestAnswersTheRankedList() throws Exception {
    when(emailContactService.suggestRecipients(anyString(), eq("bob"), anyInt()))
                                                                                 .thenReturn(List.of(new EmailContactSuggestion("bob@example.org",
                                                                                                                               "Bob Smith",
                                                                                                                               "/avatar",
                                                                                                                               true,
                                                                                                                               "/bob")));
    mockMvc.perform(get(CONTACTS_PATH + "/suggest?q=bob").with(testSimpleUser()))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$[0].address").value("bob@example.org"))
           .andExpect(jsonPath("$[0].platformUser").value(true));
  }

  @Test
  void suggestWithoutATermIsStillAValidRequest() throws Exception {
    // A blank term is the "field just opened" case, not an error: the service
    // answers the user's top contacts and never touches the directory.
    // The same request also proves the route itself: "/contacts/suggest" and
    // "/contacts/{id}" share a path segment, and the literal has to win or the
    // type-ahead 400s on an unparseable id.
    when(emailContactService.suggestRecipients(anyString(), any(), anyInt())).thenReturn(List.of());
    mockMvc.perform(get(CONTACTS_PATH + "/suggest").with(testSimpleUser())).andExpect(status().isOk());
    verify(emailContactService, never()).getContact(anyLong(), anyString());
  }

  @Test
  void getMissingContactAnswersNotFound() throws Exception {
    when(emailContactService.getContact(anyLong(), anyString())).thenReturn(null);
    mockMvc.perform(get(CONTACTS_PATH + "/12").with(testSimpleUser())).andExpect(status().isNotFound());
  }

  @Test
  void getOwnContactAnswersOk() throws Exception {
    when(emailContactService.getContact(eq(12L), anyString())).thenReturn(contact(12L));
    mockMvc.perform(get(CONTACTS_PATH + "/12").with(testSimpleUser()))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.primaryEmail").value("bob@example.org"));
  }

  @Test
  void importDirectoryAnswersTheCreatedRow() throws Exception {
    when(emailContactService.importDirectoryContact(eq(SIMPLE_USER), eq("jdoe"))).thenReturn(contact(12L));
    mockMvc.perform(post(CONTACTS_PATH + "/directory?username=jdoe").with(testSimpleUser()))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.id").value(12));
    // The acting user is the authenticated caller, never a parameter: the one
    // username the request carries names the colleague to import.
    verify(emailContactService).importDirectoryContact(eq(SIMPLE_USER), eq("jdoe"));
  }

  @Test
  void importDirectoryOfAnUnknownUserAnswersNotFound() throws Exception {
    when(emailContactService.importDirectoryContact(anyString(), anyString())).thenReturn(null);
    mockMvc.perform(post(CONTACTS_PATH + "/directory?username=ghost").with(testSimpleUser()))
           .andExpect(status().isNotFound());
  }

  @Test
  void importDirectoryOfOneselfAnswersBadRequest() throws Exception {
    when(emailContactService.importDirectoryContact(anyString(), anyString()))
                                                                              .thenThrow(new IllegalArgumentException(EmailContactService.CONTACT_SELF_IMPORT));
    mockMvc.perform(post(CONTACTS_PATH + "/directory?username=" + SIMPLE_USER).with(testSimpleUser()))
           .andExpect(status().isBadRequest());
  }

  @Test
  void importDirectoryOfAKnownPersonAnswersConflict() throws Exception {
    when(emailContactService.importDirectoryContact(anyString(), anyString()))
                                                                              .thenThrow(new IllegalStateException(EmailContactService.CONTACT_ALREADY_EXISTS));
    mockMvc.perform(post(CONTACTS_PATH + "/directory?username=jdoe").with(testSimpleUser()))
           .andExpect(status().isConflict());
  }

  @Test
  void byUserAnswersTheCallersRowForTheColleague() throws Exception {
    when(emailContactService.getContactByPlatformUser(eq(SIMPLE_USER), eq("jdoe"))).thenReturn(contact(12L));
    mockMvc.perform(get(CONTACTS_PATH + "/by-user?username=jdoe").with(testSimpleUser()))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.id").value(12));
  }

  @Test
  void byUserWithNoRowAnswersNotFound() throws Exception {
    when(emailContactService.getContactByPlatformUser(anyString(), anyString())).thenReturn(null);
    mockMvc.perform(get(CONTACTS_PATH + "/by-user?username=jdoe").with(testSimpleUser()))
           .andExpect(status().isNotFound());
    // The literal route wins over /{id}, or the lookup 400s on an unparseable id.
    verify(emailContactService, never()).getContact(anyLong(), anyString());
  }

  @Test
  void getAContactPhotoAnswersItWithItsOwnMimetype() throws Exception {
    // Whatever the user cropped is served as what it is: relabelling a JPEG as PNG
    // only makes clients sniff.
    when(emailContactService.getContactPhoto(eq(12L), anyString())).thenReturn(photo("image/jpeg"));
    mockMvc.perform(get(CONTACTS_PATH + "/12/photo").with(testSimpleUser()))
           .andExpect(status().isOk())
           .andExpect(content().contentType(MediaType.IMAGE_JPEG));
  }

  @Test
  void getAMissingOrForeignContactPhotoAnswersNotFound() throws Exception {
    // The service answers null for "no photo", "no such contact" and "not yours"
    // alike, so a photo URL can never be probed for another user's rows.
    when(emailContactService.getContactPhoto(anyLong(), anyString())).thenReturn(null);
    mockMvc.perform(get(CONTACTS_PATH + "/12/photo").with(testSimpleUser())).andExpect(status().isNotFound());
  }

  @Test
  void getTheQrVCardAnswersTheTextCard() throws Exception {
    when(emailContactVCardService.getContactVCard(anyString(), eq(12L), eq(false)))
        .thenReturn("BEGIN:VCARD\r\nVERSION:3.0\r\nFN:Bob\r\nEND:VCARD\r\n");
    mockMvc.perform(get(CONTACTS_PATH + "/12/vcard").with(testSimpleUser()))
           .andExpect(status().isOk())
           .andExpect(content().contentTypeCompatibleWith("text/vcard"));
  }

  @Test
  void getTheVCardWithItsPhotoIsAskedExplicitly() throws Exception {
    // The QR client sends no parameter and must keep getting the text-only
    // card; only photo=true — the attachment path — asks for the full one.
    when(emailContactVCardService.getContactVCard(anyString(), eq(12L), eq(true)))
        .thenReturn("BEGIN:VCARD\r\nVERSION:3.0\r\nFN:Bob\r\nPHOTO;ENCODING=b:AAAA\r\nEND:VCARD\r\n");
    mockMvc.perform(get(CONTACTS_PATH + "/12/vcard?photo=true").with(testSimpleUser()))
           .andExpect(status().isOk())
           .andExpect(content().contentTypeCompatibleWith("text/vcard"));
    verify(emailContactVCardService).getContactVCard(anyString(), eq(12L), eq(true));
  }

  @Test
  void getTheQrVCardOfAMissingOrForeignContactAnswersNotFound() throws Exception {
    // Null covers "no such row" and "somebody else's row" alike — the QR
    // endpoint must not become a way to probe another user's store either.
    when(emailContactVCardService.getContactVCard(anyString(), anyLong(), anyBoolean())).thenReturn(null);
    mockMvc.perform(get(CONTACTS_PATH + "/12/vcard").with(testSimpleUser())).andExpect(status().isNotFound());
  }

  @Test
  void getFromAttachmentAnswersThePrefill() throws Exception {
    when(emailContactVCardService.getAttachmentContact(anyString(), eq(7L), eq("2"), anyString())).thenReturn(contact(null));
    mockMvc.perform(get(CONTACTS_PATH + "/from-attachment?mailRemoteId=7&attachmentId=2").with(testSimpleUser()))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.primaryEmail").isNotEmpty());
  }

  @Test
  void getFromAttachmentThatIsNoVCardAnswersBadRequestWithTheCode() throws Exception {
    when(emailContactVCardService.getAttachmentContact(anyString(), anyLong(), anyString(), anyString()))
        .thenThrow(new IllegalArgumentException(EmailContactVCardService.ATTACHMENT_NOT_VCARD));
    mockMvc.perform(get(CONTACTS_PATH + "/from-attachment?mailRemoteId=7&attachmentId=2").with(testSimpleUser()))
           .andExpect(status().isBadRequest());
  }

  @Test
  void getFromAttachmentOfSomebodyElsesMailAnswersNotFound() throws Exception {
    // The mailbox answers "not yours", "not there" and "unreachable" with the
    // same exception, and every one of them must read 404 — never 403 — so a
    // mail id cannot be probed for existence.
    when(emailContactVCardService.getAttachmentContact(anyString(), anyLong(), anyString(), anyString()))
        .thenThrow(new IllegalStateException("Error when connecting store for user simple"));
    mockMvc.perform(get(CONTACTS_PATH + "/from-attachment?mailRemoteId=7&attachmentId=2").with(testSimpleUser()))
           .andExpect(status().isNotFound());
  }

  @Test
  void getFromAttachmentWithoutAMailboxAnswersUnauthorized() throws Exception {
    when(emailContactVCardService.getAttachmentContact(anyString(), anyLong(), anyString(), anyString()))
        .thenThrow(new IllegalAccessException("no mailbox"));
    mockMvc.perform(get(CONTACTS_PATH + "/from-attachment?mailRemoteId=7&attachmentId=2").with(testSimpleUser()))
           .andExpect(status().isUnauthorized());
  }

  @Test
  void createAnswersOk() throws Exception {
    when(emailContactService.createContact(any(EmailContact.class), anyString(), any(ContactOrigin.class))).thenReturn(contact(12L));
    mockMvc.perform(post(CONTACTS_PATH).with(testSimpleUser())
                                       .content(asJsonString(contact(null)))
                                       .contentType(MediaType.APPLICATION_JSON)
                                       .accept(MediaType.APPLICATION_JSON))
           .andExpect(status().isOk());
  }

  /**
   * The origin is the whole of slice 5's safety at this layer: this endpoint —
   * and only this one — witnesses a person pressing Save on one contact, so it
   * is the only one allowed to say so. If this ever stopped naming USER_FORM
   * the automatic push would go quiet; if anything else started naming it,
   * imports would publish.
   */
  @Test
  void createNamesTheFormAsTheOrigin() throws Exception {
    when(emailContactService.createContact(any(EmailContact.class), anyString(), any(ContactOrigin.class))).thenReturn(contact(12L));
    mockMvc.perform(post(CONTACTS_PATH).with(testSimpleUser())
                                       .content(asJsonString(contact(null)))
                                       .contentType(MediaType.APPLICATION_JSON)
                                       .accept(MediaType.APPLICATION_JSON))
           .andExpect(status().isOk());

    verify(emailContactService).createContact(any(EmailContact.class), eq(SIMPLE_USER), eq(ContactOrigin.USER_FORM));
  }

  @Test
  void createWithAnInvalidAddressAnswersBadRequest() throws Exception {
    when(emailContactService.createContact(any(EmailContact.class), anyString(), any(ContactOrigin.class)))
                                                                                 .thenThrow(new IllegalArgumentException(EmailContactService.CONTACT_INVALID_EMAIL));
    mockMvc.perform(post(CONTACTS_PATH).with(testSimpleUser())
                                       .content(asJsonString(contact(null)))
                                       .contentType(MediaType.APPLICATION_JSON))
           .andExpect(status().isBadRequest());
  }

  @Test
  void createOfAnExistingVisibleAddressAnswersConflict() throws Exception {
    when(emailContactService.createContact(any(EmailContact.class), anyString(), any(ContactOrigin.class)))
                                                                                 .thenThrow(new IllegalStateException(EmailContactService.CONTACT_ALREADY_EXISTS));
    mockMvc.perform(post(CONTACTS_PATH).with(testSimpleUser())
                                       .content(asJsonString(contact(null)))
                                       .contentType(MediaType.APPLICATION_JSON))
           .andExpect(status().isConflict());
  }

  @Test
  void updateOfAMissingContactAnswersNotFound() throws Exception {
    when(emailContactService.updateContact(anyLong(), any(EmailContact.class), anyString())).thenReturn(null);
    mockMvc.perform(put(CONTACTS_PATH + "/12").with(testSimpleUser())
                                              .content(asJsonString(contact(12L)))
                                              .contentType(MediaType.APPLICATION_JSON))
           .andExpect(status().isNotFound());
  }

  @Test
  void deleteSaysWhetherItSuppressedOrDeleted() throws Exception {
    EmailContact suppressed = contact(12L);
    suppressed.setSuppressed(true);
    when(emailContactService.deleteOrSuppressContact(eq(12L), anyString())).thenReturn(suppressed);
    mockMvc.perform(delete(CONTACTS_PATH + "/12").with(testSimpleUser()))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.suppressed").value(true));
  }

  @Test
  void deleteOfAMissingContactAnswersNotFound() throws Exception {
    when(emailContactService.deleteOrSuppressContact(anyLong(), anyString())).thenReturn(null);
    mockMvc.perform(delete(CONTACTS_PATH + "/12").with(testSimpleUser())).andExpect(status().isNotFound());
  }

  @Test
  void restoreAnswersOkOrNotFound() throws Exception {
    when(emailContactService.restoreContact(eq(12L), anyString())).thenReturn(contact(12L));
    mockMvc.perform(post(CONTACTS_PATH + "/12/restore").with(testSimpleUser())).andExpect(status().isOk());

    when(emailContactService.restoreContact(eq(13L), anyString())).thenReturn(null);
    mockMvc.perform(post(CONTACTS_PATH + "/13/restore").with(testSimpleUser())).andExpect(status().isNotFound());
  }

  @Test
  void exportAnswersAVcfAttachment() throws Exception {
    // The service writes onto whatever writer the response hands it; here it
    // writes one recognisable line so the test can see the streaming happened.
    org.mockito.Mockito.doAnswer(invocation -> {
      ((java.io.Writer) invocation.getArgument(1)).write("BEGIN:VCARD\r\n");
      return null;
    }).when(emailContactVCardService).exportContacts(anyString(), any(), any());

    mockMvc.perform(get(CONTACTS_PATH + "/export").with(testSimpleUser()))
           .andExpect(status().isOk())
           .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header()
                                                                                       .string("Content-Disposition",
                                                                                               "attachment; filename=\"contacts.vcf\""))
           .andExpect(content().string("BEGIN:VCARD\r\n"));
    // No ids, no selection: the service is asked for the whole store.
    verify(emailContactVCardService).exportContacts(anyString(), any(), org.mockito.ArgumentMatchers.isNull());
  }

  @Test
  void exportOfASelectionCarriesTheIdsAndItsOwnFilename() throws Exception {
    org.mockito.Mockito.doAnswer(invocation -> {
      ((java.io.Writer) invocation.getArgument(1)).write("BEGIN:VCARD\r\n");
      return null;
    }).when(emailContactVCardService).exportContacts(anyString(), any(), any());

    mockMvc.perform(get(CONTACTS_PATH + "/export?ids=7,3").with(testSimpleUser()))
           .andExpect(status().isOk())
           // A distinct name: a Downloads folder cannot tell a partial file from
           // a full one otherwise.
           .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header()
                                                                                       .string("Content-Disposition",
                                                                                               "attachment; filename=\"contacts-selection.vcf\""));
    // The order the caller ticked in survives the binding.
    verify(emailContactVCardService).exportContacts(anyString(), any(), eq(List.of(7L, 3L)));
  }

  @Test
  void exportOfATooBigSelectionAnswersBadRequest() throws Exception {
    org.mockito.Mockito.doThrow(new IllegalArgumentException(EmailContactVCardService.EXPORT_TOO_MANY_IDS))
                       .when(emailContactVCardService)
                       .exportContacts(anyString(), any(), any());

    mockMvc.perform(get(CONTACTS_PATH + "/export?ids=1,2").with(testSimpleUser()))
           .andExpect(status().isBadRequest());
  }

  @Test
  void importStartsARunAndAnswersItsState() throws Exception {
    ContactImportState started = new ContactImportState();
    started.setStatus(SyncStatus.IN_PROGRESS);
    when(emailContactVCardService.startImport(anyString(), eq("up-1"))).thenReturn(started);

    mockMvc.perform(post(CONTACTS_PATH + "/import?uploadId=up-1").with(testSimpleUser()))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.status").value("IN_PROGRESS"));
  }

  @Test
  void importOfAMissingUploadAnswersBadRequest() throws Exception {
    when(emailContactVCardService.startImport(anyString(), anyString()))
                                                                        .thenThrow(new IllegalArgumentException(EmailContactVCardService.IMPORT_UPLOAD_MISSING));
    mockMvc.perform(post(CONTACTS_PATH + "/import?uploadId=up-1").with(testSimpleUser())).andExpect(status().isBadRequest());
  }

  @Test
  void importWhileOneRunsAnswersConflict() throws Exception {
    when(emailContactVCardService.startImport(anyString(), anyString()))
                                                                        .thenThrow(new IllegalStateException(EmailContactVCardService.IMPORT_ALREADY_RUNNING));
    mockMvc.perform(post(CONTACTS_PATH + "/import?uploadId=up-1").with(testSimpleUser())).andExpect(status().isConflict());
  }

  @Test
  void importStatusAnswersTheStoredReport() throws Exception {
    ContactImportState state = new ContactImportState();
    state.setStatus(SyncStatus.SUCCESS);
    state.setImported(3);
    state.setAlreadyKnown(2);
    when(emailContactVCardService.getImportState(anyString())).thenReturn(state);

    mockMvc.perform(get(CONTACTS_PATH + "/import/status").with(testSimpleUser()))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.imported").value(3))
           .andExpect(jsonPath("$.alreadyKnown").value(2));
  }

  @Test
  void publishAnswersTheContactReRead() throws Exception {
    EmailContact published = contact(5L);
    published.setSource(EmailContactSource.CARDDAV);
    when(emailContactCardDavSyncService.publishContact(anyString(), eq(5L))).thenReturn(published);

    mockMvc.perform(post(CONTACTS_PATH + "/5/publish").with(testSimpleUser()))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.source").value("CARDDAV"));
  }

  @Test
  void publishOfSomebodyElsesContactAnswersNotFound() throws Exception {
    // Null from the service covers absent, foreign and suppressed alike, and
    // 404 keeps them indistinguishable -- the surface's 404-never-403 rule.
    when(emailContactCardDavSyncService.publishContact(anyString(), anyLong())).thenReturn(null);

    mockMvc.perform(post(CONTACTS_PATH + "/5/publish").with(testSimpleUser())).andExpect(status().isNotFound());
  }

  @Test
  void aPublishGuardRefusalAnswersBadRequestWithItsCode() throws Exception {
    when(emailContactCardDavSyncService.publishContact(anyString(), anyLong()))
                                                                               .thenThrow(new IllegalArgumentException(EmailContactCardDavSyncService.PUBLISH_NOT_DISCOVERED));

    mockMvc.perform(post(CONTACTS_PATH + "/5/publish").with(testSimpleUser())).andExpect(status().isBadRequest());
  }

  @Test
  void aRefusedCreateAnswersConflict() throws Exception {
    when(emailContactCardDavSyncService.publishContact(anyString(), anyLong()))
                                                                               .thenThrow(new IllegalStateException(EmailContactCardDavSyncService.PUBLISH_EXISTS_ON_SERVER));

    mockMvc.perform(post(CONTACTS_PATH + "/5/publish").with(testSimpleUser())).andExpect(status().isConflict());
  }

  @Test
  void aCardDavFailureAnswersBadGatewayNotAStackTrace() throws Exception {
    // The server's failure, not the caller's: a message code at 502, so a
    // client -- or a third-party integrator with no server log -- can tell
    // "your address book server is down" from "you asked wrong".
    when(emailContactCardDavSyncService.publishContact(anyString(), anyLong()))
                                                                               .thenThrow(new CardDavException("boom"));

    mockMvc.perform(post(CONTACTS_PATH + "/5/publish").with(testSimpleUser())).andExpect(status().isBadGateway());
  }

  @Test
  void aQueuedPublishAnswersAcceptedNotAnError() throws Exception {
    // The one CardDavException that is a promise: the click did its job, the
    // publish waits for the next successful sync. 202 with the message code,
    // so the client says "will be saved" instead of telling the user to retry
    // what no longer needs retrying.
    when(emailContactCardDavSyncService.publishContact(anyString(), anyLong()))
                                                                               .thenThrow(new CardDavPublishQueuedException("away", null));

    mockMvc.perform(post(CONTACTS_PATH + "/5/publish").with(testSimpleUser()))
           .andExpect(status().isAccepted())
           .andExpect(jsonPath("$.queued").value(true))
           .andExpect(jsonPath("$.messageCode").value(EmailContactCardDavSyncService.PUBLISH_QUEUED));
  }

  @Test
  void thePublishQueueAnswersItsStoredEntries() throws Exception {
    ContactPublishQueue queue = new ContactPublishQueue();
    queue.getEntries().add(new ContactPublishQueueEntry(42L, 1721000000000L, 2, true, "server said no", "boom", null));
    when(emailContactCardDavSyncService.getPublishQueue(anyString())).thenReturn(queue);

    mockMvc.perform(get(CONTACTS_PATH + "/carddav/publish-queue").with(testSimpleUser()))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.entries[0].contactId").value(42))
           .andExpect(jsonPath("$.entries[0].parked").value(true))
           .andExpect(jsonPath("$.entries[0].parkedReason").value("server said no"));
  }

  @Test
  void publishableAnswersTheStoredFlag() throws Exception {
    when(emailContactCardDavSyncService.isPublishAvailable(anyString())).thenReturn(true);

    mockMvc.perform(get(CONTACTS_PATH + "/carddav/publishable").with(testSimpleUser()))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.available").value(true));
  }

  @Test
  void publishOfferAnswersTheServersOwnRule() throws Exception {
    when(emailContactCardDavSyncService.isPublishOffered(SIMPLE_USER, 12L)).thenReturn(true);

    mockMvc.perform(get(CONTACTS_PATH + "/12/publish-offer").with(testSimpleUser()))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.offered").value(true));
  }

  /**
   * A contact that is not the caller's answers false rather than 404 — the
   * nudge question is not a way to learn that somebody else's contact exists.
   */
  @Test
  void publishOfferOfAForeignContactAnswersFalse() throws Exception {
    when(emailContactCardDavSyncService.isPublishOffered(anyString(), anyLong())).thenReturn(false);

    mockMvc.perform(get(CONTACTS_PATH + "/12/publish-offer").with(testSimpleUser()))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.offered").value(false));
  }

  /**
   * The authenticated simple user every call acts as.
   *
   * @return the request post-processor
   */
  private RequestPostProcessor testSimpleUser() {
    return user(SIMPLE_USER).password("password").authorities(new SimpleGrantedAuthority("users"));
  }

  /**
   * A minimal contact payload.
   *
   * @param id the contact id, or null for a create body
   * @return the contact
   */
  private EmailContact contact(Long id) {
    EmailContact contact = new EmailContact();
    contact.setId(id);
    contact.setSource(EmailContactSource.COLLECTED);
    contact.setPrimaryEmail("bob@example.org");
    contact.setDisplayName("Bob");
    return contact;
  }

  /**
   * A stored photo as the file service hands it back.
   *
   * @param mimetype the stored mimetype
   * @return the file item
   */
  @SneakyThrows
  private FileItem photo(String mimetype) {
    return new FileItem(77L,
                        "emailContactPhoto",
                        mimetype,
                        "emailConnector",
                        3,
                        new Date(),
                        null,
                        false,
                        new ByteArrayInputStream(new byte[] { 1, 2, 3 }));
  }

  /**
   * Serializes a payload the way the client would.
   *
   * @param obj the payload
   * @return its JSON
   */
  @SneakyThrows
  private String asJsonString(final Object obj) {
    return OBJECT_MAPPER.writeValueAsString(obj);
  }
}
