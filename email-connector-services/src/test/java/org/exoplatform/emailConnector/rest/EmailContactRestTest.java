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
import org.exoplatform.emailConnector.model.EmailContact;
import org.exoplatform.emailConnector.model.EmailContactSource;
import org.exoplatform.emailConnector.model.EmailContactSuggestion;
import org.exoplatform.emailConnector.service.EmailContactCardDavSyncService;
import org.exoplatform.emailConnector.service.EmailContactService;

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
    when(emailContactService.getContacts(anyString(), eq(List.of("bogus")), any(), anyInt(), anyInt()))
                                                                                              .thenThrow(new IllegalArgumentException(EmailContactService.CONTACT_INVALID_SOURCE));
    mockMvc.perform(get(CONTACTS_PATH + "?source=bogus").with(testSimpleUser())).andExpect(status().isBadRequest());
  }

  @Test
  void severalSourcesReachTheServiceTogether() throws Exception {
    // Repeated parameters, because the chips they come from are multi-select. Bound
    // to a single value, two selected chips reached the service as one -- and the
    // service, given more than it could express, answered with the whole store.
    mockMvc.perform(get(CONTACTS_PATH + "?source=collected&source=manual").with(testSimpleUser())).andExpect(status().isOk());

    verify(emailContactService).getContacts(anyString(), eq(List.of("collected", "manual")), any(), anyInt(), anyInt());
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
    when(emailContactService.suggestRecipients(anyString(), any(), anyInt())).thenReturn(List.of());
    mockMvc.perform(get(CONTACTS_PATH + "/suggest").with(testSimpleUser())).andExpect(status().isOk());
  }

  @Test
  void suggestIsNotSwallowedByTheContactIdRoute() throws Exception {
    // "/contacts/suggest" and "/contacts/{id}" share a path segment; the literal
    // has to win, or the type-ahead 400s on an unparseable id.
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
  void createAnswersOk() throws Exception {
    when(emailContactService.createContact(any(EmailContact.class), anyString())).thenReturn(contact(12L));
    mockMvc.perform(post(CONTACTS_PATH).with(testSimpleUser())
                                       .content(asJsonString(contact(null)))
                                       .contentType(MediaType.APPLICATION_JSON)
                                       .accept(MediaType.APPLICATION_JSON))
           .andExpect(status().isOk());
  }

  @Test
  void createWithAnInvalidAddressAnswersBadRequest() throws Exception {
    when(emailContactService.createContact(any(EmailContact.class), anyString()))
                                                                                 .thenThrow(new IllegalArgumentException(EmailContactService.CONTACT_INVALID_EMAIL));
    mockMvc.perform(post(CONTACTS_PATH).with(testSimpleUser())
                                       .content(asJsonString(contact(null)))
                                       .contentType(MediaType.APPLICATION_JSON))
           .andExpect(status().isBadRequest());
  }

  @Test
  void createOfAnExistingVisibleAddressAnswersConflict() throws Exception {
    when(emailContactService.createContact(any(EmailContact.class), anyString()))
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
