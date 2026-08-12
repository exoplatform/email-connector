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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
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
import org.exoplatform.emailConnector.carddav.CardDavPublishQueuedException;
import org.exoplatform.emailConnector.carddav.PutResult;
import org.exoplatform.emailConnector.carddav.VCardParser;
import org.exoplatform.emailConnector.model.ContactPublishQueue;
import org.exoplatform.emailConnector.model.ContactPublishQueueEntry;
import org.exoplatform.emailConnector.model.ContactSyncState;
import org.exoplatform.emailConnector.model.EmailConnector;
import org.exoplatform.emailConnector.model.EmailContact;
import org.exoplatform.emailConnector.model.EmailContactSource;
import org.exoplatform.emailConnector.model.SyncStatus;
import org.exoplatform.emailConnector.model.UserEmailSetting;
import org.exoplatform.emailConnector.storage.EmailContactStorage;

/**
 * The outbound queue's promises, pinned one by one: a publish the server
 * cannot take is neither lost nor silent, it retries only when a successful
 * sync proves the server back, it gives up loudly (parked, with why) instead
 * of looping, and none of its failures ever count against the INBOUND
 * failure counter — a broken publish must not stop anybody reading.
 */
@ExtendWith(MockitoExtension.class)
@SpringBootTest(classes = { EmailContactCardDavSyncService.class })
public class EmailContactPublishQueueTest {

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

  /** The stored queue, shared by reference like the settings accessor shares it within a call chain. */
  private ContactPublishQueue            queue;

  /** The stored sync state, discovered and matching the configured URL. */
  private ContactSyncState               state;

  /**
   * A user whose book is bound, discovered and previously synced whole, so a
   * scheduled run takes the cheap unchanged-version path — the smallest
   * successful run a drain can ride on.
   */
  @BeforeEach
  void setUp() {
    UserEmailSetting setting = new UserEmailSetting();
    setting.setEmailConnectorId(String.valueOf(CONNECTOR_ID));
    setting.setEmailAddress("alice@example.com");
    setting.setEmailPassword("secret");
    setting.setCarddavEnabled(true);
    lenient().when(userEmailSettingService.getUserEmailSetting(USERNAME)).thenReturn(setting);

    EmailConnector connector = new EmailConnector();
    connector.setId(CONNECTOR_ID);
    connector.setCarddavUrl("https://mail.example.com");
    lenient().when(emailConnectorService.getEmailConnector(CONNECTOR_ID)).thenReturn(connector);

    state = new ContactSyncState();
    state.setAddressBookHref(BOOK_URL);
    state.setConfiguredUrl("https://mail.example.com");
    state.setCtag("ctag-1");
    lenient().when(userEmailSettingService.getContactSyncState(USERNAME)).thenReturn(state);
    lenient().when(cardDavClient.getCtag(any(), anyString(), anyString())).thenReturn("ctag-1");

    queue = new ContactPublishQueue();
    lenient().when(userEmailSettingService.getContactPublishQueue(USERNAME)).thenReturn(queue);

    lenient().when(emailContactService.getContact(CONTACT_ID, USERNAME))
             .thenReturn(ownContact(CONTACT_ID, EmailContactSource.MANUAL));
    lenient().when(emailContactVCardService.getPublishVCard(any(), anyString())).thenReturn("BEGIN:VCARD\nEND:VCARD\n");
  }

  @Test
  void aPublishTheServerCannotTakeIsQueuedNotLost() {
    when(cardDavClient.putVCard(anyString(), anyString(), anyString(), anyString(), anyString()))
                      .thenThrow(new CardDavException("connection refused"));

    // The queued exception, not the plain one: the REST layer answers it 202,
    // which is what makes the failure non-silent without being an error.
    assertThrows(CardDavPublishQueuedException.class, () -> syncService.publishContact(USERNAME, CONTACT_ID));

    ArgumentCaptor<ContactPublishQueue> saved = ArgumentCaptor.forClass(ContactPublishQueue.class);
    verify(userEmailSettingService).setContactPublishQueue(saved.capture(), eq(USERNAME));
    assertEquals(1, saved.getValue().getEntries().size());
    ContactPublishQueueEntry entry = saved.getValue().getEntries().get(0);
    assertEquals(CONTACT_ID, entry.getContactId());
    assertFalse(entry.isParked());
    assertEquals(0, entry.getAttempts(), "the click's own failure is not a drain attempt");
    assertNotNull(entry.getEnqueuedDate());
    // And the contact itself never moved: no binding, no source flip.
    verify(emailContactStorage, never()).bindPublishedCard(anyLong(), anyLong(), anyString(), any(), anyString());
  }

