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
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
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
import org.exoplatform.emailConnector.model.EmailContactSuggestion;
import org.exoplatform.emailConnector.model.EmailRecipient;
import org.exoplatform.emailConnector.model.EmailSender;
import org.exoplatform.emailConnector.model.MailFolder;
import org.exoplatform.emailConnector.model.UserEmailSetting;
import org.exoplatform.emailConnector.storage.EmailBoxStorage;
import org.exoplatform.emailConnector.storage.EmailContactStorage;
import org.exoplatform.emailConnector.utils.EmailConnectorUtils;
import org.exoplatform.social.core.identity.model.Identity;
import org.exoplatform.social.core.identity.model.Profile;
import org.exoplatform.social.core.manager.IdentityManager;
import org.exoplatform.social.core.profile.ProfileFilter;

import lombok.SneakyThrows;

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
    givenWrittenTo("someone@example.com");
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
    givenWrittenTo("someone@example.org");
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
  void collectionSkipsASenderFromAnOrganisationTheUserNeverWroteTo() {
    // The rule the header checks cannot express: a transactional sender carries no
    // list headers and is not marked auto-submitted, so the only thing separating
    // it from a person is that the user has never written back to it.
    givenBackfillDone();
    givenWrittenTo("someone@example.org");
    givenInboxMessages(inboxEmail("DocuSign EU System", "_na3@docusign.net", email -> {
    }));

    emailContactService.collectFromSyncedEmails(USERNAME, List.of(1L));

    verify(emailContactStorage, never()).createContact(any());
    verify(emailContactStorage, never()).updateContact(any());
  }

  @Test
  void aConsumerProviderAdmitsOnlyThePersonWrittenTo() {
    // Writing to one friend at gmail.com must not admit every gmail.com sender:
    // at a consumer provider the domain says nothing about the people behind it,
    // so the address itself is the only signal worth anything.
    givenBackfillDone();
    givenWrittenTo("friend@gmail.com");
    givenInboxMessages(inboxEmail("Marketing", "offers@gmail.com", email -> {
    }));

    emailContactService.collectFromSyncedEmails(USERNAME, List.of(1L));

    verify(emailContactStorage, never()).createContact(any());
  }

  @Test
  void aConsumerProviderStillCollectsThePersonWrittenTo() {
    givenBackfillDone();
    givenWrittenTo("friend@gmail.com");
    givenInboxMessages(inboxEmail("A Friend", "Friend@Gmail.com", email -> {
    }));

    emailContactService.collectFromSyncedEmails(USERNAME, List.of(1L));

    ArgumentCaptor<EmailContact> created = ArgumentCaptor.forClass(EmailContact.class);
    verify(emailContactStorage).createContact(created.capture());
    assertEquals("friend@gmail.com", created.getValue().getPrimaryEmail());
  }

  @Test
  void aCompanyDomainStillAdmitsAnyoneThere() {
    // The other half of the rule, unchanged: at a real organisation, having
    // written to one person is a good reason to keep their colleague too.
    givenBackfillDone();
    givenWrittenTo("someone@acme.com");
    givenInboxMessages(inboxEmail("Someone Else", "other.person@acme.com", email -> {
    }));

    emailContactService.collectFromSyncedEmails(USERNAME, List.of(1L));

    ArgumentCaptor<EmailContact> created = ArgumentCaptor.forClass(EmailContact.class);
    verify(emailContactStorage).createContact(created.capture());
    assertEquals("other.person@acme.com", created.getValue().getPrimaryEmail());
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
    givenWrittenTo("someone@example.org");
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
  void replayedMailDoesNotBumpTheCounterAgain() {
    // A mailbox reset re-downloads the inbox and every message is created afresh, so
    // collection runs over mail it has already counted. The counter the compose
    // autocomplete ranks on must not drift upwards every time that happens.
    givenBackfillDone();
    givenWrittenTo("someone@example.org");
    Date alreadySeen = new Date(2000L);
    EmailContact existing = collectedContact(5L, "bob@example.org", "Bob Smith");
    existing.setSeenCount(3);
    existing.setLastSeenDate(alreadySeen);
    when(emailContactStorage.getContactByAddress(USERNAME, "bob@example.org")).thenReturn(existing);
    givenInboxMessages(inboxEmail("Bob Smith", "bob@example.org", email -> email.setReceivedDate(alreadySeen)));

    emailContactService.collectFromSyncedEmails(USERNAME, List.of(1L));

    ArgumentCaptor<EmailContact> updated = ArgumentCaptor.forClass(EmailContact.class);
    verify(emailContactStorage).updateContact(updated.capture());
    assertEquals(3, updated.getValue().getSeenCount());
    assertEquals(alreadySeen, updated.getValue().getLastSeenDate());
  }

  @Test
  void upsertBackfillsOnlyAnEmptyCollectedName() {
    givenBackfillDone();
    givenWrittenTo("someone@example.org");
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
    givenWrittenTo("someone@example.org");
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

  // ---------------------------------------------------------------- contact photo

  @Test
  void savingAPhotoStoresTheUploadAndAnswersItsUrl() {
    when(emailContactStorage.getContactById(5L)).thenReturn(collectedContact(5L, "bob@example.org", "Bob"));
    when(emailContactStorage.savePhotoFileItem(null, "upload-1")).thenReturn(77L);
    when(emailContactStorage.updateContact(any())).thenAnswer(invocation -> invocation.getArgument(0));

    EmailContact input = manualInput("Bob", "bob@example.org");
    input.setPhotoUploadId("upload-1");
    EmailContact updated = emailContactService.updateContact(5L, input, USERNAME);

    assertEquals(77L, updated.getPhotoFileId());
    // The avatar the whole UI already reads now points at our own photo, so the list,
    // the card and the compose autocomplete show it without knowing this feature exists.
    assertTrue(updated.getAvatarUrl().startsWith("/email-connector/rest/contacts/5/photo?v="));
    verify(emailContactStorage, never()).deletePhotoFile(any());
  }

  @Test
  void replacingAPhotoWritesOverTheSameFile() {
    EmailContact stored = collectedContact(5L, "bob@example.org", "Bob");
    stored.setPhotoFileId(77L);
    when(emailContactStorage.getContactById(5L)).thenReturn(stored);
    when(emailContactStorage.savePhotoFileItem(77L, "upload-2")).thenReturn(77L);
    when(emailContactStorage.updateContact(any())).thenAnswer(invocation -> invocation.getArgument(0));

    EmailContact input = manualInput("Bob", "bob@example.org");
    input.setPhotoUploadId("upload-2");
    EmailContact updated = emailContactService.updateContact(5L, input, USERNAME);

    // Replaced in place, never deleted-then-written: the file id stays stable, which
    // is exactly why the read URL is versioned on the update date instead.
    verify(emailContactStorage).savePhotoFileItem(77L, "upload-2");
    verify(emailContactStorage, never()).deletePhotoFile(any());
    assertEquals(77L, updated.getPhotoFileId());
  }

  @Test
  void anEmptyUploadIdClearsThePhotoAndItsFile() {
    EmailContact stored = collectedContact(5L, "bob@example.org", "Bob");
    stored.setPhotoFileId(77L);
    when(emailContactStorage.getContactById(5L)).thenReturn(stored);
    when(emailContactStorage.updateContact(any())).thenAnswer(invocation -> invocation.getArgument(0));

    EmailContact input = manualInput("Bob", "bob@example.org");
    input.setPhotoUploadId("");
    EmailContact updated = emailContactService.updateContact(5L, input, USERNAME);

    verify(emailContactStorage).deletePhotoFile(77L);
    assertNull(updated.getPhotoFileId());
    assertNull(updated.getAvatarUrl());
  }

  @Test
  void anAbsentUploadIdLeavesTheStoredPhotoAlone() {
    // The client round-trips the contact it read, so "no photo field" has to mean
    // "I am not talking about the photo" - anything else would erase it on every
    // name edit.
    EmailContact stored = collectedContact(5L, "bob@example.org", "Bob");
    stored.setPhotoFileId(77L);
    when(emailContactStorage.getContactById(5L)).thenReturn(stored);
    when(emailContactStorage.updateContact(any())).thenAnswer(invocation -> invocation.getArgument(0));

    EmailContact updated = emailContactService.updateContact(5L, manualInput("Bob", "bob@example.org"), USERNAME);

    verify(emailContactStorage, never()).deletePhotoFile(any());
    verify(emailContactStorage, never()).savePhotoFileItem(any(), anyString());
    assertEquals(77L, updated.getPhotoFileId());
  }

  @Test
  void aDirectoryContactRefusesAPhoto() {
    // The colleague's picture is the platform profile's; a local copy would drift
    // from it silently, so the service refuses rather than trusting the form to hide
    // the button.
    EmailContact directory = directoryContact(5L, "jane.doe@example.com", "Jane Doe", "jdoe");
    when(emailContactStorage.getContactById(5L)).thenReturn(directory);

    EmailContact input = manualInput("Jane Doe", "jane.doe@example.com");
    input.setPhotoUploadId("upload-1");
    IllegalArgumentException refused =
                                     assertThrows(IllegalArgumentException.class,
                                                  () -> emailContactService.updateContact(5L, input, USERNAME));

    assertEquals(EmailContactService.CONTACT_DIRECTORY_READ_ONLY, refused.getMessage());
    verify(emailContactStorage, never()).savePhotoFileItem(any(), anyString());
  }

  @Test
  void aDirectoryRowNeverAnswersAStoredPhotoUrl() {
    // Defence in depth for a row that somehow carries a file id: the live profile
    // avatar stays the answer, so an imported colleague can never show a stale copy.
    EmailContact directory = directoryContact(5L, "jane.doe@example.com", "Jane Doe", "jdoe");
    directory.setPhotoFileId(77L);
    givenDirectoryProfile("jdoe", "Jane Doe", "jane.doe@example.com", "profile-avatar", "profile-url");
    when(emailContactStorage.getContactById(5L)).thenReturn(directory);

    assertEquals("profile-avatar", emailContactService.getContact(5L, USERNAME).getAvatarUrl());
  }

  @Test
  void aPhotoOfSomebodyElsesContactIsNotReadable() {
    EmailContact other = collectedContact(5L, "bob@example.org", "Bob");
    other.setUserId("mallory");
    other.setPhotoFileId(77L);
    when(emailContactStorage.getContactById(5L)).thenReturn(other);

    assertNull(emailContactService.getContactPhoto(5L, USERNAME));
    verify(emailContactStorage, never()).getPhotoFileItem(any());
  }

  @Test
  void hardDeletingAContactDisposesOfItsPhoto() {
    EmailContact manual = collectedContact(5L, "bob@example.org", "Bob");
    manual.setSource(EmailContactSource.MANUAL);
    manual.setPhotoFileId(77L);
    when(emailContactStorage.getContactById(5L)).thenReturn(manual);

    emailContactService.deleteOrSuppressContact(5L, USERNAME);

    verify(emailContactStorage).deletePhotoFile(77L);
    verify(emailContactStorage).deleteContact(5L);
  }

  @Test
  void aStoredPhotoOutranksAProfileThatMerelySharesTheAddress() {
    // The user chose this picture by hand; a platform profile that happens to carry
    // the same address does not get to override it.
    EmailContact manual = collectedContact(5L, "bob@example.org", "Bob");
    manual.setSource(EmailContactSource.MANUAL);
    manual.setPhotoFileId(77L);
    manual.setUpdatedDate(new Date(1700000000000L));
    when(emailContactStorage.getContactById(5L)).thenReturn(manual);
    Profile matched = mock(Profile.class);
    lenient().when(matched.getAvatarUrl()).thenReturn("profile-avatar");
    lenient().when(matched.getUrl()).thenReturn("profile-url");

    EmailContact read;
    try (MockedStatic<EmailConnectorUtils> utils = mockStatic(EmailConnectorUtils.class)) {
      utils.when(() -> EmailConnectorUtils.getUserProfileByEmail("bob@example.org")).thenReturn(matched);
      read = emailContactService.getContact(5L, USERNAME);
    }

    assertEquals("/email-connector/rest/contacts/5/photo?v=1700000000000", read.getAvatarUrl());
    // The profile link still resolves: only the picture is the user's to override.
    assertEquals("profile-url", read.getProfileUrl());
  }

  // ---------------------------------------------------------------- directory import

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
  void savingTheFormDoesNotEraseACollectedName() {
    // The form has no display-name field, so a collected contact -- whose name came
    // from the mail header as one string -- saves both name halves empty. That must
    // not cost it its name, or setting a picture would rename the person to their
    // own address.
    EmailContact stored = collectedContact(5L, "amelie@tech.rocks", "Amelie Deguerry");
    when(emailContactStorage.getContactById(5L)).thenReturn(stored);
    EmailContact input = new EmailContact();
    input.setPrimaryEmail("amelie@tech.rocks");

    emailContactService.updateContact(5L, input, USERNAME);

    ArgumentCaptor<EmailContact> updated = ArgumentCaptor.forClass(EmailContact.class);
    verify(emailContactStorage).updateContact(updated.capture());
    assertEquals("Amelie Deguerry", updated.getValue().getDisplayName());
  }

  @Test
  void namingAContactExplicitlyOverridesTheCollectedName() {
    // The other half of the rule: filling the form's name fields IS the user saying
    // what this person is called, and it must win over what the mail header said.
    EmailContact stored = collectedContact(5L, "amelie@tech.rocks", "Amelie Deguerry");
    when(emailContactStorage.getContactById(5L)).thenReturn(stored);
    EmailContact input = new EmailContact();
    input.setPrimaryEmail("amelie@tech.rocks");
    input.setGivenName("Amelie");
    input.setFamilyName("Dupont");

    emailContactService.updateContact(5L, input, USERNAME);

    ArgumentCaptor<EmailContact> updated = ArgumentCaptor.forClass(EmailContact.class);
    verify(emailContactStorage).updateContact(updated.capture());
    assertEquals("Amelie Dupont", updated.getValue().getDisplayName());
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

    // One filter per stored source. A contact typed by hand is neither collected
    // from mail nor owned by a provider's book, and it used to be filed with the
    // address book -- which read as "from my address book" for a contact no
    // address book had heard of.
    emailContactService.getContacts(USERNAME, "collected", null, 0, 100);
    verify(emailContactStorage).getContacts(USERNAME, List.of(EmailContactSource.COLLECTED), null, 0, 100);

    emailContactService.getContacts(USERNAME, "manual", null, 0, 100);
    verify(emailContactStorage).getContacts(USERNAME, List.of(EmailContactSource.MANUAL), null, 0, 100);

    emailContactService.getContacts(USERNAME, "addressBook", null, 0, 100);
    verify(emailContactStorage).getContacts(USERNAME, List.of(EmailContactSource.CARDDAV), null, 0, 100);
  }

  // ---------------------------------------------------------------- recipient suggestions

  @Test
  void suggestionsKeepTheStoreOrderAndAppendTheDirectoryAfterIt() {
    // The store's own ranking (frequency, then recency) is the storage's job and is
    // asserted against a real database in EmailContactDAOTest; what this asserts is
    // the merge: every store row first, directory-only matches after them.
    givenStoreSuggestions("bob", collectedContact(1L, "bob@example.org", "Bob Smith"));
    givenDirectorySearch("bob", "bobby");
    givenDirectoryProfile("bobby", "Bobby Tables", "bobby@example.com", "bobby-avatar", "/bobby");

    List<EmailContactSuggestion> suggestions = suggest("bob", 10);

    assertEquals(List.of("bob@example.org", "bobby@example.com"),
                 suggestions.stream().map(EmailContactSuggestion::getAddress).toList());
    assertEquals("Bobby Tables", suggestions.get(1).getDisplayName());
    assertTrue(suggestions.get(1).isPlatformUser());
    assertEquals("/bobby", suggestions.get(1).getProfileUrl());
  }

  @Test
  void aColleagueWhoIsAlsoACollectedContactAppearsOnce() {
    // The same person reached from both halves: one chip, the store's row, with the
    // platform profile supplying the live avatar and profile link.
    givenStoreSuggestions("jane", collectedContact(1L, "jane@example.com", "Jane"));
    givenDirectorySearch("jane", "jane");
    givenDirectoryProfile("jane", "Jane Doe", "jane@example.com", "jane-avatar", "/jane");

    Profile matched = profile("Jane Doe", "jane@example.com", "jane-avatar", "/jane");
    List<EmailContactSuggestion> suggestions;
    try (MockedStatic<EmailConnectorUtils> utils = mockStatic(EmailConnectorUtils.class)) {
      utils.when(() -> EmailConnectorUtils.getUserProfileByEmail("jane@example.com")).thenReturn(matched);
      suggestions = emailContactService.suggestRecipients(USERNAME, "jane", 10);
    }

    assertEquals(1, suggestions.size());
    assertEquals("jane@example.com", suggestions.get(0).getAddress());
    assertEquals("jane-avatar", suggestions.get(0).getAvatarUrl());
    assertTrue(suggestions.get(0).isPlatformUser());
  }

  @Test
  void aDirectoryRowWhoseProfileAddressMovedStillAppearsOnce() {
    // Enrichment replaces a DIRECTORY row's stored address with the live profile
    // one. Keying the merge before that happens files the row under an address
    // the emitted suggestion no longer carries, so the directory half looks the
    // same person up by their live address, misses, and adds them a second time.
    givenStoreSuggestions("jane", directoryContact(1L, "old@example.com", "Jane", "jane"));
    givenDirectorySearch("jane", "jane");
    givenDirectoryProfile("jane", "Jane Doe", "new@example.com", "jane-avatar", "/jane");

    List<EmailContactSuggestion> suggestions = suggest("jane", 10);

    assertEquals(List.of("new@example.com"), suggestions.stream().map(EmailContactSuggestion::getAddress).toList());
    assertEquals("Jane Doe", suggestions.get(0).getDisplayName());
  }

  @Test
  void aStoreRowWithNoPlatformProfileIsNotAPlatformUser() {
    givenStoreSuggestions("bob", collectedContact(1L, "bob@outside.org", "Bob"));

    List<EmailContactSuggestion> suggestions = suggest("bob", 10);

    assertEquals(1, suggestions.size());
    assertFalse(suggestions.get(0).isPlatformUser());
    assertNull(suggestions.get(0).getProfileUrl());
  }

  @Test
  void aBlankTermAnswersTheTopOfTheStoreAndNeverTheDirectory() {
    // Opening the field offers the people already written to; a blank term must not
    // become a way to dump the company address book.
    givenStoreSuggestions(null, collectedContact(1L, "bob@example.org", "Bob"));

    List<EmailContactSuggestion> suggestions = suggest("  ", 10);

    assertEquals(1, suggestions.size());
    verifyNoInteractions(identityManager);
  }

  @Test
  void theSuggestionWindowIsDefaultedAndCapped() {
    givenStoreSuggestions(null);

    suggest(null, 0);
    verify(emailContactStorage).suggestContacts(USERNAME, null, EmailContactService.SUGGEST_DEFAULT_LIMIT);

    suggest(null, 1000);
    verify(emailContactStorage).suggestContacts(USERNAME, null, EmailContactService.SUGGEST_MAX_LIMIT);
  }

  @Test
  void theDirectoryIsNotQueriedWhenTheStoreAlreadyFilledTheWindow() {
    givenStoreSuggestions("bob", collectedContact(1L, "bob@example.org", "Bob"));

    suggest("bob", 1);

    verifyNoInteractions(identityManager);
  }

  @Test
  void theActingUserIsNeverSuggestedToThemselves() {
    givenStoreSuggestions("ali");
    givenDirectorySearch("ali", USERNAME);

    assertTrue(suggest("ali", 10).isEmpty());
  }

  @Test
  void aFailingDirectoryStillAnswersTheStore() throws Exception {
    // A recipient field that errors out mid-typing is worse than one offering
    // fewer names.
    givenStoreSuggestions("bob", collectedContact(1L, "bob@example.org", "Bob"));
    when(identityManager.getIdentitiesByProfileFilter(anyString(), any(ProfileFilter.class), anyLong(), anyLong()))
                                                                                                                  .thenThrow(new IllegalStateException("directory down"));

    assertEquals(1, suggest("bob", 10).size());
  }

  // ---------------------------------------------------------------- helpers

  /**
   * Answers the suggestions with the address→profile lookup stubbed away, so a
   * test that is not about platform enrichment does not have to mock it.
   *
   * @param query what the user typed
   * @param limit the requested window
   * @return the suggestions
   */
  private List<EmailContactSuggestion> suggest(String query, int limit) {
    try (MockedStatic<EmailConnectorUtils> utils = mockStatic(EmailConnectorUtils.class)) {
      utils.when(() -> EmailConnectorUtils.getUserProfileByEmail(anyString())).thenReturn(null);
      return emailContactService.suggestRecipients(USERNAME, query, limit);
    }
  }

  /**
   * Stubs the store's half of the suggestion merge, for whatever window the
   * service ends up asking for.
   *
   * @param term the term the service normalizes the query to
   * @param contacts the ranked rows the storage answers
   */
  private void givenStoreSuggestions(String term, EmailContact... contacts) {
    lenient().when(emailContactStorage.suggestContacts(eq(USERNAME), term == null ? any() : eq(term), anyInt()))
             .thenReturn(List.of(contacts));
  }

  /**
   * Stubs the platform directory's half of the suggestion merge.
   *
   * @param term the searched text
   * @param platformUsernames the identities the directory answers
   */
  @SneakyThrows
  private void givenDirectorySearch(String term, String... platformUsernames) {
    List<Identity> identities = new ArrayList<>();
    for (String platformUsername : platformUsernames) {
      Identity identity = mock(Identity.class);
      lenient().when(identity.getRemoteId()).thenReturn(platformUsername);
      identities.add(identity);
    }
    when(identityManager.getIdentitiesByProfileFilter(anyString(),
                                                      argThat(filter -> filter != null && term.equals(filter.getName())),
                                                      anyLong(),
                                                      anyLong())).thenReturn(identities);
  }

  /**
   * A mocked platform profile.
   *
   * @param fullName the profile's full name
   * @param email the profile's email
   * @param avatarUrl the profile's avatar
   * @param profileUrl the profile's page link
   * @return the profile
   */
  private Profile profile(String fullName, String email, String avatarUrl, String profileUrl) {
    Profile profile = mock(Profile.class);
    lenient().when(profile.getFullName()).thenReturn(fullName);
    lenient().when(profile.getEmail()).thenReturn(email);
    lenient().when(profile.getAvatarUrl()).thenReturn(avatarUrl);
    lenient().when(profile.getUrl()).thenReturn(profileUrl);
    return profile;
  }

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
   * Stubs the sent side of collection: the people the user has written to. Their
   * domains are what opens an inbox sender to collection, so a test that expects a
   * sender to be collected has to say that the user has written to them.
   *
   * @param addresses the addresses the user has written to
   */
  private void givenWrittenTo(String... addresses) {
    Email sent = new Email();
    List<EmailRecipient> recipients = new ArrayList<>();
    for (String address : addresses) {
      recipients.add(new EmailRecipient(null, address, null, false));
    }
    sent.setTo(recipients);
    when(emailBoxStorage.getContactSourceEmails(USERNAME, MailFolder.SENT, null)).thenReturn(List.of(sent));
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
