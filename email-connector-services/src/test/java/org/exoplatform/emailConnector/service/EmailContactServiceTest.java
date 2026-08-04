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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.Date;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;

import org.exoplatform.commons.api.settings.SettingService;
import org.exoplatform.commons.api.settings.SettingValue;
import org.exoplatform.commons.api.settings.data.Context;
import org.exoplatform.commons.api.settings.data.Scope;
import org.exoplatform.emailConnector.model.Email;
import org.exoplatform.emailConnector.model.EmailContact;
import org.exoplatform.emailConnector.model.EmailContactSource;
import org.exoplatform.emailConnector.model.EmailRecipient;
import org.exoplatform.emailConnector.model.EmailSender;
import org.exoplatform.emailConnector.model.MailFolder;
import org.exoplatform.emailConnector.model.UserEmailSetting;
import org.exoplatform.emailConnector.storage.EmailBoxStorage;
import org.exoplatform.emailConnector.storage.EmailContactStorage;
import org.exoplatform.social.core.identity.model.Identity;
import org.exoplatform.social.core.identity.model.Profile;
import org.exoplatform.social.core.manager.IdentityManager;

@ExtendWith(MockitoExtension.class)
@SpringBootTest(classes = { EmailContactService.class })
public class EmailContactServiceTest {

  private static final String     USERNAME    = "alice";

  private static final String     OWN_ADDRESS = "alice@example.com";

  @MockBean
  private EmailContactStorage     emailContactStorage;

  @MockBean
  private EmailBoxStorage         emailBoxStorage;

  @MockBean
  private UserEmailSettingService userEmailSettingService;

  @MockBean
  private SettingService          settingService;

  @MockBean
  private IdentityManager         identityManager;

  @Autowired
  private EmailContactService     emailContactService;

  @BeforeEach
  void setUp() {
    lenient().when(userEmailSettingService.getUserEmailSetting(USERNAME)).thenReturn(userEmailSetting());
  }

  // ---------------------------------------------------------------- collection rules

  @Test
  void collectionSkipsAutoSubmittedMail() {
    givenBackfillDone();
    givenInboxMessages(inboxEmail("Robot", "robot@example.com", email -> email.setAutoSubmitted(true)));

    emailContactService.collectFromSyncedEmails(USERNAME, List.of(1L));

    verify(emailContactStorage, never()).createContact(any());
    verify(emailContactStorage, never()).updateContact(any());
  }

  @Test
  void collectionSkipsBulkMachineryWithoutAPostableList() {
    givenBackfillDone();
    givenInboxMessages(inboxEmail("Newsletter", "news@shop.example.com", email -> {
      email.setHasListUnsubscribe(true);
      email.setHasListPost(false);
    }));

    emailContactService.collectFromSyncedEmails(USERNAME, List.of(1L));

    verify(emailContactStorage, never()).createContact(any());
  }

  @Test
  void collectionKeepsAPostableListMessageAndCreditsTheOriginalAuthor() {
    givenBackfillDone();
    givenInboxMessages(inboxEmail("Jane Doe via dev-list", "dev-list@lists.example.com", email -> {
      email.setHasListId(true);
      email.setHasListPost(true);
      email.setHasListUnsubscribe(true);
      email.setOriginalSender("jane.doe@example.com");
    }));

    emailContactService.collectFromSyncedEmails(USERNAME, List.of(1L));

    ArgumentCaptor<EmailContact> created = ArgumentCaptor.forClass(EmailContact.class);
    verify(emailContactStorage).createContact(created.capture());
    assertEquals("jane.doe@example.com", created.getValue().getPrimaryEmail());
    assertEquals("Jane Doe", created.getValue().getDisplayName());
    assertEquals(EmailContactSource.COLLECTED, created.getValue().getSource());
  }