  @Test
  void aGuardRefusalNeverFallsBackToTheQueue() {
    // Retrying a NO about the contact itself cannot turn it into a yes, so the
    // refusal keeps its immediate, explicit shape.
    when(emailContactService.getContact(CONTACT_ID, USERNAME))
                            .thenReturn(ownContact(CONTACT_ID, EmailContactSource.DIRECTORY));

    assertThrows(IllegalArgumentException.class, () -> syncService.publishContact(USERNAME, CONTACT_ID));

    verify(userEmailSettingService, never()).setContactPublishQueue(any(), anyString());
  }

  @Test
  void aFullQueueKeepsTheHonestFailure() {
    // 500 entries: the cap, resized in slice 4 for the reviewed bulk publish.
    for (long id = 100; id < 600; id++) {
      queue.getEntries().add(entry(id, 0, false));
    }
    when(cardDavClient.putVCard(anyString(), anyString(), anyString(), anyString(), anyString()))
                      .thenThrow(new CardDavException("connection refused"));

    // A plain CardDavException, NOT the queued subtype: promising a retry the
    // queue cannot hold would be the silent loss this slice exists to prevent.
    CardDavException thrown = assertThrows(CardDavException.class, () -> syncService.publishContact(USERNAME, CONTACT_ID));

    assertFalse(thrown instanceof CardDavPublishQueuedException);
    verify(userEmailSettingService, never()).setContactPublishQueue(any(), anyString());
  }

  @Test
  void aSuccessfulManualPublishClearsItsQueueEntry() {
    // Parked included: the contact's own publish button is the retry that
    // un-parks, and a retry that lands owes the queue nothing.
    queue.getEntries().add(entry(CONTACT_ID, 3, true));
    when(cardDavClient.putVCard(anyString(), anyString(), anyString(), anyString(), anyString()))
                      .thenReturn(new PutResult(201, "\"etag-9\"", null));

    assertNotNull(syncService.publishContact(USERNAME, CONTACT_ID));

    ArgumentCaptor<ContactPublishQueue> saved = ArgumentCaptor.forClass(ContactPublishQueue.class);
    verify(userEmailSettingService).setContactPublishQueue(saved.capture(), eq(USERNAME));
    assertTrue(saved.getValue().getEntries().isEmpty());
  }

  @Test
  void aSuccessfulRunDrainsTheQueue() {
    queue.getEntries().add(entry(CONTACT_ID, 0, false));
    when(cardDavClient.putVCard(anyString(), anyString(), anyString(), anyString(), anyString()))
                      .thenReturn(new PutResult(201, "\"etag-9\"", null));

    syncService.syncAddressBook(USERNAME);

    // The publish went out for real -- through the same doPublish the click
    // uses, book verified, creates-only -- and the entry left the queue.
    verify(cardDavClient).putVCard(anyString(), anyString(), eq("*"), eq("alice@example.com"), eq("secret"));
    verify(emailContactStorage).bindPublishedCard(eq(CONTACT_ID), eq(CONNECTOR_ID), anyString(), eq("\"etag-9\""), anyString());
    verify(userEmailSettingService).setContactPublishQueue(any(), eq(USERNAME));
    assertTrue(queue.getEntries().isEmpty());
  }

