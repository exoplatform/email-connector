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
package org.exoplatform.emailConnector.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.exoplatform.commons.exception.ObjectNotFoundException;
import org.exoplatform.emailConnector.mcp.model.ContactHitModel;
import org.exoplatform.emailConnector.mcp.model.ContactModel;
import org.exoplatform.emailConnector.mcp.model.ContactSearchResultsModel;
import org.exoplatform.emailConnector.mcp.model.RecipientSuggestionModel;
import org.exoplatform.emailConnector.model.EmailContact;
import org.exoplatform.emailConnector.model.EmailContactPage;
import org.exoplatform.emailConnector.model.EmailContactSource;
import org.exoplatform.emailConnector.model.EmailContactSuggestion;
import org.exoplatform.emailConnector.service.EmailContactService;

/**
 * Unit tests of the contact MCP tools: the hard cap on list pages, the
 * per-user scoping (another user's contact answers not-found), and — the
 * sensitive part — that no phone number and no photo URL ever leaves a tool
 * except the single-contact read's phones.
 */
class EmailContactMcpToolTest {

  private static final String USERNAME   = "testuser1";

  private static final long   CONTACT_ID = 42L;

  private EmailContactService emailContactService;

  private EmailContactMcpTool contactMcpTool;

  /**
   * Wires the tool over a mocked service, pinning the acting username the same
   * way the MCP session would supply it.
   */
  @BeforeEach
  void setUp() {
    emailContactService = Mockito.mock(EmailContactService.class);
    contactMcpTool = new EmailContactMcpTool(emailContactService) {
      @Override
      public String getCurrentUserName() {
        return USERNAME;
      }
    };
  }

  /**
   * Builds one enriched store row the way the service answers it, phones and
   * photo URL included — so the tests can prove the tool drops them.
   *
   * @param id the row id
   * @return the contact
   */
  private EmailContact buildContact(long id) {
    EmailContact contact = new EmailContact();
    contact.setId(id);
    contact.setUserId(USERNAME);
    contact.setSource(EmailContactSource.COLLECTED);
    contact.setPrimaryEmail("marie@acme.com");
    contact.setDisplayName("Marie Dupont");
    contact.setGivenName("Marie");
    contact.setFamilyName("Dupont");
    contact.setOrganization("Acme");
    contact.setTitle("CFO");
    contact.setPhones(List.of("+33 6 12 34 56 78"));
    contact.setAvatarUrl("/email-connector/rest/contacts/42/photo?v=1");
    contact.setPhotoFileId(7L);
    contact.setFavorite(true);
    return contact;
  }

  // --- search_contacts -----------------------------------------------------

  /**
   * The whole point of the tool: the hit carries what send_email needs next,
   * and the total keeps the agent honest about what it did not see.
   */
  @Test
  void searchContactsAnswersHitsAndTotal() {
    EmailContactPage page = new EmailContactPage(List.of(buildContact(CONTACT_ID)), Map.of(), 90, 0, 20);
    when(emailContactService.getContacts(eq(USERNAME), isNull(), eq("marie"), eq(false), eq(0), anyInt())).thenReturn(page);

    ContactSearchResultsModel results = contactMcpTool.searchContacts("marie", null, null, null);

    assertEquals(90, results.getTotalMatches());
    assertEquals(1, results.getResults().size());
    ContactHitModel hit = results.getResults().get(0);
    assertEquals(CONTACT_ID, hit.getId());
    assertEquals("Marie Dupont", hit.getDisplayName());
    assertEquals("marie@acme.com", hit.getEmail());
    assertEquals("Acme", hit.getOrganization());
    assertTrue(hit.isFavorite());
  }

  /**
   * List results must never carry phone numbers, photo bytes or photo URLs,
   * whatever the store row held.
   */
  @Test
  void searchContactsNeverLeaksPhonesOrPhotos() throws Exception {
    EmailContactPage page = new EmailContactPage(List.of(buildContact(CONTACT_ID)), Map.of(), 1, 0, 20);
    when(emailContactService.getContacts(eq(USERNAME), isNull(), any(), anyBoolean(), anyInt(), anyInt())).thenReturn(page);

    ContactSearchResultsModel results = contactMcpTool.searchContacts("marie", null, null, null);

    String json = new ObjectMapper().writeValueAsString(results);
    assertFalse(json.toLowerCase().contains("phone"), "Search hits must not carry phone numbers: " + json);
    assertFalse(json.toLowerCase().contains("photo"), "Search hits must not carry photo URLs: " + json);
    assertFalse(json.toLowerCase().contains("avatar"), "Search hits must not carry avatar URLs: " + json);
    assertFalse(json.contains("+33"), "Search hits must not carry the phone value: " + json);
  }