  @Test
  void collectionSkipsNoReplyAddresses() {
    givenBackfillDone();
    givenInboxMessages(inboxEmail("Shop", "no-reply@shop.example.com", email -> {
    }));

    emailContactService.collectFromSyncedEmails(USERNAME, List.of(1L));

    verify(emailContactStorage, never()).createContact(any());
  }

  @Test
  void collectionSkipsTheUsersOwnAddress() {
    givenBackfillDone();
    givenInboxMessages(inboxEmail("Alice", OWN_ADDRESS, email -> {
    }));

    emailContactService.collectFromSyncedEmails(USERNAME, List.of(1L));

    verify(emailContactStorage, never()).createContact(any());
  }

  @Test
  void collectionCreatesACollectedRowForAPlainHumanSender() {
    givenBackfillDone();
    Date receivedDate = new Date(1000000L);
    givenInboxMessages(inboxEmail("Bob Smith", "Bob@Example.org", email -> email.setReceivedDate(receivedDate)));

    emailContactService.collectFromSyncedEmails(USERNAME, List.of(1L));

    ArgumentCaptor<EmailContact> created = ArgumentCaptor.forClass(EmailContact.class);
    verify(emailContactStorage).createContact(created.capture());
    assertEquals("bob@example.org", created.getValue().getPrimaryEmail());
    assertEquals("Bob Smith", created.getValue().getDisplayName());
    assertEquals(1, created.getValue().getSeenCount());
    assertEquals(receivedDate, created.getValue().getLastSeenDate());
  }

  @Test
  void collectionIsSkippedEntirelyUntilTheBackfillRan() {
    // Before the one-time backfill, sync groups must not collect: the backfill's
    // whole-cache pass covers those very messages, and skipping here is what
    // keeps them from being counted twice.
    givenBackfillNotDone();

    emailContactService.collectFromSyncedEmails(USERNAME, List.of(1L));

    verifyNoInteractions(emailBoxStorage);
    verifyNoInteractions(emailContactStorage);
  }

  // ---------------------------------------------------------------- upsert semantics

  @Test
  void upsertBumpsCountersOnAKnownAddress() {
    givenBackfillDone();
    EmailContact existing = collectedContact(5L, "bob@example.org", "Bob Smith");
    existing.setSeenCount(3);
    existing.setLastSeenDate(new Date(1000L));
    when(emailContactStorage.getContactByAddress(USERNAME, "bob@example.org")).thenReturn(existing);
    Date newer = new Date(2000L);
    givenInboxMessages(inboxEmail("Bob Smith", "bob@example.org", email -> email.setReceivedDate(newer)));

    emailContactService.collectFromSyncedEmails(USERNAME, List.of(1L));

    ArgumentCaptor<EmailContact> updated = ArgumentCaptor.forClass(EmailContact.class);
    verify(emailContactStorage).updateContact(updated.capture());
    verify(emailContactStorage, never()).createContact(any());
    assertEquals(4, updated.getValue().getSeenCount());
    assertEquals(newer, updated.getValue().getLastSeenDate());
  }

  @Test
  void upsertBackfillsOnlyAnEmptyCollectedName() {
    givenBackfillDone();
    EmailContact anonymous = collectedContact(5L, "bob@example.org", null);
    when(emailContactStorage.getContactByAddress(USERNAME, "bob@example.org")).thenReturn(anonymous);
    givenInboxMessages(inboxEmail("Bob Smith", "bob@example.org", email -> {
    }));

    emailContactService.collectFromSyncedEmails(USERNAME, List.of(1L));

    ArgumentCaptor<EmailContact> updated = ArgumentCaptor.forClass(EmailContact.class);
    verify(emailContactStorage).updateContact(updated.capture());
    assertEquals("Bob Smith", updated.getValue().getDisplayName());
  }