  @Test
  void aFailedRunNeverDrains() {
    // The precondition is a run that SUCCEEDED: a failed one proved the server
    // unreachable, and pushing writes at it anyway would be the hot loop.
    queue.getEntries().add(entry(CONTACT_ID, 0, false));
    when(cardDavClient.getCtag(any(), anyString(), anyString())).thenThrow(new CardDavException("unreachable"));

    syncService.syncAddressBook(USERNAME);

    verify(cardDavClient, never()).putVCard(anyString(), anyString(), anyString(), anyString(), anyString());
    verify(userEmailSettingService, never()).setContactPublishQueue(any(), anyString());
  }

  @Test
  void aBlockedBookPausesDrainingToo() {
    // BLOCKED is the sync backing off a server that keeps refusing; the queue
    // backs off with it, and the entries just wait.
    queue.getEntries().add(entry(CONTACT_ID, 0, false));
    state.setFailedAttempts(2);
    when(cardDavClient.getCtag(any(), anyString(), anyString())).thenThrow(new CardDavException("unreachable"));

    syncService.syncAddressBook(USERNAME, true);

    assertEquals(SyncStatus.BLOCKED, state.getStatus());
    verify(cardDavClient, never()).putVCard(anyString(), anyString(), anyString(), anyString(), anyString());
    assertFalse(queue.getEntries().get(0).isParked());
    assertEquals(0, queue.getEntries().get(0).getAttempts());
  }

  @Test
  void aFailingDrainCountsAgainstTheEntryNeverTheSync() {
    queue.getEntries().add(entry(CONTACT_ID, 0, false));
    when(cardDavClient.putVCard(anyString(), anyString(), anyString(), anyString(), anyString()))
                      .thenThrow(new CardDavException("boom"));

    syncService.syncAddressBook(USERNAME);

    ContactPublishQueueEntry entry = queue.getEntries().get(0);
    assertEquals(1, entry.getAttempts());
    assertFalse(entry.isParked(), "one failure is bad luck, not a verdict");
    assertEquals("boom", entry.getLastError());
    verify(userEmailSettingService).setContactPublishQueue(any(), eq(USERNAME));
    // The requirement in one assertion: the outbound failure did not feed the
    // FAILURE -> BLOCKED counter, the run stays the success it was.
    assertEquals(SyncStatus.SUCCESS, state.getStatus());
    assertEquals(0, state.getFailedAttempts());
  }

  @Test
  void retriesExhaustedParkTheEntryWithItsReason() {
    queue.getEntries().add(entry(CONTACT_ID, 2, false));
    when(cardDavClient.putVCard(anyString(), anyString(), anyString(), anyString(), anyString()))
                      .thenThrow(new CardDavException("boom"));

    syncService.syncAddressBook(USERNAME);

    ContactPublishQueueEntry entry = queue.getEntries().get(0);
    assertEquals(3, entry.getAttempts());
    assertTrue(entry.isParked(), "three refusals from a server that answers reads is not bad luck");
    assertEquals("boom", entry.getParkedReason());
  }

  @Test
  void aParkedEntryIsLeftInPeace() {
    queue.getEntries().add(entry(CONTACT_ID, 3, true));

    syncService.syncAddressBook(USERNAME);

    // Parked means parked: no attempt, no counter movement, until a person
    // retries it through the contact's own publish action.
    verify(cardDavClient, never()).putVCard(anyString(), anyString(), anyString(), anyString(), anyString());
    verify(userEmailSettingService, never()).setContactPublishQueue(any(), anyString());
  }

  @Test
  void aTransportFailureStopsTheDrainForEveryone() {
    // One bad moment must cost one attempt on one entry, not an attempt on
    // every entry in the queue.
    queue.getEntries().add(entry(CONTACT_ID, 0, false));
    queue.getEntries().add(entry(6L, 0, false));
    lenient().when(emailContactService.getContact(6L, USERNAME)).thenReturn(ownContact(6L, EmailContactSource.MANUAL));
    when(cardDavClient.putVCard(anyString(), anyString(), anyString(), anyString(), anyString()))
                      .thenThrow(new CardDavException("boom"));

    syncService.syncAddressBook(USERNAME);

    verify(cardDavClient, times(1)).putVCard(anyString(), anyString(), anyString(), anyString(), anyString());
    assertEquals(1, queue.getEntries().get(0).getAttempts());
    assertEquals(0, queue.getEntries().get(1).getAttempts());
  }