  /**
   * The limit is hard-capped at 50 whatever the caller asks for, and defaults
   * to 20 when the caller names none — the store must not be dumpable through
   * an oversized page.
   */
  @Test
  void searchContactsHardCapsTheLimit() {
    when(emailContactService.getContacts(eq(USERNAME), isNull(), any(), anyBoolean(), anyInt(), anyInt()))
                                                                                                          .thenReturn(new EmailContactPage(List.of(),
                                                                                                                                           Map.of(),
                                                                                                                                           0,
                                                                                                                                           0,
                                                                                                                                           20));

    contactMcpTool.searchContacts("marie", null, null, 100000);
    verify(emailContactService).getContacts(eq(USERNAME), isNull(), eq("marie"), eq(false), eq(0), eq(50));

    contactMcpTool.searchContacts("acme", null, null, null);
    verify(emailContactService).getContacts(eq(USERNAME), isNull(), eq("acme"), eq(false), eq(0), eq(20));
  }

  /**
   * The source filter travels as the service expects it, and the favorites
   * flag rides along.
   */
  @Test
  void searchContactsPassesSourceAndFavorites() {
    when(emailContactService.getContacts(eq(USERNAME), any(), any(), anyBoolean(), anyInt(), anyInt()))
                                                                                                       .thenReturn(new EmailContactPage(List.of(),
                                                                                                                                        Map.of(),
                                                                                                                                        0,
                                                                                                                                        0,
                                                                                                                                        20));

    contactMcpTool.searchContacts("marie", "manual", true, null);

    verify(emailContactService).getContacts(eq(USERNAME), eq(List.of("manual")), eq("marie"), eq(true), eq(0), eq(20));
  }