  @Test
  void upsertNeverRenamesAManualContact() {
    givenBackfillDone();
    EmailContact manual = collectedContact(5L, "bob@example.org", null);
    manual.setSource(EmailContactSource.MANUAL);
    when(emailContactStorage.getContactByAddress(USERNAME, "bob@example.org")).thenReturn(manual);
    givenInboxMessages(inboxEmail("Bobby", "bob@example.org", email -> {
    }));

    emailContactService.collectFromSyncedEmails(USERNAME, List.of(1L));

    ArgumentCaptor<EmailContact> updated = ArgumentCaptor.forClass(EmailContact.class);
    verify(emailContactStorage).updateContact(updated.capture());
    // Counters move, the name a stronger source owns does not.
    assertNull(updated.getValue().getDisplayName());
    assertEquals(2, updated.getValue().getSeenCount());
  }

  @Test
  void upsertLeavesASuppressedRowCompletelyAlone() {
    givenBackfillDone();
    EmailContact suppressed = collectedContact(5L, "bob@example.org", "Bob");
    suppressed.setSuppressed(true);
    suppressed.setSeenCount(7);
    when(emailContactStorage.getContactByAddress(USERNAME, "bob@example.org")).thenReturn(suppressed);
    givenInboxMessages(inboxEmail("Bob", "bob@example.org", email -> {
    }));

    emailContactService.collectFromSyncedEmails(USERNAME, List.of(1L));

    verify(emailContactStorage, never()).updateContact(any());
    verify(emailContactStorage, never()).createContact(any());
  }

  // ---------------------------------------------------------------- sent mail and backfill

  @Test
  void sentRecipientsAreCollectedButTheOwnAddressAndNoReplyAreNot() {
    emailContactService.collectFromSentRecipients(USERNAME,
                                                  List.of(new EmailRecipient("Bob", "bob@example.org", null, false),
                                                          new EmailRecipient("Alice", OWN_ADDRESS, null, true),
                                                          new EmailRecipient("Robot", "noreply@shop.example.com", null,
                                                                             false)));

    ArgumentCaptor<EmailContact> created = ArgumentCaptor.forClass(EmailContact.class);
    verify(emailContactStorage, times(1)).createContact(created.capture());
    assertEquals("bob@example.org", created.getValue().getPrimaryEmail());
  }

  @Test
  void backfillReadsInboxSendersAndSentRecipientsOnceThenMarksItself() {
    givenBackfillNotDone();
    when(emailBoxStorage.getContactSourceEmails(USERNAME, MailFolder.INBOX, null))
                                                                                  .thenReturn(List.of(inboxEmail("Bob",
                                                                                                                 "bob@example.org",
                                                                                                                 email -> {
                                                                                                                 })));
    Email sent = new Email();
    sent.setTo(List.of(new EmailRecipient("Carol", "carol@example.org", null, false)));
    sent.setCc(List.of(new EmailRecipient("Dave", "dave@example.org", null, false)));
    when(emailBoxStorage.getContactSourceEmails(USERNAME, MailFolder.SENT, null)).thenReturn(List.of(sent));

    emailContactService.backfillFromCacheIfNeeded(USERNAME);

    verify(emailContactStorage, times(3)).createContact(any());
    verify(settingService).set(any(Context.class), any(Scope.class), eq("emailContactsBackfillDone"), any());
  }

  @Test
  void backfillDoesNotRunTwice() {
    givenBackfillDone();

    emailContactService.backfillFromCacheIfNeeded(USERNAME);

    verifyNoInteractions(emailBoxStorage);
    verify(settingService, never()).set(any(), any(), anyString(), any());
  }

  // ---------------------------------------------------------------- manual CRUD

  @Test
  void manualAddCreatesAManualRow() {
    when(emailContactStorage.getContactByAddress(USERNAME, "carol@example.org")).thenReturn(null);
    when(emailContactStorage.createContact(any())).thenAnswer(invocation -> invocation.getArgument(0));

    EmailContact created = emailContactService.createContact(manualInput("Carol", "Carol@Example.org"), USERNAME);

    assertEquals(EmailContactSource.MANUAL, created.getSource());
    assertEquals("carol@example.org", created.getPrimaryEmail());
    assertEquals(USERNAME, created.getUserId());
  }

