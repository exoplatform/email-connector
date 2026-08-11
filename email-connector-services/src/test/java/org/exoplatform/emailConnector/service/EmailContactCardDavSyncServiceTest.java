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
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;

import org.exoplatform.emailConnector.carddav.AddressBook;
import org.exoplatform.emailConnector.carddav.CardDavClient;
import org.exoplatform.emailConnector.carddav.CardDavException;
import org.exoplatform.emailConnector.carddav.ContactResource;
import org.exoplatform.emailConnector.carddav.ParsedVCard;
import org.exoplatform.emailConnector.carddav.PutResult;
import org.exoplatform.emailConnector.carddav.VCardParser;
import org.exoplatform.emailConnector.model.CardDavContactData;
import org.exoplatform.emailConnector.model.CardDavRow;
import org.exoplatform.emailConnector.model.ContactPublishQueue;
import org.exoplatform.emailConnector.model.ContactSyncState;
import org.exoplatform.emailConnector.model.EmailConnector;
import org.exoplatform.emailConnector.model.EmailContact;
import org.exoplatform.emailConnector.model.EmailContactSource;
import org.exoplatform.emailConnector.model.PhotoOrigin;
import org.exoplatform.emailConnector.model.SyncStatus;
import org.exoplatform.emailConnector.model.UserEmailSetting;
import org.exoplatform.emailConnector.storage.EmailContactStorage;

/**
 * The reconciliation rules. Every one of these decides what happens to somebody's
 * real contact, so they are pinned individually rather than through one happy
 * path.
 */
@ExtendWith(MockitoExtension.class)
@SpringBootTest(classes = { EmailContactCardDavSyncService.class })
public class EmailContactCardDavSyncServiceTest {

  private static final String                USERNAME     = "alice";

  private static final long                  CONNECTOR_ID = 7L;

  private static final String                BOOK_URL     = "https://mail.example.com/dav/alice/default/";

  @MockBean
  private EmailContactStorage                emailContactStorage;

  @MockBean
  private UserEmailSettingService            userEmailSettingService;

  @MockBean
  private EmailConnectorService              emailConnectorService;

  @MockBean
  private CardDavClient                      cardDavClient;

  @MockBean
  private VCardParser                        vCardParser;

  @MockBean
  private EmailContactFavoriteService        emailContactFavoriteService;

  @MockBean
  private EmailContactService                emailContactService;

  @MockBean
  private EmailContactVCardService           emailContactVCardService;

