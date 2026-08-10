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
import static org.junit.jupiter.api.Assertions.assertNotNull;
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
import static org.mockito.Mockito.doThrow;
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
import java.util.Set;

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
import org.exoplatform.emailConnector.model.EmailContactPage;
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
import org.exoplatform.social.core.profileproperty.ProfilePropertyService;
import org.exoplatform.social.core.profileproperty.model.ProfilePropertySetting;

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

  @MockBean
  private EmailContactFavoriteService emailContactFavoriteService;

  @MockBean
  private ProfilePropertyService  profilePropertyService;

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

  @Test
  void manualAddNormalizesTheCardFieldsItCarries() {
    when(emailContactStorage.getContactByAddress(USERNAME, "carol@example.org")).thenReturn(null);
    when(emailContactStorage.createContact(any())).thenAnswer(invocation -> invocation.getArgument(0));
    EmailContact input = manualInput("Carol", "carol@example.org");
    // The spellings a person actually types: a compact vCard date, an address
    // with padding, a note with trailing whitespace.
    input.setBirthday("19850412");
    input.setPostalAddress(new org.exoplatform.emailConnector.model.PostalAddress(" 12 rue de la Paix ",
                                                                                  "Paris",
                                                                                  null,
                                                                                  "75002",
                                                                                  "France"));
    input.setNote("  Met at FOSDEM.  ");
    input.setWebsite(" https://carol.example ");

    EmailContact created = emailContactService.createContact(input, USERNAME);

    assertEquals("1985-04-12", created.getBirthday());
    assertEquals("12 rue de la Paix", created.getPostalAddress().street());
    assertEquals("Met at FOSDEM.", created.getNote());
    assertEquals("https://carol.example", created.getWebsite());
  }

  @Test
  void aYearlessBirthdayIsHeldAsTyped() {
    when(emailContactStorage.getContactByAddress(USERNAME, "carol@example.org")).thenReturn(null);
    when(emailContactStorage.createContact(any())).thenAnswer(invocation -> invocation.getArgument(0));
    EmailContact input = manualInput("Carol", "carol@example.org");
    input.setBirthday("12-31");

    assertEquals("--12-31", emailContactService.createContact(input, USERNAME).getBirthday());
  }

  @Test
  void aBirthdayThatIsNotADateIsRefusedNotRepaired() {
    when(emailContactStorage.getContactByAddress(USERNAME, "carol@example.org")).thenReturn(null);
    EmailContact input = manualInput("Carol", "carol@example.org");
    input.setBirthday("next tuesday");

    IllegalArgumentException invalid = assertThrows(IllegalArgumentException.class,
                                                    () -> emailContactService.createContact(input, USERNAME));

    assertEquals(EmailContactService.CONTACT_INVALID_BIRTHDAY, invalid.getMessage());
  }

  @Test
  void anAddressOfOnlyBlanksIsRemovedNotStored() {
    // Five emptied form fields mean "no address", not an address of blanks.
    when(emailContactStorage.getContactByAddress(USERNAME, "carol@example.org")).thenReturn(null);
    when(emailContactStorage.createContact(any())).thenAnswer(invocation -> invocation.getArgument(0));
    EmailContact input = manualInput("Carol", "carol@example.org");
    input.setPostalAddress(new org.exoplatform.emailConnector.model.PostalAddress(" ", "", null, "  ", ""));

    assertNull(emailContactService.createContact(input, USERNAME).getPostalAddress());
  }

  // ---------------------------------------------------------------- several addresses

  @Test
  void manualAddKeepsEverySecondaryAddress() {
    when(emailContactStorage.createContact(any())).thenAnswer(invocation -> invocation.getArgument(0));
    EmailContact input = manualInput("Carol", "carol@example.org");
    // As typed: padding, case, and one address twice — the store files the
    // normalized, de-duplicated set.
    input.setSecondaryEmails(List.of(" Carol@Work.example ", "carol@home.example", "carol@work.example"));

    EmailContact created = emailContactService.createContact(input, USERNAME);

    assertEquals(List.of("carol@work.example", "carol@home.example"), created.getSecondaryEmails());
  }

  @Test
  void manualAddRejectsAnUnusableSecondaryAddress() {
    EmailContact input = manualInput("Carol", "carol@example.org");
    input.setSecondaryEmails(List.of("not-an-address"));

    IllegalArgumentException invalid = assertThrows(IllegalArgumentException.class,
                                                    () -> emailContactService.createContact(input, USERNAME));

    assertEquals(EmailContactService.CONTACT_INVALID_EMAIL, invalid.getMessage());
    verify(emailContactStorage, never()).createContact(any());
  }

  @Test
  void aSecondaryDuplicatingThePrimaryIsFiledOnce() {
    when(emailContactStorage.createContact(any())).thenAnswer(invocation -> invocation.getArgument(0));
    EmailContact input = manualInput("Carol", "carol@example.org");
    input.setSecondaryEmails(List.of("Carol@Example.org"));

    EmailContact created = emailContactService.createContact(input, USERNAME);

    assertEquals("carol@example.org", created.getPrimaryEmail());
    assertNull(created.getSecondaryEmails());
  }

  @Test
  void aSecondaryHeldByAnotherVisibleContactConflicts() {
    // The same rule as the primary: an address names one person per store.
    when(emailContactStorage.getContactByAddress(USERNAME, "bob@example.org"))
                                                                              .thenReturn(collectedContact(5L,
                                                                                                           "bob@example.org",
                                                                                                           "Bob"));
    EmailContact input = manualInput("Carol", "carol@example.org");
    input.setSecondaryEmails(List.of("bob@example.org"));

    IllegalStateException conflict = assertThrows(IllegalStateException.class,
                                                  () -> emailContactService.createContact(input, USERNAME));

    assertEquals(EmailContactService.CONTACT_ALREADY_EXISTS, conflict.getMessage());
    verify(emailContactStorage, never()).createContact(any());
  }

  @Test
  void aSecondaryHeldByATombstoneAbsorbsItInsteadOfRevivingIt() {
    // The user removed Bob; adding his address as Carol's second one must not
    // bring Bob back — the tombstone dies, the address changes hands, exactly
    // as a primary change has always absorbed one.
    EmailContact tombstone = collectedContact(5L, "bob@example.org", "Bob");
    tombstone.setSuppressed(true);
    when(emailContactStorage.getContactByAddress(USERNAME, "bob@example.org")).thenReturn(tombstone);
    when(emailContactStorage.createContact(any())).thenAnswer(invocation -> invocation.getArgument(0));
    EmailContact input = manualInput("Carol", "carol@example.org");
    input.setSecondaryEmails(List.of("bob@example.org"));

    EmailContact created = emailContactService.createContact(input, USERNAME);

    verify(emailContactStorage).deleteContact(5L);
    verify(emailContactFavoriteService).removeFavorite(5L, USERNAME);
    assertEquals(List.of("bob@example.org"), created.getSecondaryEmails());
  }

  @Test
  void aRevivedTombstoneAdoptsTheTypedPrimary() {
    // The tombstone is found by ANY of its addresses, so the typed one may be
    // its secondary: the revival must file the row under what the user typed,
    // demoting the tombstone's old primary rather than losing either address.
    EmailContact tombstone = collectedContact(5L, "bob@example.org", "Bob");
    tombstone.setSecondaryEmails(List.of("bob@home.example"));
    tombstone.setSuppressed(true);
    when(emailContactStorage.getContactByAddress(USERNAME, "bob@home.example")).thenReturn(tombstone);
    when(emailContactStorage.updateContact(any())).thenAnswer(invocation -> invocation.getArgument(0));

    EmailContact revived = emailContactService.createContact(manualInput("Bob", "bob@home.example"), USERNAME);

    assertEquals("bob@home.example", revived.getPrimaryEmail());
    assertEquals(List.of("bob@example.org"), revived.getSecondaryEmails());
    verify(emailContactStorage, never()).createContact(any());
  }

  @Test
  void updateReplacesTheSecondariesTheRequestCarries() {
    // A present list is the authoritative set: the form sends every address row
    // it shows, so an address missing from it was removed on purpose.
    EmailContact stored = collectedContact(5L, "bob@example.org", "Bob");
    stored.setSecondaryEmails(List.of("old@example.org"));
    when(emailContactStorage.getContactById(5L)).thenReturn(stored);
    when(emailContactStorage.updateContact(any())).thenAnswer(invocation -> invocation.getArgument(0));
    EmailContact input = manualInput("Bob", "bob@example.org");
    input.setSecondaryEmails(List.of("new@example.org"));

    EmailContact updated = emailContactService.updateContact(5L, input, USERNAME);

    assertEquals(List.of("new@example.org"), updated.getSecondaryEmails());
  }

  @Test
  void updateSayingNothingAboutSecondariesKeepsThem() {
    // The one behaviour protecting stored data from clients that never heard of
    // secondary addresses: silence means "leave them alone", never "drop them".
    EmailContact stored = collectedContact(5L, "bob@example.org", "Bob");
    stored.setSecondaryEmails(List.of("bob@home.example"));
    when(emailContactStorage.getContactById(5L)).thenReturn(stored);
    when(emailContactStorage.updateContact(any())).thenAnswer(invocation -> invocation.getArgument(0));

    EmailContact updated = emailContactService.updateContact(5L, manualInput("Bob", "bob@example.org"), USERNAME);

    assertEquals(List.of("bob@home.example"), updated.getSecondaryEmails());
  }

  @Test
  void changingThePrimaryDemotesTheOldAddressWhenTheRequestSaysNothing() {
    // The sharpest bug this slice fixes: moving the primary used to DELETE the
    // old address row, leaving the person unreachable at the address the
    // mailbox knows them by. Silence about secondaries now demotes it instead.
    EmailContact stored = collectedContact(5L, "bob@example.org", "Bob");
    when(emailContactStorage.getContactById(5L)).thenReturn(stored);
    when(emailContactStorage.updateContact(any())).thenAnswer(invocation -> invocation.getArgument(0));

    EmailContact updated = emailContactService.updateContact(5L, manualInput("Bob", "bob@work.example"), USERNAME);

    assertEquals("bob@work.example", updated.getPrimaryEmail());
    assertEquals(List.of("bob@example.org"), updated.getSecondaryEmails());
  }

  @Test
  void removingTheOldPrimaryTakesAnExplicitEmptyList() {
    // Dropping an address stays possible — it just has to be said: an empty
    // list is a removal, only silence demotes.
    EmailContact stored = collectedContact(5L, "bob@example.org", "Bob");
    stored.setSecondaryEmails(List.of("bob@home.example"));
    when(emailContactStorage.getContactById(5L)).thenReturn(stored);
    when(emailContactStorage.updateContact(any())).thenAnswer(invocation -> invocation.getArgument(0));
    EmailContact input = manualInput("Bob", "bob@work.example");
    input.setSecondaryEmails(List.of());

    EmailContact updated = emailContactService.updateContact(5L, input, USERNAME);

    assertEquals("bob@work.example", updated.getPrimaryEmail());
    assertNull(updated.getSecondaryEmails());
  }

  @Test
  void swappingPrimaryAndSecondaryKeepsBothAddresses() {
    EmailContact stored = collectedContact(5L, "bob@example.org", "Bob");
    stored.setSecondaryEmails(List.of("bob@home.example"));
    when(emailContactStorage.getContactById(5L)).thenReturn(stored);
    // Both addresses resolve to the row being saved: its own addresses are
    // never a conflict, so the swap needs no absorption and deletes nothing.
    lenient().when(emailContactStorage.getContactByAddress(USERNAME, "bob@example.org")).thenReturn(stored);
    lenient().when(emailContactStorage.getContactByAddress(USERNAME, "bob@home.example")).thenReturn(stored);
    when(emailContactStorage.updateContact(any())).thenAnswer(invocation -> invocation.getArgument(0));
    EmailContact input = manualInput("Bob", "bob@home.example");
    input.setSecondaryEmails(List.of("bob@example.org"));

    EmailContact updated = emailContactService.updateContact(5L, input, USERNAME);

    assertEquals("bob@home.example", updated.getPrimaryEmail());
    assertEquals(List.of("bob@example.org"), updated.getSecondaryEmails());
    verify(emailContactStorage, never()).deleteContact(anyLong());
  }

  @Test
  void updateConflictsWhenASecondaryBelongsToAnotherVisibleContact() {
    when(emailContactStorage.getContactById(5L)).thenReturn(collectedContact(5L, "bob@example.org", "Bob"));
    when(emailContactStorage.getContactByAddress(USERNAME, "alice@example.org"))
                                                                                .thenReturn(collectedContact(9L,
                                                                                                             "alice@example.org",
                                                                                                             "Alice"));
    EmailContact input = manualInput("Bob", "bob@example.org");
    input.setSecondaryEmails(List.of("alice@example.org"));

    IllegalStateException conflict = assertThrows(IllegalStateException.class,
                                                  () -> emailContactService.updateContact(5L, input, USERNAME));

    assertEquals(EmailContactService.CONTACT_ALREADY_EXISTS, conflict.getMessage());
    verify(emailContactStorage, never()).updateContact(any());
  }

  @Test
  void directoryAnnotationsNeverTouchTheAddresses() {
    // A directory row's addresses are the profile's business: whatever address
    // set a payload carries, the stored one survives the save.
    EmailContact directory = directoryContact(5L, "jane.doe@example.com", "Jane Doe", "jdoe");
    directory.setSecondaryEmails(List.of("jane@home.example"));
    when(emailContactStorage.getContactById(5L)).thenReturn(directory);
    when(emailContactStorage.updateContact(any())).thenAnswer(invocation -> invocation.getArgument(0));
    EmailContact input = manualInput("Somebody Else", "other@example.org");
    input.setSecondaryEmails(List.of("sneaked@example.org"));
    input.setNote("Met at FOSDEM.");

    EmailContact updated = emailContactService.updateContact(5L, input, USERNAME);

    assertEquals("jane.doe@example.com", updated.getPrimaryEmail());
    assertEquals(List.of("jane@home.example"), updated.getSecondaryEmails());
    assertEquals("Met at FOSDEM.", updated.getNote());
  }

  // ---------------------------------------------------------------- phones

  @Test
  void updateSayingNothingAboutPhonesKeepsThem() {
    // THE data-loss bug this slice fixes: applyAuthoredFields used to copy the
    // payload's phones raw, so a body that said nothing about them -- or only
    // knew the first one -- silently deleted the rest. Silence now keeps them.
    EmailContact stored = collectedContact(5L, "bob@example.org", "Bob");
    stored.setPhones(List.of("cell,+33 6 12 34 56 78", "work,+33 1 23 45 67 89"));
    when(emailContactStorage.getContactById(5L)).thenReturn(stored);
    when(emailContactStorage.updateContact(any())).thenAnswer(invocation -> invocation.getArgument(0));

    EmailContact updated = emailContactService.updateContact(5L, manualInput("Bob", "bob@example.org"), USERNAME);

    assertEquals(List.of("cell,+33 6 12 34 56 78", "work,+33 1 23 45 67 89"), updated.getPhones());
  }

  @Test
  void updateReplacesThePhonesTheRequestCarries() {
    // A present list is the authoritative set, exactly as for the secondary
    // addresses: the form sends every row it shows, so a number missing from
    // the list was removed on purpose.
    EmailContact stored = collectedContact(5L, "bob@example.org", "Bob");
    stored.setPhones(List.of("cell,+33 6 12 34 56 78", "work,+33 1 23 45 67 89"));
    when(emailContactStorage.getContactById(5L)).thenReturn(stored);
    when(emailContactStorage.updateContact(any())).thenAnswer(invocation -> invocation.getArgument(0));
    EmailContact input = manualInput("Bob", "bob@example.org");
    input.setPhones(List.of("home,+33 2 11 22 33 44"));

    EmailContact updated = emailContactService.updateContact(5L, input, USERNAME);

    assertEquals(List.of("home,+33 2 11 22 33 44"), updated.getPhones());
  }

  @Test
  void removingEveryPhoneTakesAnExplicitEmptyList() {
    // Clearing stays possible -- it just has to be said. Only silence keeps.
    EmailContact stored = collectedContact(5L, "bob@example.org", "Bob");
    stored.setPhones(List.of("cell,+33 6 12 34 56 78"));
    when(emailContactStorage.getContactById(5L)).thenReturn(stored);
    when(emailContactStorage.updateContact(any())).thenAnswer(invocation -> invocation.getArgument(0));
    EmailContact input = manualInput("Bob", "bob@example.org");
    input.setPhones(List.of());

    EmailContact updated = emailContactService.updateContact(5L, input, USERNAME);

    assertNull(updated.getPhones());
  }

  @Test
  void phonesDedupeBySameDigitsAndTheFirstSpellingWins() {
    // Normalization happens at comparison time, never on the stored value: the
    // first spelling of a number is kept exactly as typed, and a second way of
    // writing the same digits -- typed or bare -- is recognized and dropped.
    when(emailContactStorage.getContactByAddress(USERNAME, "bob@example.org")).thenReturn(null);
    when(emailContactStorage.createContact(any())).thenAnswer(invocation -> invocation.getArgument(0));
    EmailContact input = manualInput("Bob", "bob@example.org");
    input.setPhones(List.of("+33 6 12 34 56 78", "cell,+33612345678", "  ", "work,+33 1 23 45 67 89"));

    EmailContact created = emailContactService.createContact(input, USERNAME);

    assertEquals(List.of("+33 6 12 34 56 78", "work,+33 1 23 45 67 89"), created.getPhones());
  }

  @Test
  void anUnknownPhoneTypePrefixStaysPartOfTheNumber() {
    // Only the vocabulary the exporter can write back counts as a type; any
    // other prefix is simply part of what the user typed, kept messy rather
    // than mangled.
    when(emailContactStorage.getContactByAddress(USERNAME, "bob@example.org")).thenReturn(null);
    when(emailContactStorage.createContact(any())).thenAnswer(invocation -> invocation.getArgument(0));
    EmailContact input = manualInput("Bob", "bob@example.org");
    input.setPhones(List.of("office,+33 1 23 45 67 89"));

    EmailContact created = emailContactService.createContact(input, USERNAME);

    assertEquals(List.of("office,+33 1 23 45 67 89"), created.getPhones());
  }

  @Test
  void aRevivedTombstoneKeepsItsPhonesWhenTheCreateSaysNothing() {
    // The revival adopts what the user typed, but silence about the phones
    // must not cost the tombstone's numbers -- same contract as everywhere.
    EmailContact tombstone = collectedContact(5L, "bob@example.org", "Bob");
    tombstone.setPhones(List.of("cell,+33 6 12 34 56 78"));
    tombstone.setSuppressed(true);
    when(emailContactStorage.getContactByAddress(USERNAME, "bob@example.org")).thenReturn(tombstone);
    when(emailContactStorage.updateContact(any())).thenAnswer(invocation -> invocation.getArgument(0));

    EmailContact revived = emailContactService.createContact(manualInput("Bob", "bob@example.org"), USERNAME);

    assertEquals(List.of("cell,+33 6 12 34 56 78"), revived.getPhones());
  }

  @Test
  void directoryPhonesFollowTheSameKeepAndReplaceContract() {
    // A directory row's phones are the user's own annotation (the profile does
    // not feed them), so they stay editable -- and now loss-proof: silence
    // keeps them, a present list replaces them.
    EmailContact directory = directoryContact(5L, "jane.doe@example.com", "Jane Doe", "jdoe");
    directory.setPhones(List.of("work,+33 1 23 45 67 89"));
    when(emailContactStorage.getContactById(5L)).thenReturn(directory);
    when(emailContactStorage.updateContact(any())).thenAnswer(invocation -> invocation.getArgument(0));

    EmailContact kept = emailContactService.updateContact(5L, manualInput("Jane", "jane.doe@example.com"), USERNAME);
    assertEquals(List.of("work,+33 1 23 45 67 89"), kept.getPhones());

    EmailContact input = manualInput("Jane", "jane.doe@example.com");
    input.setPhones(List.of("cell,+33 6 12 34 56 78"));
    EmailContact replaced = emailContactService.updateContact(5L, input, USERNAME);
    assertEquals(List.of("cell,+33 6 12 34 56 78"), replaced.getPhones());
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
  void deleteDropsTheFavoriteOfAHardDeletedContact() {
    EmailContact manual = collectedContact(5L, "bob@example.org", "Bob");
    manual.setSource(EmailContactSource.MANUAL);
    when(emailContactStorage.getContactById(5L)).thenReturn(manual);

    emailContactService.deleteOrSuppressContact(5L, USERNAME);

    verify(emailContactFavoriteService).removeFavorite(5L, USERNAME);
  }

  @Test
  void suppressionDropsTheFavoriteToo() {
    // A suppressed contact answers 404 everywhere, so its favorite would only be
    // a dead drawer row: the star must die with the row's visibility, not just
    // with the row itself.
    when(emailContactStorage.getContactById(5L)).thenReturn(collectedContact(5L, "bob@example.org", "Bob"));
    when(emailContactStorage.updateContact(any())).thenAnswer(invocation -> invocation.getArgument(0));

    emailContactService.deleteOrSuppressContact(5L, USERNAME);

    verify(emailContactFavoriteService).removeFavorite(5L, USERNAME);
  }

  @Test
  void aFailingFavoriteCleanupNeverFailsTheDelete() {
    doThrow(new RuntimeException("favorites unavailable")).when(emailContactFavoriteService)
                                                          .removeFavorite(anyLong(), anyString());
    EmailContact manual = collectedContact(5L, "bob@example.org", "Bob");
    manual.setSource(EmailContactSource.MANUAL);
    when(emailContactStorage.getContactById(5L)).thenReturn(manual);

    EmailContact result = assertDoesNotThrow(() -> emailContactService.deleteOrSuppressContact(5L, USERNAME));

    verify(emailContactStorage).deleteContact(5L);
    assertFalse(result.isSuppressed());
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
    when(emailContactStorage.getContacts(USERNAME, null, null, null, 0, 100))
                                                                       .thenReturn(new org.exoplatform.emailConnector.model.EmailContactPage(List.of(linked),
                                                                                                                                             java.util.Map.of(),
                                                                                                                                             1,
                                                                                                                                             0,
                                                                                                                                             100));
    givenDirectoryProfile("jdoe", "Jane Renamed", "jane.doe@example.com", "avatar", null);

    List<EmailContact> contacts = emailContactService.getContacts(USERNAME, null, null, false, 0, 100).getContacts();

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
  void importCreatesADirectoryLinkWithASnapshot() {
    givenDirectoryProfile("jdoe", "Jane Doe", "Jane.Doe@Example.com", "avatar", "profile-url");
    when(emailContactStorage.createContact(any())).thenAnswer(invocation -> invocation.getArgument(0));

    EmailContact imported = emailContactService.importDirectoryContact(USERNAME, "jdoe");

    ArgumentCaptor<EmailContact> created = ArgumentCaptor.forClass(EmailContact.class);
    verify(emailContactStorage).createContact(created.capture());
    assertEquals(EmailContactSource.DIRECTORY, created.getValue().getSource());
    assertEquals("jdoe", created.getValue().getPlatformUsername());
    assertEquals(USERNAME, created.getValue().getUserId());
    // The snapshot is the fallback for a profile that later disappears: the
    // name as-is, the address normalized like every stored address.
    assertEquals("Jane Doe", created.getValue().getDisplayName());
    assertEquals("jane.doe@example.com", created.getValue().getPrimaryEmail());
    // The answer is resolved live, so the client renders the card straight away.
    assertEquals("avatar", imported.getAvatarUrl());
    assertEquals("profile-url", imported.getProfileUrl());
  }

  @Test
  void importAnswersConflictForAnAlreadyLinkedColleague() {
    givenDirectoryProfile("jdoe", "Jane Doe", "jane.doe@example.com", null, null);
    when(emailContactStorage.getContactByPlatformUsername(USERNAME, "jdoe"))
                                                                            .thenReturn(directoryContact(5L,
                                                                                                         "jane.doe@example.com",
                                                                                                         "Jane Doe",
                                                                                                         "jdoe"));

    IllegalStateException conflict = assertThrows(IllegalStateException.class,
                                                  () -> emailContactService.importDirectoryContact(USERNAME, "jdoe"));

    assertEquals(EmailContactService.CONTACT_ALREADY_EXISTS, conflict.getMessage());
    verify(emailContactStorage, never()).createContact(any());
    verify(emailContactStorage, never()).updateContact(any());
  }

  @Test
  void importRevivesASuppressedDirectoryLink() {
    // The user removed the link, then asked for this colleague again: the
    // tombstone comes back as the same DIRECTORY row, never a conflict about a
    // row the user cannot see.
    givenDirectoryProfile("jdoe", "Jane Doe", "jane.doe@example.com", null, null);
    EmailContact tombstone = directoryContact(5L, "jane.doe@example.com", "Jane Doe", "jdoe");
    tombstone.setSuppressed(true);
    when(emailContactStorage.getContactByPlatformUsername(USERNAME, "jdoe")).thenReturn(tombstone);
    when(emailContactStorage.updateContact(any())).thenAnswer(invocation -> invocation.getArgument(0));

    EmailContact revived = emailContactService.importDirectoryContact(USERNAME, "jdoe");

    assertFalse(revived.isSuppressed());
    assertEquals(EmailContactSource.DIRECTORY, revived.getSource());
    verify(emailContactStorage, never()).createContact(any());
  }

  @Test
  void importAnswersConflictForAVisibleRowAtTheAddress() {
    // A collected row already holds the colleague's address: it is answered as
    // the conflict, UNMUTATED -- converting it to DIRECTORY would make it
    // read-only and gain nothing, enrichment already paints the profile on it.
    givenDirectoryProfile("jdoe", "Jane Doe", "jane.doe@example.com", null, null);
    when(emailContactStorage.getContactByAddress(USERNAME, "jane.doe@example.com"))
                                                                                   .thenReturn(collectedContact(9L,
                                                                                                                "jane.doe@example.com",
                                                                                                                "Jane"));

    IllegalStateException conflict = assertThrows(IllegalStateException.class,
                                                  () -> emailContactService.importDirectoryContact(USERNAME, "jdoe"));

    assertEquals(EmailContactService.CONTACT_ALREADY_EXISTS, conflict.getMessage());
    verify(emailContactStorage, never()).createContact(any());
    verify(emailContactStorage, never()).updateContact(any());
  }

  @Test
  void importMatchesASecondaryAddressToo() {
    // The address lookup runs against the address table, so a manual contact
    // filed under another primary address but carrying the profile email as a
    // secondary one is the same person -- and the same conflict.
    givenDirectoryProfile("jdoe", "Jane Doe", "jane.doe@example.com", null, null);
    EmailContact manual = collectedContact(9L, "jane@personal.org", "Jane");
    manual.setSource(EmailContactSource.MANUAL);
    manual.setSecondaryEmails(List.of("jane.doe@example.com"));
    when(emailContactStorage.getContactByAddress(USERNAME, "jane.doe@example.com")).thenReturn(manual);

    IllegalStateException conflict = assertThrows(IllegalStateException.class,
                                                  () -> emailContactService.importDirectoryContact(USERNAME, "jdoe"));

    assertEquals(EmailContactService.CONTACT_ALREADY_EXISTS, conflict.getMessage());
  }

  @Test
  void importRevivesASuppressedTombstoneAtTheAddress() {
    // A collected contact the user deleted, whose person they now import from
    // the directory: the tombstone comes back to life as the directory link --
    // the same explicit-user-act rule as creating a manual contact over one.
    givenDirectoryProfile("jdoe", "Jane Doe", "jane.doe@example.com", null, null);
    EmailContact tombstone = collectedContact(9L, "jane.doe@example.com", "Jane");
    tombstone.setSuppressed(true);
    when(emailContactStorage.getContactByAddress(USERNAME, "jane.doe@example.com")).thenReturn(tombstone);
    when(emailContactStorage.updateContact(any())).thenAnswer(invocation -> invocation.getArgument(0));

    EmailContact revived = emailContactService.importDirectoryContact(USERNAME, "jdoe");

    assertFalse(revived.isSuppressed());
    assertEquals(EmailContactSource.DIRECTORY, revived.getSource());
    assertEquals("jdoe", revived.getPlatformUsername());
    verify(emailContactStorage, never()).createContact(any());
  }

  @Test
  void importAnswersNullForAnUnknownIdentity() {
    when(identityManager.getOrCreateUserIdentity("ghost")).thenReturn(null);

    assertNull(emailContactService.importDirectoryContact(USERNAME, "ghost"));
    verify(emailContactStorage, never()).createContact(any());
  }

  @Test
  void importAnswersNullForADeletedIdentity() {
    Identity deleted = mock(Identity.class);
    when(deleted.isDeleted()).thenReturn(true);
    when(identityManager.getOrCreateUserIdentity("gone")).thenReturn(deleted);

    assertNull(emailContactService.importDirectoryContact(USERNAME, "gone"));
    verify(emailContactStorage, never()).createContact(any());
  }

  @Test
  void importRefusesTheCallerThemselves() {
    IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                                                    () -> emailContactService.importDirectoryContact(USERNAME, USERNAME));

    assertEquals(EmailContactService.CONTACT_SELF_IMPORT, refused.getMessage());
    verifyNoInteractions(emailContactStorage);
  }

  @Test
  void importStoresNoAddressWhenTheProfileHasNone() {
    // Address-less contacts are supported; a colleague without a profile email
    // still imports, and later mail from them simply collects a second row.
    givenDirectoryProfile("jdoe", "Jane Doe", null, null, null);
    when(emailContactStorage.createContact(any())).thenAnswer(invocation -> invocation.getArgument(0));

    EmailContact imported = emailContactService.importDirectoryContact(USERNAME, "jdoe");

    assertNull(imported.getPrimaryEmail());
    verify(emailContactStorage, never()).getContactByAddress(anyString(), anyString());
  }

  @Test
  void importStoresNoAddressWhenTheOwnerHidIt() {
    // The owner hid their email on their profile: the link is still worth
    // having, but the snapshot must not hold what the owner chose to hide --
    // stored today, it would resurface the day their profile is gone.
    Identity identity = givenDirectoryProfile("jdoe", "Jane Doe", "jane.doe@example.com", null, null);
    givenEmailHiddenBy(identity);
    when(emailContactStorage.createContact(any())).thenAnswer(invocation -> invocation.getArgument(0));

    EmailContact imported = emailContactService.importDirectoryContact(USERNAME, "jdoe");

    ArgumentCaptor<EmailContact> created = ArgumentCaptor.forClass(EmailContact.class);
    verify(emailContactStorage).createContact(created.capture());
    assertNull(created.getValue().getPrimaryEmail());
    assertEquals("Jane Doe", created.getValue().getDisplayName());
    assertNull(imported.getPrimaryEmail());
  }

  @Test
  void importStillDedupesOnAHiddenAddress() {
    // Hidden governs what is SHOWN, not what is matched: a visible collected
    // row at the hidden address is the same person, and answering it as the
    // conflict reveals nothing -- that row's address came from the user's own
    // mail.
    Identity identity = givenDirectoryProfile("jdoe", "Jane Doe", "jane.doe@example.com", null, null);
    givenEmailHiddenBy(identity);
    when(emailContactStorage.getContactByAddress(USERNAME, "jane.doe@example.com"))
                                                                                   .thenReturn(collectedContact(9L,
                                                                                                                "jane.doe@example.com",
                                                                                                                "Jane"));

    IllegalStateException conflict = assertThrows(IllegalStateException.class,
                                                  () -> emailContactService.importDirectoryContact(USERNAME, "jdoe"));

    assertEquals(EmailContactService.CONTACT_ALREADY_EXISTS, conflict.getMessage());
  }

  @Test
  void directoryCardsBlankAHiddenAddress() {
    // The owner hid their email AFTER the import stored it: the live resolution
    // wins over the snapshot in both directions, so the card shows no address.
    EmailContact linked = directoryContact(5L, "jane.doe@example.com", "Jane Doe", "jdoe");
    when(emailContactStorage.getContactById(5L)).thenReturn(linked);
    Identity identity = givenDirectoryProfile("jdoe", "Jane Doe", "jane.doe@example.com", "avatar", null);
    givenEmailHiddenBy(identity);

    EmailContact shown = emailContactService.getContact(5L, USERNAME);

    assertNull(shown.getPrimaryEmail());
    assertEquals("Jane Doe", shown.getDisplayName());
    assertEquals("avatar", shown.getAvatarUrl());
  }

  @Test
  void unreadableEmailVisibilityFailsClosed() {
    // A card briefly missing an address costs a click; a hidden address shown
    // costs a trust. So a visibility read that errors hides the address.
    EmailContact linked = directoryContact(5L, "jane.doe@example.com", "Jane Doe", "jdoe");
    when(emailContactStorage.getContactById(5L)).thenReturn(linked);
    givenDirectoryProfile("jdoe", "Jane Doe", "jane.doe@example.com", null, null);
    when(profilePropertyService.getProfileSettingByName("email")).thenThrow(new IllegalStateException("down"));

    assertNull(emailContactService.getContact(5L, USERNAME).getPrimaryEmail());
  }

  @Test
  void byPlatformUserAnswersTheDirectoryLinkFirst() {
    EmailContact linked = directoryContact(5L, "jane.doe@example.com", "Jane Doe", "jdoe");
    when(emailContactStorage.getContactByPlatformUsername(USERNAME, "jdoe")).thenReturn(linked);
    givenDirectoryProfile("jdoe", "Jane Renamed", "jane.doe@example.com", "avatar", null);

    EmailContact found = emailContactService.getContactByPlatformUser(USERNAME, "jdoe");

    assertEquals(5L, found.getId());
    assertEquals("Jane Renamed", found.getDisplayName());
  }

  @Test
  void byPlatformUserFallsBackToTheProfileAddress() {
    // No directory link, but a collected row holds the colleague's address:
    // the same row the import would answer 409 about, found the same way.
    givenDirectoryProfile("jdoe", "Jane Doe", "jane.doe@example.com", null, null);
    EmailContact collected = collectedContact(9L, "jane.doe@example.com", "Jane");
    when(emailContactStorage.getContactByAddress(USERNAME, "jane.doe@example.com")).thenReturn(collected);

    try (MockedStatic<EmailConnectorUtils> utils = mockStatic(EmailConnectorUtils.class)) {
      utils.when(() -> EmailConnectorUtils.getUserProfileByEmail(anyString())).thenReturn(null);

      EmailContact found = emailContactService.getContactByPlatformUser(USERNAME, "jdoe");

      assertEquals(9L, found.getId());
    }
  }

  @Test
  void byPlatformUserAnswersNullWhenNothingHoldsThePerson() {
    givenDirectoryProfile("jdoe", "Jane Doe", "jane.doe@example.com", null, null);

    assertNull(emailContactService.getContactByPlatformUser(USERNAME, "jdoe"));
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
  void aDirectoryContactKeepsTheIdentityItsProfileOwns() {
    // Editing used to be refused outright. It is not any more -- the fields the
    // profile resolves are simply kept, so a stale client cannot write them,
    // while everything the profile does not own is the user's to keep.
    EmailContact stored = directoryContact(5L, "jane.doe@example.com", "Jane Doe", "jdoe");
    when(emailContactStorage.getContactById(5L)).thenReturn(stored);
    when(emailContactStorage.updateContact(any())).thenAnswer(invocation -> invocation.getArgument(0));

    EmailContact saved = emailContactService.updateContact(5L, manualInput("J", "renamed@example.com"), USERNAME);

    assertNotNull(saved);
    assertEquals("jane.doe@example.com", saved.getPrimaryEmail());
    assertEquals("Jane Doe", saved.getDisplayName());
  }

  // ---------------------------------------------------------------- listing

  @Test
  void unknownSourceFilterIsRejected() {
    IllegalArgumentException invalid = assertThrows(IllegalArgumentException.class,
                                                    () -> emailContactService.getContacts(USERNAME, List.of("bogus"), null, false, 0, 100));
    assertEquals(EmailContactService.CONTACT_INVALID_SOURCE, invalid.getMessage());
  }

  @Test
  void sourceFiltersMapToTheStoredDiscriminators() {
    emailContactService.getContacts(USERNAME, null, null, false, 0, 100);
    verify(emailContactStorage).getContacts(USERNAME, null, null, null, 0, 100);

    // One filter per stored source. A contact typed by hand is neither collected
    // from mail nor owned by a provider's book, and it used to be filed with the
    // address book -- which read as "from my address book" for a contact no
    // address book had heard of.
    emailContactService.getContacts(USERNAME, List.of("collected"), null, false, 0, 100);
    verify(emailContactStorage).getContacts(USERNAME, List.of(EmailContactSource.COLLECTED), null, null, 0, 100);

    emailContactService.getContacts(USERNAME, List.of("manual"), null, false, 0, 100);
    verify(emailContactStorage).getContacts(USERNAME, List.of(EmailContactSource.MANUAL), null, null, 0, 100);

    // The directory chip, which only had rows to select once a colleague could be
    // added from their profile.
    emailContactService.getContacts(USERNAME, List.of("directory"), null, false, 0, 100);
    verify(emailContactStorage).getContacts(USERNAME, List.of(EmailContactSource.DIRECTORY), null, null, 0, 100);

    emailContactService.getContacts(USERNAME, List.of("addressBook"), null, false, 0, 100);
    verify(emailContactStorage).getContacts(USERNAME, List.of(EmailContactSource.CARDDAV), null, null, 0, 100);

    // Several chips mean their union. Answering only the first -- or, as this did,
    // answering "everything" the moment more than one was selected -- put back the
    // rows the user had just excluded.
    emailContactService.getContacts(USERNAME, List.of("collected", "manual"), null, false, 0, 100);
    verify(emailContactStorage).getContacts(USERNAME,
                                            List.of(EmailContactSource.COLLECTED, EmailContactSource.MANUAL),
                                            null,
                                            null,
                                            0,
                                            100);
  }

  @Test
  void favoritesFilterRestrictsTheListToTheStarredIds() {
    when(emailContactFavoriteService.getFavoriteContactIds(USERNAME)).thenReturn(Set.of(5L, 6L));

    emailContactService.getContacts(USERNAME, null, null, true, 0, 100);

    verify(emailContactStorage).getContacts(USERNAME, null, null, Set.of(5L, 6L), 0, 100);
  }

  @Test
  void favoritesFilterIntersectsWithTheSourceFilter() {
    // The Favorites chip narrows whatever the source chips selected; it never
    // replaces them, or toggling it would silently widen the list.
    when(emailContactFavoriteService.getFavoriteContactIds(USERNAME)).thenReturn(Set.of(5L));

    emailContactService.getContacts(USERNAME, List.of("collected"), null, true, 0, 100);

    verify(emailContactStorage).getContacts(USERNAME, List.of(EmailContactSource.COLLECTED), null, Set.of(5L), 0, 100);
  }

  @Test
  void anEmptyFavoriteSetShortCircuitsToAnEmptyPage() {
    // Nothing starred filters to nothing, answered without a query: "id IN ()" is
    // not SQL every database accepts, and there is nothing to find anyway.
    when(emailContactFavoriteService.getFavoriteContactIds(USERNAME)).thenReturn(new java.util.HashSet<>());

    org.exoplatform.emailConnector.model.EmailContactPage page =
                                                               emailContactService.getContacts(USERNAME, null, null, true, 0, 100);

    assertTrue(page.getContacts().isEmpty());
    assertEquals(0, page.getSize());
    verify(emailContactStorage, never()).getContacts(anyString(), anyList(), anyString(), any(), anyInt(), anyInt());
  }

  @Test
  void answeredPagesAreMarkedFromOneFavoriteRead() {
    // The set is read once per call and every row checked against it, never one
    // isFavorite per row — the drawer streams pages of 200 up to thousands of rows.
    when(emailContactFavoriteService.getFavoriteContactIds(USERNAME)).thenReturn(Set.of(5L));
    EmailContact starred = collectedContact(5L, "bob@example.org", "Bob");
    EmailContact plain = collectedContact(6L, "ann@example.org", "Ann");
    when(emailContactStorage.getContacts(USERNAME, null, null, null, 0, 100))
                                                                             .thenReturn(new org.exoplatform.emailConnector.model.EmailContactPage(List.of(starred,
                                                                                                                                                           plain),
                                                                                                                                                   java.util.Map.of(),
                                                                                                                                                   2,
                                                                                                                                                   0,
                                                                                                                                                   100));

    List<EmailContact> contacts = emailContactService.getContacts(USERNAME, null, null, false, 0, 100).getContacts();

    assertTrue(contacts.get(0).isFavorite());
    assertFalse(contacts.get(1).isFavorite());
    verify(emailContactFavoriteService, times(1)).getFavoriteContactIds(USERNAME);
    verify(emailContactFavoriteService, never()).isFavorite(anyLong(), anyString());
  }

  @Test
  void singleContactReadsCarryTheFavoriteFlag() {
    when(emailContactStorage.getContactById(5L)).thenReturn(collectedContact(5L, "bob@example.org", "Bob"));
    when(emailContactFavoriteService.isFavorite(5L, USERNAME)).thenReturn(true);

    assertTrue(emailContactService.getContact(5L, USERNAME).isFavorite());
  }

  // ---------------------------------------------------------------- unified search

  @Test
  void searchAnswersTheStoreWithoutColleaguesAndWithoutPhones() {
    // Bob's address resolves to a platform profile: he is a colleague, and the
    // People section is where colleagues answer — his row must not appear twice
    // across the two sections. Ann is who this section exists for.
    EmailContact colleague = collectedContact(5L, "bob@example.org", "Bob");
    EmailContact outsider = collectedContact(6L, "ann@client.org", "Ann");
    outsider.setPhones(List.of("+33 6 00 00 00 00"));
    givenSearchSlice("bob", 0, 10, colleague, outsider);
    Profile matched = mock(Profile.class);
    lenient().when(matched.getUrl()).thenReturn("profile-url");

    List<EmailContact> answered;
    try (MockedStatic<EmailConnectorUtils> utils = mockStatic(EmailConnectorUtils.class)) {
      utils.when(() -> EmailConnectorUtils.getUserProfileByEmail(anyString())).thenReturn(null);
      utils.when(() -> EmailConnectorUtils.getUserProfileByEmail("bob@example.org")).thenReturn(matched);
      answered = emailContactService.searchContacts(USERNAME, "bob", false, 10);
    }

    assertEquals(1, answered.size());
    assertEquals("Ann", answered.get(0).getDisplayName());
    // A search hit needs a name, a face and an address — not the whole card.
    assertNull(answered.get(0).getPhones());
  }

  @Test
  void searchNeverScansDirectoryRows() {
    // A directory row IS a platform user by construction, so it is excluded in
    // SQL rather than paid for (one profile lookup per row) and then dropped.
    try (MockedStatic<EmailConnectorUtils> utils = mockStatic(EmailConnectorUtils.class)) {
      utils.when(() -> EmailConnectorUtils.getUserProfileByEmail(anyString())).thenReturn(null);
      emailContactService.searchContacts(USERNAME, "jane", false, 10);
    }

    verify(emailContactStorage).getContacts(USERNAME,
                                            List.of(EmailContactSource.COLLECTED,
                                                    EmailContactSource.MANUAL,
                                                    EmailContactSource.CARDDAV,
                                                    EmailContactSource.GRAPH),
                                            "jane",
                                            null,
                                            0,
                                            10);
  }

  @Test
  void searchWalksFurtherSlicesWhenColleaguesFillThePage() {
    // A first page made entirely of colleagues must not read as "no results":
    // the walk continues into the next slice until the answer is full.
    EmailContact colleagueA = collectedContact(5L, "bob@example.org", "Bob");
    EmailContact colleagueB = collectedContact(6L, "jim@example.org", "Jim");
    EmailContact outsider = collectedContact(7L, "ann@client.org", "Ann");
    givenSearchSlice("o", 0, 2, colleagueA, colleagueB);
    givenSearchSlice("o", 2, 2, outsider);
    Profile matched = mock(Profile.class);
    lenient().when(matched.getUrl()).thenReturn("profile-url");

    List<EmailContact> answered;
    try (MockedStatic<EmailConnectorUtils> utils = mockStatic(EmailConnectorUtils.class)) {
      utils.when(() -> EmailConnectorUtils.getUserProfileByEmail(anyString())).thenReturn(null);
      utils.when(() -> EmailConnectorUtils.getUserProfileByEmail("bob@example.org")).thenReturn(matched);
      utils.when(() -> EmailConnectorUtils.getUserProfileByEmail("jim@example.org")).thenReturn(matched);
      answered = emailContactService.searchContacts(USERNAME, "o", false, 2);
    }

    assertEquals(1, answered.size());
    assertEquals("Ann", answered.get(0).getDisplayName());
  }

  @Test
  void searchStopsWalkingAtTheScanCap() {
    // A store where every matching row is a colleague must answer short rather
    // than walk the whole table: each scanned row costs a profile lookup, and
    // this runs on every search anyone types.
    EmailContact colleague = collectedContact(5L, "bob@example.org", "Bob");
    when(emailContactStorage.getContacts(eq(USERNAME), anyList(), eq("bob"), any(), anyInt(), eq(1)))
                                                                                                     .thenReturn(new EmailContactPage(List.of(colleague),
                                                                                                                                      java.util.Map.of(),
                                                                                                                                      100,
                                                                                                                                      0,
                                                                                                                                      1));
    Profile matched = mock(Profile.class);
    lenient().when(matched.getUrl()).thenReturn("profile-url");

    List<EmailContact> answered;
    try (MockedStatic<EmailConnectorUtils> utils = mockStatic(EmailConnectorUtils.class)) {
      utils.when(() -> EmailConnectorUtils.getUserProfileByEmail(anyString())).thenReturn(matched);
      answered = emailContactService.searchContacts(USERNAME, "bob", false, 1);
    }

    assertTrue(answered.isEmpty());
    verify(emailContactStorage, times(5)).getContacts(eq(USERNAME), anyList(), eq("bob"), any(), anyInt(), eq(1));
  }

  @Test
  void searchWithoutATermAnswersNothing() {
    // The search page only searches with a term or the Favorites filter; a bare
    // call must not become "list the whole store".
    assertTrue(emailContactService.searchContacts(USERNAME, "  ", false, 10).isEmpty());

    verify(emailContactStorage, never()).getContacts(anyString(), anyList(), anyString(), any(), anyInt(), anyInt());
  }

  @Test
  void searchFavoritesNarrowsToTheStarredIdsAndMarksTheRows() {
    // Declaring favoritesEnabled is what makes the search page send
    // favorites=true here; the browser then re-filters rows on their own
    // `favorite` flag, so an unmarked row would be thrown away after arriving.
    when(emailContactFavoriteService.getFavoriteContactIds(USERNAME)).thenReturn(Set.of(6L));
    EmailContact starred = collectedContact(6L, "ann@client.org", "Ann");
    when(emailContactStorage.getContacts(eq(USERNAME), anyList(), eq("ann"), eq(Set.of(6L)), eq(0), eq(10)))
                                                                                                            .thenReturn(new EmailContactPage(List.of(starred),
                                                                                                                                             java.util.Map.of(),
                                                                                                                                             1,
                                                                                                                                             0,
                                                                                                                                             10));

    List<EmailContact> answered;
    try (MockedStatic<EmailConnectorUtils> utils = mockStatic(EmailConnectorUtils.class)) {
      utils.when(() -> EmailConnectorUtils.getUserProfileByEmail(anyString())).thenReturn(null);
      answered = emailContactService.searchContacts(USERNAME, "ann", true, 10);
    }

    assertEquals(1, answered.size());
    assertTrue(answered.get(0).isFavorite());
  }

  @Test
  void searchWithFavoritesAndNothingStarredShortCircuits() {
    // The same "id IN ()" guard as the list: nothing starred filters to
    // nothing, answered before the database is asked.
    when(emailContactFavoriteService.getFavoriteContactIds(USERNAME)).thenReturn(new java.util.HashSet<>());

    assertTrue(emailContactService.searchContacts(USERNAME, "ann", true, 10).isEmpty());

    verify(emailContactStorage, never()).getContacts(anyString(), anyList(), anyString(), any(), anyInt(), anyInt());
  }

  @Test
  void searchClampsTheLimitEitherWay() {
    try (MockedStatic<EmailConnectorUtils> utils = mockStatic(EmailConnectorUtils.class)) {
      utils.when(() -> EmailConnectorUtils.getUserProfileByEmail(anyString())).thenReturn(null);
      // Asking for nothing takes the server's default...
      emailContactService.searchContacts(USERNAME, "ann", false, 0);
      verify(emailContactStorage).getContacts(eq(USERNAME), anyList(), eq("ann"), any(), eq(0), eq(10));
      // ...and asking for everything is capped, whatever the caller says.
      emailContactService.searchContacts(USERNAME, "bob", false, 5000);
      verify(emailContactStorage).getContacts(eq(USERNAME), anyList(), eq("bob"), any(), eq(0), eq(50));
    }
  }

  @Test
  void aMailboxThatChangedLetsCollectionStartOverAgain() {
    // The backfill is what bootstraps collection, because it reads sent mail before
    // the inbox. Incremental collection cannot: a sync caches the inbox first, so
    // every sender of a fresh mailbox is judged against no sent mail at all. Left
    // marked done from the previous mailbox, a rebound account collects nobody.
    emailContactService.resetCollectionBackfill(USERNAME);

    verify(settingService).remove(Context.USER.id(USERNAME),
                                  EmailConnectorService.EMAIL_CONNECTOR_SCOPE,
                                  "emailContactsBackfillDone");
  }

  @Test
  void oneUnusableContactDoesNotCostTheRestOfTheRun() {
    // A run walks hundreds of messages. An exception on one of them used to abandon
    // every message after it, so a single bad row emptied a whole collection pass.
    when(emailContactStorage.getContactByAddress(eq(USERNAME), anyString())).thenThrow(new IllegalStateException("bad row"));

    assertDoesNotThrow(() -> emailContactService.collectFromSentRecipients(USERNAME,
                                                                           List.of(new EmailRecipient("Jane", "jane@example.com", null, false),
                                                                                   new EmailRecipient("Bob", "bob@example.com", null, false))));
  }

  @Test
  void aMailFindsItsSenderByAnyOfTheirAddresses() {
    // What the mailbox asks: it has an address from a header and nothing else.
    // Matching only the address a contact is filed under would miss the person
    // every time they write from their second one.
    EmailContact stored = collectedContact(4L, "jane@example.org", "Jane Doe");
    when(emailContactStorage.getContactByAddress(USERNAME, "jane.doe@example.com")).thenReturn(stored);

    EmailContact found = emailContactService.getContactByAddress("Jane.Doe@Example.COM", USERNAME);

    assertNotNull(found);
    assertEquals(4L, found.getId());
  }

  @Test
  void anAddressNobodyIsStoredAtIsNotAnError() {
    // The mailbox reads this answer as "offer to add them", so it must be a plain
    // null rather than anything that looks like a failure.
    when(emailContactStorage.getContactByAddress(eq(USERNAME), anyString())).thenReturn(null);

    assertNull(emailContactService.getContactByAddress("stranger@example.com", USERNAME));
  }

  @Test
  void aSuppressedContactStaysHiddenFromTheMailbox() {
    EmailContact hidden = collectedContact(5L, "hidden@example.org", "Hidden");
    hidden.setSuppressed(true);
    when(emailContactStorage.getContactByAddress(eq(USERNAME), anyString())).thenReturn(hidden);

    assertNull(emailContactService.getContactByAddress("hidden@example.org", USERNAME));
  }

  @Test
  void aColleagueKeepsTheirProfileIdentityButTakesYourAnnotations() {
    // What the profile owns is resolved on every read, so an edit to it would be
    // undone; what it does not own -- a birthday, a note about where you met --
    // is the user's, and refusing the whole row denied them both.
    EmailContact stored = collectedContact(12L, "colleague@exoplatform.com", "Jane Colleague");
    stored.setSource(EmailContactSource.DIRECTORY);
    stored.setPlatformUsername("jane");
    when(emailContactStorage.getContactById(12L)).thenReturn(stored);
    when(emailContactStorage.updateContact(any())).thenAnswer(invocation -> invocation.getArgument(0));

    EmailContact edit = new EmailContact();
    edit.setPrimaryEmail("someone.else@example.com");
    edit.setDisplayName("Renamed By Hand");
    edit.setNote("Met at the Grenoble offsite");
    edit.setBirthday("--12-31");

    EmailContact saved = emailContactService.updateContact(12L, edit, USERNAME);

    assertNotNull(saved);
    assertEquals("Met at the Grenoble offsite", saved.getNote());
    assertEquals("--12-31", saved.getBirthday());
    // Ignored rather than refused: a stale client must not be able to write them.
    assertEquals("colleague@exoplatform.com", saved.getPrimaryEmail());
    assertEquals("Jane Colleague", saved.getDisplayName());
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
  private Identity givenDirectoryProfile(String username, String fullName, String email, String avatarUrl, String profileUrl) {
    Identity identity = mock(Identity.class);
    Profile profile = mock(Profile.class);
    lenient().when(identity.isDeleted()).thenReturn(false);
    lenient().when(identity.getProfile()).thenReturn(profile);
    lenient().when(identity.getId()).thenReturn("77");
    lenient().when(identity.getRemoteId()).thenReturn(username);
    lenient().when(profile.getFullName()).thenReturn(fullName);
    lenient().when(profile.getEmail()).thenReturn(email);
    lenient().when(profile.getAvatarUrl()).thenReturn(avatarUrl);
    lenient().when(profile.getUrl()).thenReturn(profileUrl);
    when(identityManager.getOrCreateUserIdentity(username)).thenReturn(identity);
    return identity;
  }

  /**
   * Marks the email profile property as hidden by its owner: the platform knows
   * an "email" property setting, and the given identity's hidden ids hold it.
   *
   * @param identity the profile owner whose email is hidden
   */
  private void givenEmailHiddenBy(Identity identity) {
    ProfilePropertySetting emailSetting = new ProfilePropertySetting();
    emailSetting.setId(42L);
    emailSetting.setPropertyName("email");
    lenient().when(profilePropertyService.getProfileSettingByName("email")).thenReturn(emailSetting);
    lenient().when(profilePropertyService.getHiddenProfilePropertyIds(Long.parseLong(identity.getId())))
             .thenReturn(List.of(42L));
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
   * One storage slice as the unified search walks them: the rows the store
   * answers for this term at this offset, whatever the sources.
   *
   * @param term the searched term
   * @param offset the slice's row offset
   * @param limit the slice size
   * @param contacts the rows the slice holds
   */
  private void givenSearchSlice(String term, int offset, int limit, EmailContact... contacts) {
    lenient().when(emailContactStorage.getContacts(eq(USERNAME), anyList(), eq(term), any(), eq(offset), eq(limit)))
             .thenReturn(new EmailContactPage(List.of(contacts), java.util.Map.of(), contacts.length, offset, limit));
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