  @Test
  void manualAddOfAVisibleAddressConflicts() {
    when(emailContactStorage.getContactByAddress(USERNAME, "bob@example.org"))
                                                                              .thenReturn(collectedContact(5L,
                                                                                                           "bob@example.org",
                                                                                                           "Bob"));

    IllegalStateException conflict =
                                   assertThrows(IllegalStateException.class,
                                                () -> emailContactService.createContact(manualInput("Bob", "bob@example.org"),
                                                                                        USERNAME));
    assertEquals(EmailContactService.CONTACT_ALREADY_EXISTS, conflict.getMessage());
  }

  @Test
  void manualAddOfASuppressedAddressRevivesTheRowInsteadOfConflicting() {
    // The user deleted a collected contact, then adds the same address by hand:
    // answering 409 about a row they cannot see would be a dead end. The
    // tombstone comes back as what they typed.
    EmailContact suppressed = collectedContact(5L, "bob@example.org", "Old Name");
    suppressed.setSuppressed(true);
    suppressed.setSeenCount(9);
    when(emailContactStorage.getContactByAddress(USERNAME, "bob@example.org")).thenReturn(suppressed);
    when(emailContactStorage.updateContact(any())).thenAnswer(invocation -> invocation.getArgument(0));

    EmailContact revived = emailContactService.createContact(manualInput("Bob Smith", "bob@example.org"), USERNAME);

    assertFalse(revived.isSuppressed());
    assertEquals(EmailContactSource.MANUAL, revived.getSource());
    assertEquals("Bob Smith", revived.getDisplayName());
    assertEquals(5L, revived.getId());
    assertEquals(9, revived.getSeenCount());
    verify(emailContactStorage, never()).createContact(any());
  }

  @Test
  void manualAddRejectsAnUnusableAddress() {
    IllegalArgumentException invalid =
                                     assertThrows(IllegalArgumentException.class,
                                                  () -> emailContactService.createContact(manualInput("X", "not-an-address"),
                                                                                          USERNAME));
    assertEquals(EmailContactService.CONTACT_INVALID_EMAIL, invalid.getMessage());
  }

  // ---------------------------------------------------------------- delete / suppress / restore

  @Test
  void deleteRemovesAManualContactForReal() {
    EmailContact manual = collectedContact(5L, "bob@example.org", "Bob");
    manual.setSource(EmailContactSource.MANUAL);
    when(emailContactStorage.getContactById(5L)).thenReturn(manual);

    EmailContact result = emailContactService.deleteOrSuppressContact(5L, USERNAME);

    verify(emailContactStorage).deleteContact(5L);
    assertFalse(result.isSuppressed());
  }

  @Test
  void deleteSuppressesACollectedContact() {
    when(emailContactStorage.getContactById(5L)).thenReturn(collectedContact(5L, "bob@example.org", "Bob"));
    when(emailContactStorage.updateContact(any())).thenAnswer(invocation -> invocation.getArgument(0));

    EmailContact result = emailContactService.deleteOrSuppressContact(5L, USERNAME);

    verify(emailContactStorage, never()).deleteContact(anyLong());
    assertTrue(result.isSuppressed());
  }

  @Test
  void deleteOfSomebodyElsesContactSaysNotFound() {
    EmailContact other = collectedContact(5L, "bob@example.org", "Bob");
    other.setUserId("mallory");
    when(emailContactStorage.getContactById(5L)).thenReturn(other);

    assertNull(emailContactService.deleteOrSuppressContact(5L, USERNAME));
    verify(emailContactStorage, never()).deleteContact(anyLong());
    verify(emailContactStorage, never()).updateContact(any());
  }