  @Autowired
  private EmailContactCardDavSyncService      syncService;

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
    lenient().when(userEmailSettingService.getContactSyncState(USERNAME)).thenReturn(new ContactSyncState());
    // Empty and shared across calls, like the real accessor's never-null answer:
    // the publish and drain paths consult it even when nothing was ever queued.
    lenient().when(userEmailSettingService.getContactPublishQueue(USERNAME)).thenReturn(new ContactPublishQueue());
    lenient().when(cardDavClient.discoverAddressBook(anyString(), anyString(), anyString()))
             .thenReturn(new AddressBook(BOOK_URL, "Contacts", "ctag-1"));
  }

  @Test
  void anUnchangedAddressBookCostsOneQuestion() {
    // The whole reason the job can run often: a collection version that has not
    // moved means nothing inside it moved either.
    ContactSyncState state = new ContactSyncState();
    state.setAddressBookHref(BOOK_URL);
    state.setCtag("ctag-1");
    when(userEmailSettingService.getContactSyncState(USERNAME)).thenReturn(state);
    when(cardDavClient.getCtag(any(), anyString(), anyString())).thenReturn("ctag-1");

    syncService.syncAddressBook(USERNAME);

    verify(cardDavClient, never()).listResourceEtags(any(), anyString(), anyString());
    verify(cardDavClient, never()).multiget(any(), any(), anyString(), anyString());
  }

  @Test
  void aRunSomebodyAskedForIgnoresTheUnchangedVersion() {
    // The scheduled job stops at an unchanged collection version, which is what
    // makes it cheap. A person pressing the button has decided that answer is
    // wrong, and the sync should not out-argue them.
    ContactSyncState state = new ContactSyncState();
    state.setAddressBookHref(BOOK_URL);
    state.setConfiguredUrl("https://mail.example.com");
    state.setCtag("ctag-1");
    when(userEmailSettingService.getContactSyncState(USERNAME)).thenReturn(state);
    when(cardDavClient.getCtag(any(), anyString(), anyString())).thenReturn("ctag-1");
    when(cardDavClient.listResourceEtags(any(), anyString(), anyString())).thenReturn(Map.of());

    syncService.syncAddressBook(USERNAME, true);

    verify(cardDavClient).listResourceEtags(any(), anyString(), anyString());
  }

  @Test
  void anEntryAlreadyStoredAtItsCurrentVersionIsNotReRead() {
    givenServerHas(Map.of("/dav/jane.vcf", "\"v1\""));
    when(emailContactStorage.getCardDavRows(USERNAME, CONNECTOR_ID)).thenReturn(List.of(row(3L,
                                                                                            "jane@example.com",
                                                                                            EmailContactSource.CARDDAV,
                                                                                            false,
                                                                                            2,
                                                                                            "/dav/jane.vcf",
                                                                                            "\"v1\"",
                                                                                            null,
                                                                                            PhotoOrigin.USER)));

    syncService.syncAddressBook(USERNAME);

    verify(cardDavClient, never()).multiget(any(), any(), anyString(), anyString());
  }

  @Test
  void aCollectedContactIsClaimedInPlaceAndKeepsItsHistory() {
    // The rule the compose autocomplete depends on: claiming must not reset the
    // counters that say who this user actually writes to.
    givenServerHas(Map.of("/dav/jane.vcf", "\"v1\""));
    givenServerReturns(resource("/dav/jane.vcf", "\"v1\""), card("Jane Doe", "jane@example.com"));
    when(emailContactStorage.getCardDavRowByAddress(USERNAME, "jane@example.com")).thenReturn(row(9L,
                                                                                                  "jane@example.com",
                                                                                                  EmailContactSource.COLLECTED,
                                                                                                  false,
                                                                                                  17,
                                                                                                  null,
                                                                                                  null,
                                                                                                  null,
                                                                                                  PhotoOrigin.USER));

    syncService.syncAddressBook(USERNAME);

    ArgumentCaptor<Long> existingId = ArgumentCaptor.forClass(Long.class);
    verify(emailContactStorage).saveCardDavContact(eq(USERNAME),
                                                   existingId.capture(),
                                                   eq(CONNECTOR_ID),
                                                   eq("/dav/jane.vcf"),
                                                   eq("\"v1\""),
                                                   any(CardDavContactData.class),
                                                   any(),
                                                   anyBoolean());
    assertEquals(9L, existingId.getValue(), "the same row, so its correspondence history survives");
  }

  @Test
  void aContactTheUserTypedIsNeverClaimed() {
    givenServerHas(Map.of("/dav/jane.vcf", "\"v1\""));
    givenServerReturns(resource("/dav/jane.vcf", "\"v1\""), card("Jane Doe", "jane@example.com"));
    when(emailContactStorage.getCardDavRowByAddress(USERNAME, "jane@example.com")).thenReturn(row(9L,
                                                                                                  "jane@example.com",
                                                                                                  EmailContactSource.MANUAL,
                                                                                                  false,
                                                                                                  0,
                                                                                                  null,
                                                                                                  null,
                                                                                                  null,
                                                                                                  PhotoOrigin.USER));

    syncService.syncAddressBook(USERNAME);

    verify(emailContactStorage, never()).saveCardDavContact(anyString(),
                                                            any(),
                                                            anyLong(),
                                                            anyString(),
                                                            anyString(),
                                                            any(),
                                                            any(),
                                                            anyBoolean());
  }

  @Test
  void aPictureSetByHandIsNeverOverwritten() {
    givenServerHas(Map.of("/dav/jane.vcf", "\"v1\""));
    givenServerReturns(resource("/dav/jane.vcf", "\"v1\""), cardWithPhoto("Jane Doe", "jane@example.com"));
    when(emailContactStorage.getCardDavRowByAddress(USERNAME, "jane@example.com")).thenReturn(row(9L,
                                                                                                  "jane@example.com",
                                                                                                  EmailContactSource.COLLECTED,
                                                                                                  false,
                                                                                                  3,
                                                                                                  null,
                                                                                                  null,
                                                                                                  42L,
                                                                                                  PhotoOrigin.USER));

    syncService.syncAddressBook(USERNAME);

    ArgumentCaptor<Boolean> writePhoto = ArgumentCaptor.forClass(Boolean.class);
    verify(emailContactStorage).saveCardDavContact(anyString(),
                                                   any(),
                                                   anyLong(),
                                                   anyString(),
                                                   anyString(),
                                                   any(),
                                                   any(),
                                                   writePhoto.capture());
    assertEquals(false, writePhoto.getValue(), "the user's own picture outranks the address book's");
  }

  @Test
  void anEntryWithAPhoneAndNoAddressIsStillAPerson() {
    // This is the change: a contact is a person, not an address. Refusing these is
    // what made a 496-entry Google address book arrive as 132 contacts, and the
    // ones it dropped were people with a phone number.
    givenServerHas(Map.of("/dav/phoneonly.vcf", "\"v1\""));
    givenServerReturns(resource("/dav/phoneonly.vcf", "\"v1\""), card("Somebody With A Phone", null));

    syncService.syncAddressBook(USERNAME);

    ArgumentCaptor<CardDavContactData> stored = ArgumentCaptor.forClass(CardDavContactData.class);
    verify(emailContactStorage).saveCardDavContact(eq(USERNAME),
                                                   any(),
                                                   eq(CONNECTOR_ID),
                                                   eq("/dav/phoneonly.vcf"),
                                                   eq("\"v1\""),
                                                   stored.capture(),
                                                   any(),
                                                   anyBoolean());
    assertNull(stored.getValue().primaryEmail(), "no address, and that is allowed now");
    assertEquals("Somebody With A Phone", stored.getValue().displayName());
  }

  @Test
  void anEntryWithNeitherNameNorAddressIsSkipped() {
    // The floor: nothing to show and nothing to reach is not a person, whatever the
    // server calls it.
    givenServerHas(Map.of("/dav/empty.vcf", "\"v1\""));
    givenServerReturns(resource("/dav/empty.vcf", "\"v1\""), card(null, null));

    syncService.syncAddressBook(USERNAME);

    verify(emailContactStorage, never()).saveCardDavContact(anyString(),
                                                            any(),
                                                            anyLong(),
                                                            anyString(),
                                                            anyString(),
                                                            any(),
                                                            any(),
                                                            anyBoolean());
  }

  @Test
  void anEntryIsMatchedByAnyAddressItCarries() {
    // The person may already be known here under their other address; two rows for
    // one person is exactly what this prevents.
    // Only the LOOP is pinned here: the storage is mocked, so this passed happily
    // while the query underneath asked PRIMARY_EMAIL and could not answer for a
    // secondary address at all. That query is pinned in EmailContactStorageTest
    // and, on a real database, in EmailContactDAOTest.
    givenServerHas(Map.of("/dav/jane.vcf", "\"v1\""));
    ParsedVCard twoAddresses = new ParsedVCard("uid-1",
                                               "Jane Doe",
                                               null,
                                               null,
                                               List.of("jane@work.example", "jane@home.example"),
                                               List.of(),
                                               null,
                                               null,
                                               null,
                                               null,
                                               null,
                                               null,
                                               null,
                                               null);
    givenServerReturns(resource("/dav/jane.vcf", "\"v1\""), twoAddresses);
    when(emailContactStorage.getCardDavRowByAddress(USERNAME, "jane@work.example")).thenReturn(null);
    when(emailContactStorage.getCardDavRowByAddress(USERNAME, "jane@home.example")).thenReturn(row(9L,
                                                                                                   "jane@home.example",
                                                                                                   EmailContactSource.COLLECTED,
                                                                                                   false,
                                                                                                   5,
                                                                                                   null,
                                                                                                   null,
                                                                                                   null,
                                                                                                   PhotoOrigin.USER));

    syncService.syncAddressBook(USERNAME);

    ArgumentCaptor<Long> claimed = ArgumentCaptor.forClass(Long.class);
    verify(emailContactStorage).saveCardDavContact(eq(USERNAME),
                                                   claimed.capture(),
                                                   anyLong(),
                                                   anyString(),
                                                   anyString(),
                                                   any(),
                                                   any(),
                                                   anyBoolean());
    assertEquals(9L, claimed.getValue(), "found by the second address, and claimed in place");
  }

  @Test
  void aVanishedEntryWithCorrespondenceIsDemotedNotDeleted() {
    // The address book losing an entry says nothing about whether the user still
    // writes to that person, and the mailbox earned that history.
    givenServerHas(Map.of());
    when(emailContactStorage.getCardDavRows(USERNAME, CONNECTOR_ID)).thenReturn(List.of(row(4L,
                                                                                            "jane@example.com",
                                                                                            EmailContactSource.CARDDAV,
                                                                                            false,
                                                                                            12,
                                                                                            "/dav/jane.vcf",
                                                                                            "\"v1\"",
                                                                                            77L,
                                                                                            PhotoOrigin.VCARD)));

    syncService.syncAddressBook(USERNAME);

    verify(emailContactStorage).demoteCardDavRow(4L, true);
    verify(emailContactStorage, never()).deleteContact(anyLong());
  }

  @Test
  void aVanishedEntryWithNoCorrespondenceIsDeleted() {
    givenServerHas(Map.of());
    when(emailContactStorage.getCardDavRows(USERNAME, CONNECTOR_ID)).thenReturn(List.of(row(4L,
                                                                                            "ghost@example.com",
                                                                                            EmailContactSource.CARDDAV,
                                                                                            false,
                                                                                            0,
                                                                                            "/dav/ghost.vcf",
                                                                                            "\"v1\"",
                                                                                            null,
                                                                                            PhotoOrigin.VCARD)));

    syncService.syncAddressBook(USERNAME);

    verify(emailContactStorage).deleteContact(4L);
    verify(emailContactStorage, never()).demoteCardDavRow(anyLong(), anyBoolean());
    // The row is gone for real, so its favorite goes with it; a demoted row keeps
    // its favorite on purpose, the person being still there.
    verify(emailContactFavoriteService).removeFavorite(4L, USERNAME);
  }

  @Test
  void aRowClaimedThroughItsAddressIsNeverDeletedAsVanishedInTheSameRun() {
    // The live BlueMind failure, pinned: the store knows the person at href A,
    // the server lists the same card at href B (a rename, a move, or BlueMind
    // filing a freshly published card under its own name). The apply loop
    // matches the row by address and rewrites its href in place -- but the
    // vanished check judges rows by a snapshot taken BEFORE that loop, where
    // the row still says A. Without shielding the rows this run just wrote,
    // the sync updated the contact and hard-deleted it in the same breath, and
    // the user watched it disappear.
    givenServerHas(Map.of("/dav/alice/default/B.vcf", "\"e2\""));
    when(emailContactStorage.getCardDavRows(USERNAME, CONNECTOR_ID)).thenReturn(List.of(row(9L,
                                                                                            "bob@example.org",
                                                                                            EmailContactSource.CARDDAV,
                                                                                            false,
                                                                                            0,
                                                                                            "/dav/alice/default/A.vcf",
                                                                                            "\"e1\"",
                                                                                            null,
                                                                                            null)));
    givenServerReturns(resource("/dav/alice/default/B.vcf", "\"e2\""), card("Bob", "bob@example.org"));
    when(emailContactStorage.getCardDavRowByAddress(USERNAME, "bob@example.org")).thenReturn(row(9L,
                                                                                                 "bob@example.org",
                                                                                                 EmailContactSource.CARDDAV,
                                                                                                 false,
                                                                                                 0,
                                                                                                 "/dav/alice/default/A.vcf",
                                                                                                 "\"e1\"",
                                                                                                 null,
                                                                                                 null));
    when(emailContactStorage.saveCardDavContact(anyString(), anyLong(), anyLong(), anyString(), anyString(), any(), any(),
                                                anyBoolean())).thenReturn(9L);

    syncService.syncAddressBook(USERNAME);

    // The entry is written onto the row it already was...
    ArgumentCaptor<Long> claimed = ArgumentCaptor.forClass(Long.class);
    verify(emailContactStorage).saveCardDavContact(anyString(),
                                                   claimed.capture(),
                                                   anyLong(),
                                                   eq("/dav/alice/default/B.vcf"),
                                                   anyString(),
                                                   any(),
                                                   any(),
                                                   anyBoolean());
    assertEquals(9L, claimed.getValue());
    // ...and that row survives the run, whatever the pre-apply snapshot
    // remembers its href to have been.
    verify(emailContactStorage, never()).deleteContact(anyLong());
    verify(emailContactStorage, never()).demoteCardDavRow(anyLong(), anyBoolean());
    verify(emailContactFavoriteService, never()).removeFavorite(anyLong(), anyString());
  }

  @Test
  void aVanishedEntrysFavoriteCleanupNeverFailsTheSync() {
    doThrow(new RuntimeException("favorites unavailable")).when(emailContactFavoriteService)
                                                          .removeFavorite(anyLong(), anyString());
    givenServerHas(Map.of());
    when(emailContactStorage.getCardDavRows(USERNAME, CONNECTOR_ID)).thenReturn(List.of(row(4L,
                                                                                            "ghost@example.com",
                                                                                            EmailContactSource.CARDDAV,
                                                                                            false,
                                                                                            0,
                                                                                            "/dav/ghost.vcf",
                                                                                            "\"v1\"",
                                                                                            null,
                                                                                            PhotoOrigin.VCARD)));

    assertDoesNotThrow(() -> syncService.syncAddressBook(USERNAME));

    verify(emailContactStorage).deleteContact(4L);
  }

  @Test
  void aFailedBatchLeavesTheVersionAlone() {
    // The rule that prevents silent, permanent loss: recording the new version
    // after a partial run would make the next run's cheap check skip exactly the
    // entries this one missed.
    ContactSyncState state = new ContactSyncState();
    state.setCtag("ctag-old");
    state.setAddressBookHref(BOOK_URL);
    when(userEmailSettingService.getContactSyncState(USERNAME)).thenReturn(state);
    when(cardDavClient.getCtag(any(), anyString(), anyString())).thenReturn("ctag-new");
    when(cardDavClient.listResourceEtags(any(), anyString(), anyString())).thenReturn(Map.of("/dav/jane.vcf", "\"v1\""));
    when(emailContactStorage.getCardDavRows(USERNAME, CONNECTOR_ID)).thenReturn(List.of());
    when(cardDavClient.multiget(any(), any(), anyString(), anyString())).thenThrow(new CardDavException("boom"));

    syncService.syncAddressBook(USERNAME);

    ArgumentCaptor<ContactSyncState> saved = ArgumentCaptor.forClass(ContactSyncState.class);
    verify(userEmailSettingService, times(2)).setContactSyncState(saved.capture(), eq(USERNAME));
    ContactSyncState finalState = saved.getAllValues().get(saved.getAllValues().size() - 1);
    assertEquals("ctag-old", finalState.getCtag(), "the new version is only recorded once a run saw everything");
    assertEquals(SyncStatus.SUCCESS, finalState.getStatus(), "and what did land is still a success");
  }

  @Test
  void repeatedFailuresPauseTheSyncAndForgetWhereTheBookWas() {
    ContactSyncState state = new ContactSyncState();
    state.setFailedAttempts(2);
    state.setAddressBookHref(BOOK_URL);
    when(userEmailSettingService.getContactSyncState(USERNAME)).thenReturn(state);
    when(cardDavClient.getCtag(any(), anyString(), anyString())).thenThrow(new CardDavException("401"));

    syncService.syncAddressBook(USERNAME);

    ArgumentCaptor<ContactSyncState> saved = ArgumentCaptor.forClass(ContactSyncState.class);
    verify(userEmailSettingService, times(2)).setContactSyncState(saved.capture(), eq(USERNAME));
    ContactSyncState finalState = saved.getAllValues().get(saved.getAllValues().size() - 1);
    assertEquals(SyncStatus.BLOCKED, finalState.getStatus(), "a wrong password does not fix itself by being retried");
    assertEquals(3, finalState.getFailedAttempts());
    assertNull(finalState.getAddressBookHref(), "the book may have moved, so the next attempt discovers it again");
  }

  @Test
  void aUserWhoDidNotAskForItIsNeverSynced() {
    UserEmailSetting setting = new UserEmailSetting();
    setting.setEmailConnectorId(String.valueOf(CONNECTOR_ID));
    setting.setCarddavEnabled(false);
    when(userEmailSettingService.getUserEmailSetting(USERNAME)).thenReturn(setting);

    syncService.syncAddressBook(USERNAME);

    verify(cardDavClient, never()).getCtag(any(), anyString(), anyString());
  }

  @Test
  void repointingTheConnectorDemotesTheOldRowsRatherThanDeletingThem() {
    // What actually happened on the bench: a connector was pointed at a second
    // server, every entry from the first was absent from the second, and rows with
    // no correspondence were deleted. A row missing from a DIFFERENT book proves
    // nothing about the person.
    ContactSyncState state = new ContactSyncState();
    state.setAddressBookHref("https://old.example.com/dav/alice/default/");
    state.setConfiguredUrl("https://old.example.com");
    when(userEmailSettingService.getContactSyncState(USERNAME)).thenReturn(state);
    givenServerHas(Map.of());
    when(emailContactStorage.getCardDavRows(USERNAME, CONNECTOR_ID)).thenReturn(List.of(row(4L,
                                                                                            "jane@example.com",
                                                                                            EmailContactSource.CARDDAV,
                                                                                            false,
                                                                                            0,
                                                                                            "/dav/jane.vcf",
                                                                                            "\"v1\"",
                                                                                            null,
                                                                                            PhotoOrigin.VCARD)));

    syncService.syncAddressBook(USERNAME);

    verify(emailContactStorage).demoteCardDavRow(4L, true);
    verify(emailContactStorage, never()).deleteContact(anyLong());
  }

  @Test
  void theConfiguredUrlCarriesThePersonItIsFor() {
    // Google puts the account inside the collection path, so one preset shared by
    // every user of a provider cannot hold it literally.
    EmailConnector connector = new EmailConnector();
    connector.setId(CONNECTOR_ID);
    connector.setCarddavUrl("https://www.googleapis.com/carddav/v1/principals/{email}/lists/default/");
    when(emailConnectorService.getEmailConnector(CONNECTOR_ID)).thenReturn(connector);
    when(cardDavClient.getCtag(any(), anyString(), anyString())).thenReturn("ctag-2");
    when(cardDavClient.listResourceEtags(any(), anyString(), anyString())).thenReturn(Map.of());

    syncService.syncAddressBook(USERNAME);

    verify(cardDavClient).discoverAddressBook(eq("https://www.googleapis.com/carddav/v1/principals/alice@example.com/lists/default/"),
                                              anyString(),
                                              anyString());
  }

  @Test
  void repointingTheConnectorDiscoversAgainRatherThanReusingTheOldBook() {
    ContactSyncState state = new ContactSyncState();
    state.setAddressBookHref("https://old.example.com/dav/alice/default/");
    state.setConfiguredUrl("https://old.example.com");
    state.setCtag("ctag-old");
    when(userEmailSettingService.getContactSyncState(USERNAME)).thenReturn(state);
    when(cardDavClient.getCtag(any(), anyString(), anyString())).thenReturn("ctag-2");
    when(cardDavClient.listResourceEtags(any(), anyString(), anyString())).thenReturn(Map.of());

    syncService.syncAddressBook(USERNAME);

    verify(cardDavClient).discoverAddressBook(eq("https://mail.example.com"), anyString(), anyString());
  }

  /**
   * Stubs what the server holds, with discovery already done.
   *
   * @param etags entry path to entry version
   */
  private void givenServerHas(Map<String, String> etags) {
    when(cardDavClient.getCtag(any(), anyString(), anyString())).thenReturn("ctag-2");
    when(cardDavClient.listResourceEtags(any(), anyString(), anyString())).thenReturn(etags);
    lenient().when(emailContactStorage.getCardDavRows(USERNAME, CONNECTOR_ID)).thenReturn(List.of());
  }

  /**
   * Stubs the fetch of one entry and what parsing it yields.
   *
   * @param resource the entry the server returns
   * @param card what the parser makes of it
   */
  private void givenServerReturns(ContactResource resource, ParsedVCard card) {
    when(cardDavClient.multiget(any(), any(), anyString(), anyString())).thenReturn(List.of(resource));
    when(vCardParser.parse(resource.vcard())).thenReturn(card);
  }

  @Test
  void rebindingToAnotherProviderLetsGoOfTheBookLeftBehind() {
    // The rows the old provider wrote answer to a connector this user no longer has.
    // Nothing else would ever look at them again: a sync only reads the rows of its
    // own connector, so without this they stay in the store for good.
    CardDavRow written = row(1L, "someone@old.example", EmailContactSource.CARDDAV, false, 0, "/old/1.vcf", "e1", null, PhotoOrigin.VCARD);
    CardDavRow corresponded = row(2L, "colleague@old.example", EmailContactSource.CARDDAV, false, 12, "/old/2.vcf", "e2", null, PhotoOrigin.VCARD);
    when(emailContactStorage.getAllCardDavRows(USERNAME)).thenReturn(List.of(written, corresponded));
    when(emailContactStorage.getCardDavRows(USERNAME, CONNECTOR_ID)).thenReturn(List.of());

    syncService.releaseUnboundBooks(USERNAME);

    // Only the old book ever vouched for the first one, so leaving the provider takes
    // it along. The second is someone the user actually writes to.
    verify(emailContactStorage).deleteContact(1L);
    verify(emailContactStorage).demoteCardDavRow(2L, true);
    verify(emailContactStorage, never()).deleteContact(2L);
    // And the favorite follows its row: dropped with the deleted one, kept with
    // the demoted one.
    verify(emailContactFavoriteService).removeFavorite(1L, USERNAME);
    verify(emailContactFavoriteService, never()).removeFavorite(2L, USERNAME);
  }

  @Test
  void theBookStillBoundIsLeftAlone() {
    CardDavRow mine = row(1L, "someone@example.com", EmailContactSource.CARDDAV, false, 0, "/1.vcf", "e1", null, PhotoOrigin.VCARD);
    when(emailContactStorage.getAllCardDavRows(USERNAME)).thenReturn(List.of(mine));
    when(emailContactStorage.getCardDavRows(USERNAME, CONNECTOR_ID)).thenReturn(List.of(mine));

    syncService.releaseUnboundBooks(USERNAME);

    // Saving settings raises this too. It must cost nothing when the binding has not
    // actually changed, or every save would throw away a book and re-download it.
    verify(emailContactStorage, never()).deleteContact(anyLong());
    verify(emailContactStorage, never()).demoteCardDavRow(anyLong(), anyBoolean());
    verify(userEmailSettingService, never()).setContactSyncState(any(), anyString());
  }

  @Test
  void switchingTheBookOffReleasesEvenTheBoundConnectorsRows() {
    UserEmailSetting off = new UserEmailSetting();
    off.setEmailConnectorId(String.valueOf(CONNECTOR_ID));
    off.setCarddavEnabled(false);
    when(userEmailSettingService.getUserEmailSetting(USERNAME)).thenReturn(off);
    CardDavRow mine = row(1L, "someone@example.com", EmailContactSource.CARDDAV, false, 0, "/1.vcf", "e1", null, PhotoOrigin.VCARD);
    when(emailContactStorage.getAllCardDavRows(USERNAME)).thenReturn(List.of(mine));

    syncService.releaseUnboundBooks(USERNAME);

    verify(emailContactStorage).deleteContact(1L);
  }

  @Test
  void releasingForgetsWhereTheBookWas() {
    CardDavRow written = row(1L, "someone@old.example", EmailContactSource.CARDDAV, false, 0, "/old/1.vcf", "e1", null, PhotoOrigin.VCARD);
    when(emailContactStorage.getAllCardDavRows(USERNAME)).thenReturn(List.of(written));
    when(emailContactStorage.getCardDavRows(USERNAME, CONNECTOR_ID)).thenReturn(List.of());

    syncService.releaseUnboundBooks(USERNAME);

    // The discovered URL and version belonged to the binding that has gone. Kept, the
    // next sync would ask the new provider for the old provider's book.
    ArgumentCaptor<ContactSyncState> captor = ArgumentCaptor.forClass(ContactSyncState.class);
    verify(userEmailSettingService).setContactSyncState(captor.capture(), eq(USERNAME));
    assertNull(captor.getValue().getAddressBookHref());
    assertNull(captor.getValue().getCtag());
  }

  @Test
  void aHostTypedWithoutASchemeIsStillAHost() {
    EmailConnector bare = new EmailConnector();
    bare.setId(CONNECTOR_ID);
    bare.setCarddavUrl("webmail.example.com/dav/");
    when(emailConnectorService.getEmailConnector(CONNECTOR_ID)).thenReturn(bare);
    when(cardDavClient.listResourceEtags(any(), anyString(), anyString())).thenReturn(Map.of());
    when(emailContactStorage.getCardDavRows(USERNAME, CONNECTOR_ID)).thenReturn(List.of());

    syncService.syncAddressBook(USERNAME, true);

    // What an administrator types is a host. Refusing it produced an error about
    // our URI parser, which says nothing about what to fix.
    verify(cardDavClient).discoverAddressBook(eq("https://webmail.example.com/dav/"), anyString(), anyString());
  }

  @Test
  void aFailureThatIsNotTheServersIsStillRecorded() {
    // An exception thrown before any request used to be logged and forgotten,
    // leaving the stored status at whatever the last good run wrote -- so the page
    // reported a sync that never ran.
    when(cardDavClient.discoverAddressBook(anyString(), anyString(), anyString()))
                                                                                  .thenThrow(new IllegalArgumentException("URI with undefined scheme"));

    syncService.syncAddressBook(USERNAME, true);

    // The last word on the run is what the page reads.
    ArgumentCaptor<ContactSyncState> captor = ArgumentCaptor.forClass(ContactSyncState.class);
    verify(userEmailSettingService, atLeastOnce()).setContactSyncState(captor.capture(), eq(USERNAME));
    assertEquals(SyncStatus.FAILURE, captor.getValue().getStatus());
  }

  @Test
  void aScheduledRunWaitsOutTheSyncPeriod() {
    // The mailbox syncs every few minutes. Reading the address book each time
    // would be hundreds of provider requests a day for a book most people change
    // monthly, and providers rate-limit.
    ContactSyncState recent = new ContactSyncState();
    recent.setLastSyncStartDate(System.currentTimeMillis() - 60_000L);
    when(userEmailSettingService.getContactSyncState(USERNAME)).thenReturn(recent);

    syncService.syncAddressBookIfDue(USERNAME);

    verify(cardDavClient, never()).discoverAddressBook(anyString(), anyString(), anyString());
  }

  @Test
  void aScheduledRunGoesAheadOnceThePeriodHasPassed() {
    ContactSyncState old = new ContactSyncState();
    old.setLastSyncStartDate(System.currentTimeMillis() - 7L * 3600_000L);
    when(userEmailSettingService.getContactSyncState(USERNAME)).thenReturn(old);
    when(cardDavClient.listResourceEtags(any(), anyString(), anyString())).thenReturn(Map.of());
    when(emailContactStorage.getCardDavRows(USERNAME, CONNECTOR_ID)).thenReturn(List.of());

    syncService.syncAddressBookIfDue(USERNAME);

    verify(cardDavClient).discoverAddressBook(anyString(), anyString(), anyString());
  }

  @Test
  void anAddressBookThatHasNeverSyncedRunsAtTheFirstOpportunity() {
    when(userEmailSettingService.getContactSyncState(USERNAME)).thenReturn(new ContactSyncState());
    when(cardDavClient.listResourceEtags(any(), anyString(), anyString())).thenReturn(Map.of());
    when(emailContactStorage.getCardDavRows(USERNAME, CONNECTOR_ID)).thenReturn(List.of());

    syncService.syncAddressBookIfDue(USERNAME);

    verify(cardDavClient).discoverAddressBook(anyString(), anyString(), anyString());
  }

  /**
   * A stored row.
   *
   * @param id the row id
   * @param address its address
   * @param source where it came from
   * @param suppressed whether the user hid it
   * @param seenCount its correspondence history
   * @param href the server entry, or null
   * @param etag the version synced, or null
   * @param photoFileId its picture, or null
   * @param photoOrigin who that picture belongs to
   * @return the row
   */
  private CardDavRow row(long id,
                         String address,
                         String source,
                         boolean suppressed,
                         long seenCount,
                         String href,
                         String etag,
                         Long photoFileId,
                         PhotoOrigin photoOrigin) {
    return new CardDavRow(id, address, source, suppressed, seenCount, href, etag, photoFileId, photoOrigin);
  }

  // -------------------------------------------------------------------------
  // Publishing -- slice 1 of write-back, creates only. Every guard is pinned:
  // each one is what stands between "save my contact" and writing into
  // somebody's real address book by mistake.
  // -------------------------------------------------------------------------

  @Test
  void publishRefusesWhenTheAdministratorTurnedItOff() {
    System.setProperty(EmailContactCardDavSyncService.PUBLISH_ENABLED_PROPERTY, "false");
    try {
      IllegalArgumentException refusal =
                                       assertThrows(IllegalArgumentException.class,
                                                    () -> syncService.publishContact(USERNAME, 5L));
      assertEquals(EmailContactCardDavSyncService.PUBLISH_DISABLED, refusal.getMessage());
      verify(cardDavClient, never()).putVCard(anyString(), anyString(), anyString(), anyString(), anyString());
    } finally {
      System.clearProperty(EmailContactCardDavSyncService.PUBLISH_ENABLED_PROPERTY);
    }
  }

  @Test
  void publishAnswersNullForAContactTheCallerDoesNotOwn() {
    // getContact already folds "absent", "somebody else's" and "suppressed"
    // into null, and publish must not distinguish them either: a null is the
    // REST layer's 404, and 404-never-403 is what keeps ids unprobeable.
    when(emailContactService.getContact(5L, USERNAME)).thenReturn(null);

    assertNull(syncService.publishContact(USERNAME, 5L));
    verify(cardDavClient, never()).putVCard(anyString(), anyString(), anyString(), anyString(), anyString());
  }

  @Test
  void aDirectoryContactIsNeverPublished() {
    // The company directory in a personal address book is an export nobody
    // asked for, not a sync. This is the guard the task exists to keep.
    when(emailContactService.getContact(5L, USERNAME)).thenReturn(ownContact(5L, EmailContactSource.DIRECTORY));

    IllegalArgumentException refusal = assertThrows(IllegalArgumentException.class,
                                                    () -> syncService.publishContact(USERNAME, 5L));
    assertEquals(EmailContactCardDavSyncService.PUBLISH_SOURCE_NOT_ALLOWED, refusal.getMessage());
    verify(cardDavClient, never()).putVCard(anyString(), anyString(), anyString(), anyString(), anyString());
  }

  @Test
  void anAddressBookRowIsNotPublishedTwice() {
    when(emailContactService.getContact(5L, USERNAME)).thenReturn(ownContact(5L, EmailContactSource.CARDDAV));

    IllegalStateException refusal = assertThrows(IllegalStateException.class,
                                                 () -> syncService.publishContact(USERNAME, 5L));
    assertEquals(EmailContactCardDavSyncService.PUBLISH_ALREADY_PUBLISHED, refusal.getMessage());
    verify(cardDavClient, never()).putVCard(anyString(), anyString(), anyString(), anyString(), anyString());
  }

  @Test
  void publishRefusesWithoutABoundAddressBook() {
    when(emailContactService.getContact(5L, USERNAME)).thenReturn(ownContact(5L, EmailContactSource.MANUAL));
    userEmailSettingService.getUserEmailSetting(USERNAME).setCarddavEnabled(false);

    IllegalArgumentException refusal = assertThrows(IllegalArgumentException.class,
                                                    () -> syncService.publishContact(USERNAME, 5L));
    assertEquals(EmailContactCardDavSyncService.PUBLISH_NO_ADDRESS_BOOK, refusal.getMessage());
    verify(cardDavClient, never()).putVCard(anyString(), anyString(), anyString(), anyString(), anyString());
  }

  @Test
  void publishRefusesUntilDiscoveryHasSucceededOnce() {
    // The one guard that makes the write trustworthy: a blank stored href means
    // no sync ever verified the book exists, and publishing would write to a
    // configured guess. The refusal must NOT trigger discovery either -- the fix
    // is a sync, initiated as a sync.
    when(emailContactService.getContact(5L, USERNAME)).thenReturn(ownContact(5L, EmailContactSource.MANUAL));
    when(userEmailSettingService.getContactSyncState(USERNAME)).thenReturn(new ContactSyncState());

    IllegalArgumentException refusal = assertThrows(IllegalArgumentException.class,
                                                    () -> syncService.publishContact(USERNAME, 5L));
    assertEquals(EmailContactCardDavSyncService.PUBLISH_NOT_DISCOVERED, refusal.getMessage());
    verify(cardDavClient, never()).discoverAddressBook(anyString(), anyString(), anyString());
    verify(cardDavClient, never()).putVCard(anyString(), anyString(), anyString(), anyString(), anyString());
  }

  @Test
  void aPublishedContactIsBoundToTheEntryItBecame() {
    when(emailContactService.getContact(5L, USERNAME)).thenReturn(ownContact(5L, EmailContactSource.MANUAL));
    givenADiscoveredBook();
    when(emailContactVCardService.getPublishVCard(any(), anyString())).thenReturn("BEGIN:VCARD\nEND:VCARD\n");
    when(cardDavClient.putVCard(anyString(), anyString(), anyString(), anyString(), anyString()))
                      .thenReturn(new PutResult(201, "\"etag-9\"", null));

    EmailContact published = syncService.publishContact(USERNAME, 5L);

    assertNotNull(published);
    ArgumentCaptor<String> href = ArgumentCaptor.forClass(String.class);
    ArgumentCaptor<String> condition = ArgumentCaptor.forClass(String.class);
    verify(cardDavClient).putVCard(href.capture(), anyString(), condition.capture(), eq("alice@example.com"), eq("secret"));
    assertTrue(href.getValue().startsWith(BOOK_URL), "the card goes into the book discovery verified, nowhere else");
    assertTrue(href.getValue().endsWith(".vcf"));
    assertEquals("*", condition.getValue(), "creates-only: the server is told to refuse an existing entry");
    String uid = href.getValue().substring(BOOK_URL.length(), href.getValue().length() - ".vcf".length());
    // The identity in the card is the identity in the URL is the identity on
    // the row -- one uid, three places, which is what lets the next sync
    // recognise the entry as the row it already has.
    verify(emailContactVCardService).getPublishVCard(any(), eq(uid));
    // Bound as the server's LISTING will name the entry -- the path, not the
    // absolute URL that was PUT. Synced rows store PROPFIND's href shape and
    // the reconciliation compares by string equality; an absolute URL here can
    // never match a listing and made a published row look vanished.
    verify(emailContactStorage).bindPublishedCard(5L, CONNECTOR_ID, "/dav/alice/default/" + uid + ".vcf", "\"etag-9\"", uid);
    // The stored ctag is not advanced: a post-PUT ctag could also cover another
    // client's concurrent change, and recording it would skip that change for
    // good. One redundant reconcile is the price, paid knowingly.
    verify(userEmailSettingService, never()).setContactSyncState(any(), anyString());
  }

  @Test
  void theServersLocationNamesTheEntryNotOurUrl() {
    // BlueMind files a PUT card under a path of its own choosing and says so in
    // Location. Binding the minted URL then bookkeeps an entry that does not
    // exist -- the first live test watched the next sync delete the contact
    // over exactly this.
    when(emailContactService.getContact(5L, USERNAME)).thenReturn(ownContact(5L, EmailContactSource.MANUAL));
    givenADiscoveredBook();
    when(emailContactVCardService.getPublishVCard(any(), anyString())).thenReturn("BEGIN:VCARD\nEND:VCARD\n");
    when(cardDavClient.putVCard(anyString(), anyString(), anyString(), anyString(), anyString()))
                      .thenReturn(new PutResult(201,
                                                "\"etag-9\"",
                                                "https://mail.example.com/dav/alice/default/server-chosen.vcf"));

    syncService.publishContact(USERNAME, 5L);

    verify(emailContactStorage).bindPublishedCard(eq(5L),
                                                  eq(CONNECTOR_ID),
                                                  eq("/dav/alice/default/server-chosen.vcf"),
                                                  eq("\"etag-9\""),
                                                  anyString());
  }

  @Test
  void aServerRefusingToCreateIsReportedNeverForced() {
    when(emailContactService.getContact(5L, USERNAME)).thenReturn(ownContact(5L, EmailContactSource.COLLECTED));
    givenADiscoveredBook();
    when(emailContactVCardService.getPublishVCard(any(), anyString())).thenReturn("BEGIN:VCARD\nEND:VCARD\n");
    when(cardDavClient.putVCard(anyString(), anyString(), anyString(), anyString(), anyString()))
                      .thenReturn(new PutResult(412, null, null));

    IllegalStateException refusal = assertThrows(IllegalStateException.class,
                                                 () -> syncService.publishContact(USERNAME, 5L));

    assertEquals(EmailContactCardDavSyncService.PUBLISH_EXISTS_ON_SERVER, refusal.getMessage());
    // Nothing local moves either: a refused create must leave the row exactly
    // the manual/collected contact it was.
    verify(emailContactStorage, never()).bindPublishedCard(anyLong(), anyLong(), anyString(), anyString(), anyString());
  }

  @Test
  void aServerThatSendsNoEtagLeavesTheVersionUnknown() {
    when(emailContactService.getContact(5L, USERNAME)).thenReturn(ownContact(5L, EmailContactSource.MANUAL));
    givenADiscoveredBook();
    when(emailContactVCardService.getPublishVCard(any(), anyString())).thenReturn("BEGIN:VCARD\nEND:VCARD\n");
    when(cardDavClient.putVCard(anyString(), anyString(), anyString(), anyString(), anyString()))
                      .thenReturn(new PutResult(204, null, null));

    syncService.publishContact(USERNAME, 5L);

    // Null on purpose: an unknown version makes the next sync re-read the entry
    // and settle on the server's canonical etag, which costs one fetch and can
    // never be wrong. Inventing a version here could be.
    verify(emailContactStorage).bindPublishedCard(eq(5L), eq(CONNECTOR_ID), anyString(), isNull(), anyString());
  }

  @Test
  void publishAvailabilityNeedsADiscoveredBook() {
    // The UI's question, answered from stored state with no network: the button
    // must not exist while nothing verified has ever been discovered.
    when(userEmailSettingService.getContactSyncState(USERNAME)).thenReturn(new ContactSyncState());
    assertFalse(syncService.isPublishAvailable(USERNAME));

    givenADiscoveredBook();
    assertTrue(syncService.isPublishAvailable(USERNAME));
  }

  // -------------------------------------------------------------------------
  // Editing -- slice 2 of write-back. The rule under every case: the path goes
  // from "edit refused" straight to "edit pushed", never through "edit kept
  // locally, diverging quietly" -- and a push that cannot happen leaves the
  // edit refused.
  // -------------------------------------------------------------------------

  @Test
  void editRefusesWhenTheAdministratorTurnedWritingOff() {
    System.setProperty(EmailContactCardDavSyncService.PUBLISH_ENABLED_PROPERTY, "false");
    try {
      IllegalArgumentException refusal =
                                       assertThrows(IllegalArgumentException.class,
                                                    () -> syncService.updateAddressBookContact(USERNAME, 5L, editedBody()));
      assertEquals(EmailContactCardDavSyncService.PUBLISH_DISABLED, refusal.getMessage());
      verify(cardDavClient, never()).updateVCard(anyString(), anyString(), anyString(), anyString(), anyString());
    } finally {
      System.clearProperty(EmailContactCardDavSyncService.PUBLISH_ENABLED_PROPERTY);
    }
  }

  @Test
  void anEditOfANonAddressBookRowDoesNotGoNearTheServer() {
    when(emailContactService.getContact(5L, USERNAME)).thenReturn(ownContact(5L, EmailContactSource.MANUAL));

    IllegalArgumentException refusal =
                                     assertThrows(IllegalArgumentException.class,
                                                  () -> syncService.updateAddressBookContact(USERNAME, 5L, editedBody()));
    assertEquals(EmailContactCardDavSyncService.UPDATE_NOT_ADDRESS_BOOK, refusal.getMessage());
    verify(cardDavClient, never()).fetchVCard(anyString(), anyString(), anyString());
  }

  @Test
  void aRowWithoutAServerEntryStaysRefused() {
    // A CARDDAV row with no href has nowhere verified to push to, and "cannot
    // push" must mean "still refused", never "saved locally meanwhile".
    when(emailContactService.getContact(5L, USERNAME)).thenReturn(ownContact(5L, EmailContactSource.CARDDAV));
    givenADiscoveredBook();
    when(emailContactStorage.getCardDavRowById(5L)).thenReturn(row(5L,
                                                                   "bob@example.org",
                                                                   EmailContactSource.CARDDAV,
                                                                   false,
                                                                   0,
                                                                   null,
                                                                   null,
                                                                   null,
                                                                   PhotoOrigin.VCARD));

    IllegalStateException refusal = assertThrows(IllegalStateException.class,
                                                 () -> syncService.updateAddressBookContact(USERNAME, 5L, editedBody()));
    assertEquals(EmailContactCardDavSyncService.UPDATE_NO_SERVER_ENTRY, refusal.getMessage());
    verify(cardDavClient, never()).fetchVCard(anyString(), anyString(), anyString());
    verify(emailContactStorage, never()).saveCardDavContact(anyString(), any(), any(), any(), any(), any(), any(), anyBoolean());
  }

  @Test
  void anAddressCollisionIsRefusedBeforeAnythingReachesTheServer() {
    // Pushing a card the local save would then refuse as a collision would
    // desynchronize the two on purpose, so the refusal comes first.
    when(emailContactService.getContact(5L, USERNAME)).thenReturn(ownContact(5L, EmailContactSource.CARDDAV));
    givenADiscoveredBook();
    givenABoundRow();
    when(emailContactStorage.getCardDavRowByAddress(USERNAME, "bob@example.org"))
                                                                                 .thenReturn(row(9L,
                                                                                                 "bob@example.org",
                                                                                                 EmailContactSource.MANUAL,
                                                                                                 false,
                                                                                                 0,
                                                                                                 null,
                                                                                                 null,
                                                                                                 null,
                                                                                                 PhotoOrigin.USER));

    IllegalStateException refusal = assertThrows(IllegalStateException.class,
                                                 () -> syncService.updateAddressBookContact(USERNAME, 5L, editedBody()));
    assertEquals(EmailContactService.CONTACT_ALREADY_EXISTS, refusal.getMessage());
    verify(cardDavClient, never()).fetchVCard(anyString(), anyString(), anyString());
  }

  @Test
  void anEntryGoneFromTheServerRefusesTheEditInsteadOfRecreatingIt() {
    when(emailContactService.getContact(5L, USERNAME)).thenReturn(ownContact(5L, EmailContactSource.CARDDAV));
    givenADiscoveredBook();
    givenABoundRow();
    when(cardDavClient.fetchVCard(anyString(), anyString(), anyString())).thenReturn(null);

    IllegalStateException refusal = assertThrows(IllegalStateException.class,
                                                 () -> syncService.updateAddressBookContact(USERNAME, 5L, editedBody()));
    assertEquals(EmailContactCardDavSyncService.UPDATE_ENTRY_GONE, refusal.getMessage());
    verify(cardDavClient, never()).updateVCard(anyString(), anyString(), anyString(), anyString(), anyString());
  }

  @Test
  void theEditGoesOutUnderTheEtagOfItsOwnFetchNotTheStoredOne() {
    // The whole If-Match contract: the merge's base and the precondition's
    // version are the SAME read. The etag the last sync stored may be days
    // old, and sending it would refuse every edit of a since-synced entry --
    // or worse, let one through against a base the merge never saw.
    when(emailContactService.getContact(5L, USERNAME)).thenReturn(ownContact(5L, EmailContactSource.CARDDAV));
    givenADiscoveredBook();
    givenABoundRow();
    when(cardDavClient.fetchVCard(anyString(), anyString(), anyString()))
                      .thenReturn(new ContactResource(BOOK_URL + "bob.vcf", "\"fresh\"", "RAW-CARD"));
    when(vCardParser.merge(eq("RAW-CARD"), any())).thenReturn("MERGED-CARD");
    when(cardDavClient.updateVCard(anyString(), anyString(), anyString(), anyString(), anyString()))
                      .thenReturn(new PutResult(204, "\"after\"", null));
    when(vCardParser.parse("MERGED-CARD")).thenReturn(card("Bobby", "bob@example.org"));

    syncService.updateAddressBookContact(USERNAME, 5L, editedBody());

    verify(cardDavClient).fetchVCard(eq(BOOK_URL + "bob.vcf"), eq("alice@example.com"), eq("secret"));
    verify(cardDavClient).updateVCard(eq(BOOK_URL + "bob.vcf"),
                                      eq("MERGED-CARD"),
                                      eq("\"fresh\""),
                                      eq("alice@example.com"),
                                      eq("secret"));
    // The local row becomes the merged card AS PUSHED, through the inbound
    // sync's own write, at the version the PUT answered -- photo untouched.
    verify(emailContactStorage).saveCardDavContact(eq(USERNAME),
                                                   eq(5L),
                                                   eq(CONNECTOR_ID),
                                                   eq("/dav/alice/default/bob.vcf"),
                                                   eq("\"after\""),
                                                   any(),
                                                   isNull(),
                                                   eq(false));
  }

  @Test
  void theEtagTheServerAnswersIsTheOneRecorded() {
    // A server may answer the PUT with any etag it likes -- or none. Whatever
    // it says is what the row records: null makes the next sync re-read the
    // entry, which costs one fetch and can never be wrong.
    when(emailContactService.getContact(5L, USERNAME)).thenReturn(ownContact(5L, EmailContactSource.CARDDAV));
    givenADiscoveredBook();
    givenABoundRow();
    when(cardDavClient.fetchVCard(anyString(), anyString(), anyString()))
                      .thenReturn(new ContactResource(BOOK_URL + "bob.vcf", "\"fresh\"", "RAW-CARD"));
    when(vCardParser.merge(eq("RAW-CARD"), any())).thenReturn("MERGED-CARD");
    when(cardDavClient.updateVCard(anyString(), anyString(), anyString(), anyString(), anyString()))
                      .thenReturn(new PutResult(204, null, null));
    when(vCardParser.parse("MERGED-CARD")).thenReturn(card("Bobby", "bob@example.org"));

    syncService.updateAddressBookContact(USERNAME, 5L, editedBody());

    verify(emailContactStorage).saveCardDavContact(eq(USERNAME),
                                                   eq(5L),
                                                   eq(CONNECTOR_ID),
                                                   anyString(),
                                                   isNull(),
                                                   any(),
                                                   isNull(),
                                                   eq(false));
  }

  @Test
  void aConflictNeverOverwritesAndTheServersCardBecomesTheBaseline() {
    // Somebody changed the entry between this code's fetch and its PUT. Their
    // change must win everywhere: the PUT is refused by the server (If-Match),
    // the local row adopts the server's CURRENT card -- re-fetched, because
    // the 412 proves the save-time fetch is already behind -- and the user
    // gets the conflict, their words still on their screen, never applied.
    when(emailContactService.getContact(5L, USERNAME)).thenReturn(ownContact(5L, EmailContactSource.CARDDAV));
    givenADiscoveredBook();
    givenABoundRow();
    when(cardDavClient.fetchVCard(anyString(), anyString(), anyString()))
                      .thenReturn(new ContactResource(BOOK_URL + "bob.vcf", "\"fresh\"", "RAW-CARD"))
                      .thenReturn(new ContactResource(BOOK_URL + "bob.vcf", "\"theirs\"", "THEIR-CARD"));
    when(vCardParser.merge(eq("RAW-CARD"), any())).thenReturn("MERGED-CARD");
    when(cardDavClient.updateVCard(anyString(), anyString(), anyString(), anyString(), anyString()))
                      .thenReturn(new PutResult(412, null, null));
    when(vCardParser.parse("THEIR-CARD")).thenReturn(card("Their Bob", "bob@example.org"));

    IllegalStateException refusal = assertThrows(IllegalStateException.class,
                                                 () -> syncService.updateAddressBookContact(USERNAME, 5L, editedBody()));

    assertEquals(EmailContactCardDavSyncService.UPDATE_CONFLICT, refusal.getMessage());
    // The baseline written is THEIR card at THEIR version -- the user's merged
    // card is never stored, locally or remotely.
    verify(vCardParser, never()).parse("MERGED-CARD");
    verify(emailContactStorage).saveCardDavContact(eq(USERNAME),
                                                   eq(5L),
                                                   eq(CONNECTOR_ID),
                                                   eq("/dav/alice/default/bob.vcf"),
                                                   eq("\"theirs\""),
                                                   any(),
                                                   isNull(),
                                                   eq(false));
  }

  @Test
  void aCardTheMergeCannotReadLeavesTheEditRefused() {
    // Cannot merge safely means cannot push, and cannot push means still
    // refused: the one path that must never exist is "kept locally anyway".
    when(emailContactService.getContact(5L, USERNAME)).thenReturn(ownContact(5L, EmailContactSource.CARDDAV));
    givenADiscoveredBook();
    givenABoundRow();
    when(cardDavClient.fetchVCard(anyString(), anyString(), anyString()))
                      .thenReturn(new ContactResource(BOOK_URL + "bob.vcf", "\"fresh\"", "GARBAGE"));
    when(vCardParser.merge(eq("GARBAGE"), any())).thenReturn(null);

    assertThrows(CardDavException.class, () -> syncService.updateAddressBookContact(USERNAME, 5L, editedBody()));
    verify(cardDavClient, never()).updateVCard(anyString(), anyString(), anyString(), anyString(), anyString());
    verify(emailContactStorage, never()).saveCardDavContact(anyString(), any(), any(), any(), any(), any(), any(), anyBoolean());
  }

  /**
   * Primes the row as the sync would have bound it: an entry of the current
   * book, stored under the listing-shaped href, at a version the LAST sync
   * saw — which the If-Match test proves is not the one the push uses.
   */
  private void givenABoundRow() {
    when(emailContactStorage.getCardDavRowById(5L)).thenReturn(row(5L,
                                                                   "bob@example.org",
                                                                   EmailContactSource.CARDDAV,
                                                                   false,
                                                                   0,
                                                                   "/dav/alice/default/bob.vcf",
                                                                   "\"stale\"",
                                                                   null,
                                                                   PhotoOrigin.VCARD));
  }

  /**
   * The body a form save sends for the bound row: same primary, a new given
   * name, the always-present authoritative lists.
   *
   * @return the edit
   */
  private EmailContact editedBody() {
    EmailContact edited = new EmailContact();
    edited.setPrimaryEmail("bob@example.org");
    edited.setGivenName("Bobby");
    edited.setSecondaryEmails(List.of());
    edited.setPhones(List.of());
    return edited;
  }

  /**
   * Primes the stored state as after a successful discovery: the book's href is
   * known and belongs to the currently configured URL, so resolution answers
   * from state without any network.
   */
  private void givenADiscoveredBook() {
    ContactSyncState state = new ContactSyncState();
    state.setAddressBookHref(BOOK_URL);
    state.setConfiguredUrl("https://mail.example.com");
    when(userEmailSettingService.getContactSyncState(USERNAME)).thenReturn(state);
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

  /**
   * An entry as the server returns it.
   *
   * @param href its path
   * @param etag its version
   * @return the entry
   */
  private ContactResource resource(String href, String etag) {
    return new ContactResource(href, etag, "BEGIN:VCARD\nEND:VCARD");
  }

  /**
   * A parsed vCard.
   *
   * @param name the formatted name
   * @param address the address, or null for an entry with none
   * @return the parsed card
   */
  private ParsedVCard card(String name, String address) {
    return new ParsedVCard("uid-1",
                           name,
                           null,
                           null,
                           address == null ? List.of() : List.of(address),
                           List.of(),
                           null,
                           null,
                           null,
                           null,
                           null,
                           null,
                           null,
                           null);
  }

  /**
   * A parsed vCard carrying a picture.
   *
   * @param name the formatted name
   * @param address the address
   * @return the parsed card
   */
  private ParsedVCard cardWithPhoto(String name, String address) {
    return new ParsedVCard("uid-1",
                           name,
                           null,
                           null,
                           List.of(address),
                           List.of(),
                           null,
                           null,
                           null,
                           null,
                           null,
                           null,
                           new byte[] { 1, 2, 3 },
                           "image/jpeg");
  }
}
