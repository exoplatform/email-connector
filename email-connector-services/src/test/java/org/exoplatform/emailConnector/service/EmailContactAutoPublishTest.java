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
package org.exoplatform.emailConnector.service;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Date;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;

import org.exoplatform.emailConnector.carddav.CardDavClient;
import org.exoplatform.emailConnector.carddav.CardDavException;
import org.exoplatform.emailConnector.carddav.PutResult;
import org.exoplatform.emailConnector.carddav.VCardParser;
import org.exoplatform.emailConnector.model.ContactPublishQueue;
import org.exoplatform.emailConnector.model.ContactPublishQueueEntry;
import org.exoplatform.emailConnector.model.ContactSyncState;
import org.exoplatform.emailConnector.model.EmailConnector;
import org.exoplatform.emailConnector.model.EmailContact;
import org.exoplatform.emailConnector.model.EmailContactSource;
import org.exoplatform.emailConnector.model.UserEmailSetting;
import org.exoplatform.emailConnector.storage.EmailContactStorage;

/**
 * The automatic push, pinned by its refusals more than by its success.
 * <p>
 * Every test here is one half of the same sentence: a contact somebody
 * authored, on a user who asked for this, on a deployment that allows it,
 * reaches the address book — and everything else does not. The two switches
 * are AND-ed, both default to off, and neither the caller nor a failure of the
 * server can ever turn a save into anything but a saved contact.
 */
@ExtendWith(MockitoExtension.class)
@SpringBootTest(classes = { EmailContactCardDavSyncService.class })
public class EmailContactAutoPublishTest {

  private static final String            USERNAME     = "alice";

  private static final long              CONNECTOR_ID = 7L;

  private static final String            BOOK_URL     = "https://mail.example.com/dav/alice/default/";

  private static final long              CONTACT_ID   = 5L;

  @MockBean
  private EmailContactStorage            emailContactStorage;

  @MockBean
  private UserEmailSettingService        userEmailSettingService;

  @MockBean
  private EmailConnectorService          emailConnectorService;

  @MockBean
  private CardDavClient                  cardDavClient;

  @MockBean
  private VCardParser                    vCardParser;

  @MockBean
  private EmailContactFavoriteService    emailContactFavoriteService;

  @MockBean
  private EmailContactService            emailContactService;

  @MockBean
  private EmailContactVCardService       emailContactVCardService;

  @Autowired
  private EmailContactCardDavSyncService syncService;

  /** The user's stored setting, mutated per test to move either switch. */
  private UserEmailSetting               setting;

  /** The stored queue, shared by reference like the settings accessor shares it. */
  private ContactPublishQueue            queue;

  /**
   * A user whose address book is bound and discovered, and who has NOT turned
   * the automatic push on — the state every existing user is in at upgrade, and
   * therefore the state each test has to opt out of explicitly.
   */
  @BeforeEach
  void setUp() {
    setting = new UserEmailSetting();
    setting.setEmailConnectorId(String.valueOf(CONNECTOR_ID));
    setting.setEmailAddress("alice@example.com");
    setting.setEmailPassword("secret");
    setting.setCarddavEnabled(true);
    lenient().when(userEmailSettingService.getUserEmailSetting(USERNAME)).thenReturn(setting);

    EmailConnector connector = new EmailConnector();
    connector.setId(CONNECTOR_ID);
    connector.setCarddavUrl("https://mail.example.com");
    lenient().when(emailConnectorService.getEmailConnector(CONNECTOR_ID)).thenReturn(connector);

    ContactSyncState state = new ContactSyncState();
    state.setAddressBookHref(BOOK_URL);
    state.setConfiguredUrl("https://mail.example.com");
    state.setCtag("ctag-1");
    lenient().when(userEmailSettingService.getContactSyncState(USERNAME)).thenReturn(state);

    queue = new ContactPublishQueue();
    lenient().when(userEmailSettingService.getContactPublishQueue(USERNAME)).thenReturn(queue);

    lenient().when(emailContactService.getContact(CONTACT_ID, USERNAME))
             .thenReturn(ownContact(CONTACT_ID, EmailContactSource.MANUAL));
    lenient().when(emailContactVCardService.getPublishVCard(any(), anyString())).thenReturn("BEGIN:VCARD\nEND:VCARD\n");
  }