  /**
   * A refused source comes back in words the model can act on, not as the
   * REST message code.
   */
  @Test
  void searchContactsTranslatesTheInvalidSourceCode() {
    when(emailContactService.getContacts(eq(USERNAME), any(), any(), anyBoolean(), anyInt(), anyInt()))
                                                                                                       .thenThrow(new IllegalArgumentException(EmailContactService.CONTACT_INVALID_SOURCE));

    IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                                                   () -> contactMcpTool.searchContacts("marie", "nonsense", null, null));
    assertFalse(thrown.getMessage().contains("emailConnector."));
    assertTrue(thrown.getMessage().contains("collected"));
  }

  // --- get_contact ---------------------------------------------------------

  /**
   * The single-contact read is the one place phones are answered — and still
   * no photo, neither bytes nor URL.
   */
  @Test
  void getContactAnswersPhonesButNoPhoto() throws Exception {
    when(emailContactService.getContact(eq(CONTACT_ID), eq(USERNAME))).thenReturn(buildContact(CONTACT_ID));

    ContactModel contact = contactMcpTool.getContact(CONTACT_ID);

    assertNotNull(contact);
    assertEquals("Marie Dupont", contact.getDisplayName());
    assertEquals("marie@acme.com", contact.getEmail());
    assertEquals(List.of("+33 6 12 34 56 78"), contact.getPhones());
    String json = new ObjectMapper().writeValueAsString(contact);
    assertFalse(json.toLowerCase().contains("photo"), "The contact payload must not carry photo fields: " + json);
    assertFalse(json.toLowerCase().contains("avatar"), "The contact payload must not carry avatar URLs: " + json);
  }

  /**
   * The service answers null alike for a missing row, a removed one and
   * another user's — the tool must answer not-found for all of them, so an id
   * never reveals whether it exists in somebody else's store.
   */
  @Test
  void getContactOfAnotherUserAnswersNotFound() {
    when(emailContactService.getContact(eq(CONTACT_ID), eq(USERNAME))).thenReturn(null);
    assertThrows(ObjectNotFoundException.class, () -> contactMcpTool.getContact(CONTACT_ID));
  }

  // --- suggest_recipients --------------------------------------------------

  /**
   * The suggestion reaches the agent as the compose field ranks it, and the
   * avatar URL the UI shows is dropped on the way.
   */
  @Test
  void suggestRecipientsMapsTheRankingWithoutAvatars() throws Exception {
    when(emailContactService.suggestRecipients(eq(USERNAME), eq("marie"), eq(0)))
                                                                                 .thenReturn(List.of(new EmailContactSuggestion("marie@acme.com",
                                                                                                                                "Marie Dupont",
                                                                                                                                "/rest/avatar/marie",
                                                                                                                                false,
                                                                                                                                null),
                                                                                                     new EmailContactSuggestion("marie.colleague@exo.com",
                                                                                                                                "Marie Colleague",
                                                                                                                                "/rest/avatar/mc",
                                                                                                                                true,
                                                                                                                                "/portal/profile/mc")));

    List<RecipientSuggestionModel> suggestions = contactMcpTool.suggestRecipients("marie", null);

    assertEquals(2, suggestions.size());
    assertEquals("marie@acme.com", suggestions.get(0).getAddress());
    assertFalse(suggestions.get(0).isPlatformUser());
    assertTrue(suggestions.get(1).isPlatformUser());
    assertEquals("/portal/profile/mc", suggestions.get(1).getProfileUrl());
    String json = new ObjectMapper().writeValueAsString(suggestions);
    assertFalse(json.toLowerCase().contains("avatar"), "Suggestions must not carry avatar URLs: " + json);
    assertFalse(json.toLowerCase().contains("phone"), "Suggestions must not carry phone numbers: " + json);
  }

  /**
   * A named limit is handed to the service, whose own hard cap (25) is the
   * authority — the tool must not invent a second clamp that could widen it.
   */
  @Test
  void suggestRecipientsDelegatesTheLimitToTheServiceCap() {
    when(emailContactService.suggestRecipients(eq(USERNAME), eq("marie"), eq(500))).thenReturn(List.of());
    contactMcpTool.suggestRecipients("marie", 500);
    verify(emailContactService).suggestRecipients(eq(USERNAME), eq("marie"), eq(500));
  }

  // --- create_contact ------------------------------------------------------

  /**
   * The tool builds the same request body the contact form posts and answers
   * the new id, so the assistant can chain into get_contact.
   */
  @Test
  void createContactDelegatesAndAnswersTheId() {
    EmailContact created = buildContact(CONTACT_ID);
    when(emailContactService.createContact(any(), eq(USERNAME))).thenReturn(created);

    String message = contactMcpTool.createContact("marie@acme.com",
                                                  "Marie",
                                                  "Dupont",
                                                  null,
                                                  "Acme",
                                                  "CFO",
                                                  List.of("+33 6 12 34 56 78"));

    ArgumentCaptor<EmailContact> captor = ArgumentCaptor.forClass(EmailContact.class);
    verify(emailContactService).createContact(captor.capture(), eq(USERNAME));
    EmailContact sent = captor.getValue();
    assertEquals("marie@acme.com", sent.getPrimaryEmail());
    assertEquals("Marie", sent.getGivenName());
    assertEquals("Dupont", sent.getFamilyName());
    assertEquals("Acme", sent.getOrganization());
    assertEquals("CFO", sent.getTitle());
    assertEquals(List.of("+33 6 12 34 56 78"), sent.getPhones());
    assertTrue(message.contains("id 42"), message);
    assertTrue(message.contains("marie@acme.com"), message);
  }

  /**
   * The service's message codes come back in words: the conflict names the
   * search tool that finds the existing row, the invalid address says what is
   * missing.
   */
  @Test
  void createContactTranslatesTheMessageCodes() {
    when(emailContactService.createContact(any(), eq(USERNAME)))
                                                                .thenThrow(new IllegalStateException(EmailContactService.CONTACT_ALREADY_EXISTS));
    IllegalStateException conflict = assertThrows(IllegalStateException.class,
                                                  () -> contactMcpTool.createContact("marie@acme.com",
                                                                                     null,
                                                                                     null,
                                                                                     null,
                                                                                     null,
                                                                                     null,
                                                                                     null));
    assertFalse(conflict.getMessage().contains("emailConnector."));
    assertTrue(conflict.getMessage().contains("search_contacts"));

    Mockito.reset(emailContactService);
    when(emailContactService.createContact(any(), eq(USERNAME)))
                                                                .thenThrow(new IllegalArgumentException(EmailContactService.CONTACT_INVALID_EMAIL));
    IllegalArgumentException invalid = assertThrows(IllegalArgumentException.class,
                                                    () -> contactMcpTool.createContact("nonsense",
                                                                                       null,
                                                                                       null,
                                                                                       null,
                                                                                       null,
                                                                                       null,
                                                                                       null));
    assertFalse(invalid.getMessage().contains("emailConnector."));
    assertTrue(invalid.getMessage().toLowerCase().contains("email address"));
  }
}