  @Test
  void anEntryRefusedForItselfIsParkedImmediately() {
    // The source became unpublishable after it was queued (the row was linked
    // to the directory, say): a NO about the entry, parked with the NO's words.
    queue.getEntries().add(entry(CONTACT_ID, 0, false));
    when(emailContactService.getContact(CONTACT_ID, USERNAME))
                            .thenReturn(ownContact(CONTACT_ID, EmailContactSource.DIRECTORY));

    syncService.syncAddressBook(USERNAME);

    ContactPublishQueueEntry entry = queue.getEntries().get(0);
    assertTrue(entry.isParked());
    assertEquals(EmailContactCardDavSyncService.PUBLISH_SOURCE_NOT_ALLOWED, entry.getParkedReason());
    verify(cardDavClient, never()).putVCard(anyString(), anyString(), anyString(), anyString(), anyString());
  }

  @Test
  void aUserLevelRefusalLeavesTheQueueUntouched() {
    // The administrator flipping the kill switch is not the entries' fault:
    // they wait as they are, and flipping it back resumes them unasked.
    System.setProperty(EmailContactCardDavSyncService.PUBLISH_ENABLED_PROPERTY, "false");
    try {
      queue.getEntries().add(entry(CONTACT_ID, 1, false));

      syncService.syncAddressBook(USERNAME);

      ContactPublishQueueEntry entry = queue.getEntries().get(0);
      assertFalse(entry.isParked());
      assertEquals(1, entry.getAttempts());
      verify(userEmailSettingService, never()).setContactPublishQueue(any(), anyString());
    } finally {
      System.clearProperty(EmailContactCardDavSyncService.PUBLISH_ENABLED_PROPERTY);
    }
  }

  @Test
  void aContactDeletedMeanwhileLeavesTheQueueQuietly() {
    // The queue must not resurrect what the user removed: the entry goes, and
    // nothing is reported anywhere -- deleting it WAS the user's answer.
    queue.getEntries().add(entry(CONTACT_ID, 0, false));
    when(emailContactService.getContact(CONTACT_ID, USERNAME)).thenReturn(null);

    syncService.syncAddressBook(USERNAME);

    assertTrue(queue.getEntries().isEmpty());
    verify(userEmailSettingService).setContactPublishQueue(any(), eq(USERNAME));
  }

  @Test
  void anEntryAlreadyPublishedElsewhereIsSatisfiedNotParked() {
    // The inbound run claimed the row by its address, or a manual retry landed
    // first: the promise is kept, whoever kept it.
    queue.getEntries().add(entry(CONTACT_ID, 0, false));
    when(emailContactService.getContact(CONTACT_ID, USERNAME))
                            .thenReturn(ownContact(CONTACT_ID, EmailContactSource.CARDDAV));

    syncService.syncAddressBook(USERNAME);

    assertTrue(queue.getEntries().isEmpty());
    verify(cardDavClient, never()).putVCard(anyString(), anyString(), anyString(), anyString(), anyString());
  }

  // -------------------------------------------------------------------------
  // The reviewed bulk enqueue -- slice 4. The rules pinned here are the
  // checklist's own, re-checked server-side: manual contacts only, the whole
  // selection or none of it, and no card ever written by the enqueue itself.
  // -------------------------------------------------------------------------

  @Test
  void aReviewedSelectionIsQueuedWholeAndDeDuplicated() {
    // Asserted with no book, so the enqueue is all that happens: with one, the
    // selection publishes immediately and an empty queue is the right answer,
    // which would say nothing about what was queued.
    withoutAnAddressBook();
    lenient().when(emailContactService.getContact(6L, USERNAME)).thenReturn(ownContact(6L, EmailContactSource.MANUAL));

    ContactPublishQueue stored = syncService.queuePublishes(USERNAME, java.util.List.of(CONTACT_ID, 6L, CONTACT_ID));

    assertEquals(2, stored.getEntries().size());
    assertTrue(stored.getEntries().stream().noneMatch(ContactPublishQueueEntry::isParked));
    verify(cardDavClient, never()).putVCard(anyString(), anyString(), anyString(), anyString(), anyString());
  }