  // ---------------------------------------------------------------- the two switches

  @Test
  void aUserWhoNeverAskedPublishesNothing() {
    // The upgrade case, and the default: the stored setting says nothing about
    // the automatic push, which must read as no rather than as "not yet asked".
    syncService.autoPublishContact(USERNAME, CONTACT_ID);

    verifyNothingLeft();
  }

  @Test
  void aUserWhoTurnedItOffPublishesNothing() {
    setting.setCarddavAutoPublish(false);

    syncService.autoPublishContact(USERNAME, CONTACT_ID);

    verifyNothingLeft();
  }

  @Test
  void anAuthoredContactReachesTheAddressBookWhenBothSwitchesAreOn() {
    setting.setCarddavAutoPublish(true);
    when(cardDavClient.putVCard(anyString(), anyString(), anyString(), anyString(), anyString()))
                      .thenReturn(new PutResult(201, "\"etag-9\"", null));

    syncService.autoPublishContact(USERNAME, CONTACT_ID);

    // Through the click's own path, which is the point: creates-only under
    // If-None-Match, and the row bound to the entry it just became.
    verify(cardDavClient).putVCard(anyString(), anyString(), eq("*"), eq("alice@example.com"), eq("secret"));
    verify(emailContactStorage).bindPublishedCard(eq(CONTACT_ID), eq(CONNECTOR_ID), anyString(), eq("\"etag-9\""), anyString());
  }

  @Test
  void theAdministratorsSwitchOverrulesTheUsers() {
    setting.setCarddavAutoPublish(true);
    System.setProperty(EmailContactCardDavSyncService.PUBLISH_ENABLED_PROPERTY, "false");
    try {
      syncService.autoPublishContact(USERNAME, CONTACT_ID);

      verifyNothingLeft();
    } finally {
      System.clearProperty(EmailContactCardDavSyncService.PUBLISH_ENABLED_PROPERTY);
    }
  }

  @Test
  void withNoBookBoundNothingIsPublishedAndNothingIsQueued() {
    // Not even queued: an entry waiting on a book the user never bound is a
    // list that grows and never drains, about contacts that are perfectly fine
    // where they are.
    setting.setCarddavAutoPublish(true);
    setting.setCarddavEnabled(false);

    syncService.autoPublishContact(USERNAME, CONTACT_ID);

    verifyNothingLeft();
  }

  // ---------------------------------------------------------------- never at the save's expense

  @Test
  void anUnreachableServerParksTheCardInsteadOfFailingTheSave() {
    setting.setCarddavAutoPublish(true);
    when(cardDavClient.putVCard(anyString(), anyString(), anyString(), anyString(), anyString()))
                      .thenThrow(new CardDavException("connection refused"));

    // The contact is already stored; a push that could not happen must not
    // reach back and turn a successful save into an error.
    assertDoesNotThrow(() -> syncService.autoPublishContact(USERNAME, CONTACT_ID));

    // And it is not lost either: the click's fallback queue is this path's
    // fallback queue, drained by the next successful sync.
    ArgumentCaptor<ContactPublishQueue> saved = ArgumentCaptor.forClass(ContactPublishQueue.class);
    verify(userEmailSettingService).setContactPublishQueue(saved.capture(), eq(USERNAME));
    assertEquals(1, saved.getValue().getEntries().size());
    assertEquals(CONTACT_ID, saved.getValue().getEntries().get(0).getContactId());
    assertFalse(saved.getValue().getEntries().get(0).isParked());
  }

  @Test
  void aRefusalNeverFailsTheSaveEither() {
    // A guard refusal -- here, a source that may not be published at all --
    // never reaches the queue, and must not reach the caller: the user asked
    // to save a contact, and they got one.
    setting.setCarddavAutoPublish(true);
    when(emailContactService.getContact(CONTACT_ID, USERNAME))
                            .thenReturn(ownContact(CONTACT_ID, EmailContactSource.DIRECTORY));

    assertDoesNotThrow(() -> syncService.autoPublishContact(USERNAME, CONTACT_ID));

    verify(cardDavClient, never()).putVCard(anyString(), anyString(), anyString(), anyString(), anyString());
    verify(userEmailSettingService, never()).setContactPublishQueue(any(), anyString());
  }