  @Test
  void restoreUnsuppressesAndIsIdempotent() {
    EmailContact suppressed = collectedContact(5L, "bob@example.org", "Bob");
    suppressed.setSuppressed(true);
    when(emailContactStorage.getContactById(5L)).thenReturn(suppressed);
    when(emailContactStorage.updateContact(any())).thenAnswer(invocation -> invocation.getArgument(0));

    EmailContact restored = emailContactService.restoreContact(5L, USERNAME);
    assertFalse(restored.isSuppressed());

    EmailContact visible = collectedContact(5L, "bob@example.org", "Bob");
    when(emailContactStorage.getContactById(5L)).thenReturn(visible);
    assertFalse(emailContactService.restoreContact(5L, USERNAME).isSuppressed());
  }

  @Test
  void editingACollectedContactKeepsItsSource() {
    when(emailContactStorage.getContactById(5L)).thenReturn(collectedContact(5L, "bob@example.org", "Bob"));
    when(emailContactStorage.updateContact(any())).thenAnswer(invocation -> invocation.getArgument(0));

    EmailContact input = manualInput("Robert Smith", "bob@example.org");
    EmailContact updated = emailContactService.updateContact(5L, input, USERNAME);

    assertEquals(EmailContactSource.COLLECTED, updated.getSource());
    assertEquals("Robert Smith", updated.getDisplayName());
  }

  @Test
  void editingACarddavContactIsRefused() {
    EmailContact carddav = collectedContact(5L, "bob@example.org", "Bob");
    carddav.setSource(EmailContactSource.CARDDAV);
    when(emailContactStorage.getContactById(5L)).thenReturn(carddav);

    IllegalArgumentException readOnly =
                                      assertThrows(IllegalArgumentException.class,
                                                   () -> emailContactService.updateContact(5L,
                                                                                           manualInput("B", "bob@example.org"),
                                                                                           USERNAME));
    assertEquals(EmailContactService.CONTACT_CARDDAV_READ_ONLY, readOnly.getMessage());
  }

  // ---------------------------------------------------------------- directory import

  @Test
  void importCreatesALinkedRowNotACopy() {
    givenDirectoryProfile("jdoe", "Jane Doe", "Jane.Doe@Example.com", "avatar-url", "profile-url");
    when(emailContactStorage.createContact(any())).thenAnswer(invocation -> invocation.getArgument(0));

    List<EmailContact> imported = emailContactService.importDirectoryContacts(List.of("jdoe"), USERNAME);

    ArgumentCaptor<EmailContact> created = ArgumentCaptor.forClass(EmailContact.class);
    verify(emailContactStorage).createContact(created.capture());
    assertEquals(EmailContactSource.DIRECTORY, created.getValue().getSource());
    assertEquals("jdoe", created.getValue().getPlatformUsername());
    assertEquals("jane.doe@example.com", created.getValue().getPrimaryEmail());
    // The stored name is only the fallback snapshot; display resolves live.
    assertEquals("Jane Doe", created.getValue().getDisplayName());
    assertEquals(1, imported.size());
    assertEquals("avatar-url", imported.get(0).getAvatarUrl());
    assertEquals("profile-url", imported.get(0).getProfileUrl());
  }

  @Test
  void importOfAnAlreadyCollectedAddressUpgradesThatRowInPlace() {
    givenDirectoryProfile("jdoe", "Jane Doe", "jane.doe@example.com", null, null);
    EmailContact collected = collectedContact(5L, "jane.doe@example.com", "jane");
    collected.setSeenCount(7);
    when(emailContactStorage.getContactByAddress(USERNAME, "jane.doe@example.com")).thenReturn(collected);
    when(emailContactStorage.updateContact(any())).thenAnswer(invocation -> invocation.getArgument(0));

    emailContactService.importDirectoryContacts(List.of("jdoe"), USERNAME);

    ArgumentCaptor<EmailContact> updated = ArgumentCaptor.forClass(EmailContact.class);
    verify(emailContactStorage).updateContact(updated.capture());
    verify(emailContactStorage, never()).createContact(any());
    assertEquals(EmailContactSource.DIRECTORY, updated.getValue().getSource());
    assertEquals("jdoe", updated.getValue().getPlatformUsername());
    assertEquals(5L, updated.getValue().getId());
    // The one row keeps its history: same id, counters untouched.
    assertEquals(7, updated.getValue().getSeenCount());
  }