  @Test
  void theBulkEnqueueNeedsNoBoundOrDiscoveredBook() {
    // The provider-switch flow answers the checklist right after a rebind,
    // before the new book was ever reached -- or with no book at all. The
    // queue is target-agnostic; requiring a book here would break exactly the
    // flow this exists for.
    UserEmailSetting plainImap = new UserEmailSetting();
    plainImap.setEmailConnectorId("99");
    plainImap.setCarddavEnabled(false);
    when(userEmailSettingService.getUserEmailSetting(USERNAME)).thenReturn(plainImap);
    when(userEmailSettingService.getContactSyncState(USERNAME)).thenReturn(new ContactSyncState());

    ContactPublishQueue stored = syncService.queuePublishes(USERNAME, java.util.List.of(CONTACT_ID));

    assertEquals(1, stored.getEntries().size());
  }

  @Test
  void aCollectedContactInTheSelectionRefusesTheWholeSelection() {
    // Collected rows are never OFFERED by the checklist, so one arriving here
    // is a client not showing what the server will do -- and the answer is a
    // refusal of everything, never a silent partial enqueue: what the user
    // reviewed either happens as reviewed or not at all.
    lenient().when(emailContactService.getContact(6L, USERNAME))
             .thenReturn(ownContact(6L, EmailContactSource.COLLECTED));

    IllegalArgumentException refusal =
                                     assertThrows(IllegalArgumentException.class,
                                                  () -> syncService.queuePublishes(USERNAME,
                                                                                   java.util.List.of(CONTACT_ID, 6L)));

    assertEquals(EmailContactCardDavSyncService.PUBLISH_SOURCE_NOT_ALLOWED, refusal.getMessage());
    assertTrue(queue.getEntries().isEmpty(), "all-or-nothing: the valid half was not quietly enqueued");
    verify(userEmailSettingService, never()).setContactPublishQueue(any(), anyString());
  }

  @Test
  void aDirectoryContactInTheSelectionRefusesTheWholeSelection() {
    lenient().when(emailContactService.getContact(6L, USERNAME))
             .thenReturn(ownContact(6L, EmailContactSource.DIRECTORY));

    IllegalArgumentException refusal =
                                     assertThrows(IllegalArgumentException.class,
                                                  () -> syncService.queuePublishes(USERNAME,
                                                                                   java.util.List.of(6L, CONTACT_ID)));

    assertEquals(EmailContactCardDavSyncService.PUBLISH_SOURCE_NOT_ALLOWED, refusal.getMessage());
    verify(userEmailSettingService, never()).setContactPublishQueue(any(), anyString());
  }

  @Test
  void somebodyElsesIdRefusesTheSelectionWithoutSayingWhose() {
    when(emailContactService.getContact(66L, USERNAME)).thenReturn(null);

    IllegalArgumentException refusal =
                                     assertThrows(IllegalArgumentException.class,
                                                  () -> syncService.queuePublishes(USERNAME,
                                                                                   java.util.List.of(CONTACT_ID, 66L)));

    // Absent and not-yours answer identically, as everywhere on this surface.
    assertEquals(EmailContactCardDavSyncService.PUBLISH_UNKNOWN_CONTACT, refusal.getMessage());
    verify(userEmailSettingService, never()).setContactPublishQueue(any(), anyString());
  }

  @Test
  void anAlreadyPublishedContactIsSkippedAsSatisfiedNotRefused() {
    // The race the checklist cannot avoid: a contact published between the
    // listing and the submit. The state the user asked for already holds, so
    // refusing the review over it would only teach them to stop reviewing.
    lenient().when(emailContactService.getContact(6L, USERNAME)).thenReturn(ownContact(6L, EmailContactSource.CARDDAV));

    ContactPublishQueue stored = syncService.queuePublishes(USERNAME, java.util.List.of(CONTACT_ID, 6L));

    assertEquals(1, stored.getEntries().size());
    assertEquals(CONTACT_ID, stored.getEntries().get(0).getContactId());
  }