  @Test
  void anUnreadableSettingNeverFailsTheSaveEither() {
    // The two switches are read from the user's stored setting, which decodes
    // their mail password on the way out -- so asking whether to publish can
    // itself throw. That question is asked after this save has committed, in
    // the request's own thread: escaping here would fail a POST whose contact
    // is already stored, which is the one outcome this path may never produce.
    when(userEmailSettingService.getUserEmailSetting(USERNAME)).thenThrow(new IllegalStateException("codec unavailable"));

    assertDoesNotThrow(() -> syncService.autoPublishContact(USERNAME, CONTACT_ID));

    verify(cardDavClient, never()).putVCard(anyString(), anyString(), anyString(), anyString(), anyString());
    verify(userEmailSettingService, never()).setContactPublishQueue(any(), anyString());
  }

  // ---------------------------------------------------------------- the nudge's own question

  @Test
  void aLocalRowIsWorthOffering() {
    assertTrue(syncService.isPublishOffered(USERNAME, CONTACT_ID));
  }

  @Test
  void anAddressBookRowIsNotWorthOffering() {
    // It is already there; its edits go through the server push.
    when(emailContactService.getContact(CONTACT_ID, USERNAME))
                            .thenReturn(ownContact(CONTACT_ID, EmailContactSource.CARDDAV));

    assertFalse(syncService.isPublishOffered(USERNAME, CONTACT_ID));
  }

  @Test
  void aDirectoryColleagueIsNeverWorthOffering() {
    when(emailContactService.getContact(CONTACT_ID, USERNAME))
                            .thenReturn(ownContact(CONTACT_ID, EmailContactSource.DIRECTORY));

    assertFalse(syncService.isPublishOffered(USERNAME, CONTACT_ID));
  }

  @Test
  void somebodyElsesContactIsNotWorthOffering() {
    // Answered like an absent one, as everywhere on this surface: the question
    // is not a way to learn that another user's contact exists.
    when(emailContactService.getContact(CONTACT_ID, USERNAME)).thenReturn(null);

    assertFalse(syncService.isPublishOffered(USERNAME, CONTACT_ID));
  }

  @Test
  void aContactAlreadyOnItsWayIsNotWorthOffering() {
    queue.getEntries().add(entry(CONTACT_ID, 0, false));

    assertFalse(syncService.isPublishOffered(USERNAME, CONTACT_ID));
  }

  @Test
  void aParkedContactIsWorthOfferingAgain() {
    // Parked means the queue gave up, and re-asking is exactly how a parked
    // entry gets retried -- so this is the one queued state still offered.
    queue.getEntries().add(entry(CONTACT_ID, 3, true));

    assertTrue(syncService.isPublishOffered(USERNAME, CONTACT_ID));
  }

  @Test
  void nothingIsOfferedWhenPublishingCannotWorkAtAll() {
    setting.setCarddavEnabled(false);

    assertFalse(syncService.isPublishOffered(USERNAME, CONTACT_ID));
  }

  /**
   * Nothing left for the address book, in either shape: no card written, and no
   * entry queued to write one later.
   */
  private void verifyNothingLeft() {
    verify(cardDavClient, never()).putVCard(anyString(), anyString(), anyString(), anyString(), anyString());
    verify(userEmailSettingService, never()).setContactPublishQueue(any(), anyString());
    verify(emailContactStorage, never()).bindPublishedCard(anyLong(), anyLong(), anyString(), any(), anyString());
  }

  /**
   * One of the caller's own contacts.
   *
   * @param id the contact id
   * @param source the stored source
   * @return the contact
   */
  private EmailContact ownContact(long id, String source) {
    EmailContact contact = new EmailContact();
    contact.setId(id);
    contact.setUserId(USERNAME);
    contact.setSource(source);
    contact.setPrimaryEmail("bob@example.org");
    contact.setDisplayName("Bob");
    return contact;
  }

  /**
   * A stored queue entry.
   *
   * @param contactId the queued contact
   * @param attempts how many drains it has cost
   * @param parked whether the queue gave up on it
   * @return the entry
   */
  private ContactPublishQueueEntry entry(long contactId, int attempts, boolean parked) {
    return new ContactPublishQueueEntry(contactId,
                                        new Date().getTime(),
                                        attempts,
                                        parked,
                                        parked ? "parked in a previous drain" : null,
                                        null,
                                        null);
  }
}