  @Test
  void importOfASuppressedRowRevivesIt() {
    givenDirectoryProfile("jdoe", "Jane Doe", "jane.doe@example.com", null, null);
    EmailContact suppressed = collectedContact(5L, "jane.doe@example.com", "Jane");
    suppressed.setSuppressed(true);
    when(emailContactStorage.getContactByAddress(USERNAME, "jane.doe@example.com")).thenReturn(suppressed);
    when(emailContactStorage.updateContact(any())).thenAnswer(invocation -> invocation.getArgument(0));

    List<EmailContact> imported = emailContactService.importDirectoryContacts(List.of("jdoe"), USERNAME);

    assertFalse(imported.get(0).isSuppressed());
    assertEquals(EmailContactSource.DIRECTORY, imported.get(0).getSource());
  }

  @Test
  void importIsIdempotentOnAnAlreadyLinkedIdentity() {
    givenDirectoryProfile("jdoe", "Jane Doe", "jane.doe@example.com", null, null);
    EmailContact linked = collectedContact(5L, "jane.doe@example.com", "Jane Doe");
    linked.setSource(EmailContactSource.DIRECTORY);
    linked.setPlatformUsername("jdoe");
    when(emailContactStorage.getContactByPlatformUsername(USERNAME, "jdoe")).thenReturn(linked);

    List<EmailContact> imported = emailContactService.importDirectoryContacts(List.of("jdoe", "jdoe"), USERNAME);

    verify(emailContactStorage, never()).createContact(any());
    verify(emailContactStorage, never()).updateContact(any());
    assertEquals(1, imported.size());
  }

  @Test
  void importSkipsUnknownIdentitiesAndProfilesWithoutAnAddress() {
    when(identityManager.getOrCreateUserIdentity("ghost")).thenReturn(null);
    givenDirectoryProfile("noaddress", "No Address", null, null, null);

    List<EmailContact> imported = emailContactService.importDirectoryContacts(List.of("ghost", "noaddress"), USERNAME);

    assertTrue(imported.isEmpty());
    verify(emailContactStorage, never()).createContact(any());
  }

  @Test
  void directoryRowsRenderLiveFromTheProfile() {
    // The person was imported as "Jane Doe" and later renamed and changed
    // address: the row must show the profile of TODAY, not the import day.
    EmailContact linked = directoryContact(5L, "jane.doe@example.com", "Jane Doe", "jdoe");
    when(emailContactStorage.getContactById(5L)).thenReturn(linked);
    givenDirectoryProfile("jdoe", "Jane Doe-Martin", "jane.doe-martin@example.com", "new-avatar", "profile-url");

    EmailContact shown = emailContactService.getContact(5L, USERNAME);

    assertEquals("Jane Doe-Martin", shown.getDisplayName());
    assertEquals("jane.doe-martin@example.com", shown.getPrimaryEmail());
    assertEquals("new-avatar", shown.getAvatarUrl());
  }

  @Test
  void directoryRowsFallBackToTheSnapshotWhenTheProfileIsGone() {
    // The colleague left the company: the row shows the person as last known
    // instead of blanking out or erroring.
    EmailContact linked = directoryContact(5L, "jane.doe@example.com", "Jane Doe", "jdoe");
    when(emailContactStorage.getContactById(5L)).thenReturn(linked);
    Identity deleted = mock(Identity.class);
    when(deleted.isDeleted()).thenReturn(true);
    when(identityManager.getOrCreateUserIdentity("jdoe")).thenReturn(deleted);

    EmailContact shown = emailContactService.getContact(5L, USERNAME);

    assertEquals("Jane Doe", shown.getDisplayName());
    assertEquals("jane.doe@example.com", shown.getPrimaryEmail());
    assertNull(shown.getAvatarUrl());
  }