  @Test
  void reSelectingAQueuedContactResetsItsEntry() {
    // Same rule as the single publish's fallback: asking again is the retry
    // that un-parks a parked entry and gives a tired one a fresh count.
    withoutAnAddressBook();
    queue.getEntries().add(entry(CONTACT_ID, 3, true));

    ContactPublishQueue stored = syncService.queuePublishes(USERNAME, java.util.List.of(CONTACT_ID));

    assertEquals(1, stored.getEntries().size());
    ContactPublishQueueEntry entry = stored.getEntries().get(0);
    assertFalse(entry.isParked());
    assertEquals(0, entry.getAttempts());
  }

  @Test
  void aReviewedSelectionPublishesStraightAwayWhenTheBookIsThere() {
    // The point of the change: a deliberate selection on a reachable book does
    // not wait for an unrelated sync. Waiting was right for the fallback, where
    // the click had already failed; here it left the user watching a list that
    // never changed. The card reaching the server is the evidence.
    syncService.queuePublishes(USERNAME, java.util.List.of(CONTACT_ID));

    // The card on the server is the assertion. What the queue holds afterwards
    // is the drain's business, pinned by its own tests above.
    verify(cardDavClient).putVCard(anyString(), anyString(), anyString(), anyString(), anyString());
  }

  /**
   * Puts this user on a plain IMAP account with no address book, where the
   * enqueue is the whole story because there is nothing to publish to.
   */
  private void withoutAnAddressBook() {
    UserEmailSetting plainImap = new UserEmailSetting();
    plainImap.setEmailConnectorId("99");
    plainImap.setCarddavEnabled(false);
    when(userEmailSettingService.getUserEmailSetting(USERNAME)).thenReturn(plainImap);
    lenient().when(userEmailSettingService.getContactSyncState(USERNAME)).thenReturn(new ContactSyncState());
  }

  @Test
  void aSelectionTheQueueCannotFitWholeIsRefusedWhole() {
    // Refused, not truncated: silently dropping names out of the one reviewed
    // checkpoint would be the quiet loss this whole slice exists to prevent.
    for (long id = 100; id < 600; id++) {
      queue.getEntries().add(entry(id, 0, false));
    }

    IllegalStateException refusal = assertThrows(IllegalStateException.class,
                                                 () -> syncService.queuePublishes(USERNAME,
                                                                                  java.util.List.of(CONTACT_ID)));

    assertEquals(EmailContactCardDavSyncService.PUBLISH_QUEUE_FULL, refusal.getMessage());
    verify(userEmailSettingService, never()).setContactPublishQueue(any(), anyString());
  }

  @Test
  void theBulkEnqueueHonorsTheKillSwitch() {
    System.setProperty(EmailContactCardDavSyncService.PUBLISH_ENABLED_PROPERTY, "false");
    try {
      IllegalArgumentException refusal =
                                       assertThrows(IllegalArgumentException.class,
                                                    () -> syncService.queuePublishes(USERNAME,
                                                                                     java.util.List.of(CONTACT_ID)));
      assertEquals(EmailContactCardDavSyncService.PUBLISH_DISABLED, refusal.getMessage());
    } finally {
      System.clearProperty(EmailContactCardDavSyncService.PUBLISH_ENABLED_PROPERTY);
    }
  }

  @Test
  void anEmptySelectionIsANoOp() {
    ContactPublishQueue stored = syncService.queuePublishes(USERNAME, java.util.List.of());

    assertTrue(stored.getEntries().isEmpty());
    verify(userEmailSettingService, never()).setContactPublishQueue(any(), anyString());
  }

  /**
   * A queue entry as a click during an outage leaves it.
   *
   * @param contactId the contact to publish
   * @param attempts drain attempts already spent
   * @param parked whether the queue already gave up on it
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

  /**
   * One of the caller's own visible contacts, as {@code getContact} answers it.
   *
   * @param id the contact id
   * @param source where the row came from
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
}