  @Test
  void listPagesResolveDirectoryRowsLive() {
    EmailContact linked = directoryContact(5L, "jane.doe@example.com", "Jane Doe", "jdoe");
    when(emailContactStorage.getContacts(USERNAME, null, null, 0, 100))
                                                                       .thenReturn(new org.exoplatform.emailConnector.model.EmailContactPage(List.of(linked),
                                                                                                                                             java.util.Map.of(),
                                                                                                                                             1,
                                                                                                                                             0,
                                                                                                                                             100));
    givenDirectoryProfile("jdoe", "Jane Renamed", "jane.doe@example.com", "avatar", null);

    List<EmailContact> contacts = emailContactService.getContacts(USERNAME, null, null, 0, 100).getContacts();

    assertEquals("Jane Renamed", contacts.get(0).getDisplayName());
    assertEquals("avatar", contacts.get(0).getAvatarUrl());
  }

  @Test
  void deleteRemovesADirectoryContactForReal() {
    // A directory link exists by an explicit user act, so an explicit delete
    // undoes it cleanly — no tombstone, later mail simply re-collects them.
    when(emailContactStorage.getContactById(5L)).thenReturn(directoryContact(5L, "jane.doe@example.com", "Jane Doe", "jdoe"));

    EmailContact result = emailContactService.deleteOrSuppressContact(5L, USERNAME);

    verify(emailContactStorage).deleteContact(5L);
    verify(emailContactStorage, never()).updateContact(any());
    assertFalse(result.isSuppressed());
  }

  @Test
  void editingADirectoryContactIsRefused() {
    when(emailContactStorage.getContactById(5L)).thenReturn(directoryContact(5L, "jane.doe@example.com", "Jane Doe", "jdoe"));

    IllegalArgumentException readOnly =
                                      assertThrows(IllegalArgumentException.class,
                                                   () -> emailContactService.updateContact(5L,
                                                                                           manualInput("J", "jane.doe@example.com"),
                                                                                           USERNAME));
    assertEquals(EmailContactService.CONTACT_DIRECTORY_READ_ONLY, readOnly.getMessage());
  }

  // ---------------------------------------------------------------- listing

  @Test
  void unknownSourceFilterIsRejected() {
    IllegalArgumentException invalid = assertThrows(IllegalArgumentException.class,
                                                    () -> emailContactService.getContacts(USERNAME, "bogus", null, 0, 100));
    assertEquals(EmailContactService.CONTACT_INVALID_SOURCE, invalid.getMessage());
  }

  @Test
  void sourceFiltersMapToTheStoredDiscriminators() {
    emailContactService.getContacts(USERNAME, null, null, 0, 100);
    verify(emailContactStorage).getContacts(USERNAME, null, null, 0, 100);

    emailContactService.getContacts(USERNAME, "collected", null, 0, 100);
    verify(emailContactStorage).getContacts(USERNAME, List.of(EmailContactSource.COLLECTED), null, 0, 100);

    emailContactService.getContacts(USERNAME, "addressBook", null, 0, 100);
    verify(emailContactStorage).getContacts(USERNAME,
                                            List.of(EmailContactSource.MANUAL, EmailContactSource.CARDDAV),
                                            null,
                                            0,
                                            100);
  }

  // ---------------------------------------------------------------- helpers

  /**
   * Marks the one-time backfill as already done, so group collection runs.
   */
  private void givenBackfillDone() {
    lenient().when(settingService.get(any(Context.class), any(Scope.class), eq("emailContactsBackfillDone")))
             .thenReturn((SettingValue) SettingValue.create("true"));
  }

  /**
   * Leaves the one-time backfill marker unset.
   */
  private void givenBackfillNotDone() {
    lenient().when(settingService.get(any(Context.class), any(Scope.class), eq("emailContactsBackfillDone"))).thenReturn(null);
  }

  /**
   * Stubs the light inbox rows collection reads for any UID group.
   *
   * @param emails the light cached messages
   */
  private void givenInboxMessages(Email... emails) {
    when(emailBoxStorage.getContactSourceEmails(eq(USERNAME), eq(MailFolder.INBOX), anyList())).thenReturn(List.of(emails));
  }

  /**
   * A light cached inbox message, as {@code getContactSourceEmails} shapes them.
   *
   * @param senderName the sender display name
   * @param senderAddress the sender address
   * @param customizer tweaks the flags of the message
   * @return the light message
   */
  private Email inboxEmail(String senderName, String senderAddress, java.util.function.Consumer<Email> customizer) {
    Email email = new Email();
    email.setUserId(USERNAME);
    email.setFolder(MailFolder.INBOX);
    email.setSender(new EmailSender(senderName, senderAddress, null, null));
    email.setReceivedDate(new Date());
    customizer.accept(email);
    return email;
  }

  /**
   * Mocks a resolvable platform identity with the given profile fields.
   *
   * @param username the platform username
   * @param fullName the profile's current full name
   * @param email the profile's current email, may be null
   * @param avatarUrl the profile's avatar, may be null
   * @param profileUrl the profile's page link, may be null
   */
  private void givenDirectoryProfile(String username, String fullName, String email, String avatarUrl, String profileUrl) {
    Identity identity = mock(Identity.class);
    Profile profile = mock(Profile.class);
    lenient().when(identity.isDeleted()).thenReturn(false);
    lenient().when(identity.getProfile()).thenReturn(profile);
    lenient().when(profile.getFullName()).thenReturn(fullName);
    lenient().when(profile.getEmail()).thenReturn(email);
    lenient().when(profile.getAvatarUrl()).thenReturn(avatarUrl);
    lenient().when(profile.getUrl()).thenReturn(profileUrl);
    when(identityManager.getOrCreateUserIdentity(username)).thenReturn(identity);
  }

  /**
   * An existing directory-linked row, as the import leaves them.
   *
   * @param id the row id
   * @param address the fallback address stored at import time
   * @param displayName the fallback name stored at import time
   * @param platformUsername the linked platform identity
   * @return the contact
   */
  private EmailContact directoryContact(Long id, String address, String displayName, String platformUsername) {
    EmailContact contact = collectedContact(id, address, displayName);
    contact.setSource(EmailContactSource.DIRECTORY);
    contact.setPlatformUsername(platformUsername);
    return contact;
  }

  /**
   * An existing collected row.
   *
   * @param id the row id
   * @param address the normalized address
   * @param displayName the stored name
   * @return the contact
   */
  private EmailContact collectedContact(Long id, String address, String displayName) {
    EmailContact contact = new EmailContact();
    contact.setId(id);
    contact.setUserId(USERNAME);
    contact.setSource(EmailContactSource.COLLECTED);
    contact.setPrimaryEmail(address);
    contact.setDisplayName(displayName);
    contact.setSeenCount(1);
    return contact;
  }

  /**
   * What a manual-add request body carries.
   *
   * @param displayName the typed name
   * @param address the typed address
   * @return the request contact
   */
  private EmailContact manualInput(String displayName, String address) {
    EmailContact contact = new EmailContact();
    contact.setDisplayName(displayName);
    contact.setPrimaryEmail(address);
    return contact;
  }

  /**
   * The user's mailbox binding, whose address collection must never collect.
   *
   * @return the setting
   */
  private UserEmailSetting userEmailSetting() {
    return new UserEmailSetting("1", OWN_ADDRESS, "password", null, null, 0, 0L, null, null, "connector", true);
  }
}
