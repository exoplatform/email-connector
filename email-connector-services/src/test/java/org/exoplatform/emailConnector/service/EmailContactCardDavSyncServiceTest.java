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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
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
import org.exoplatform.emailConnector.carddav.VCardParser;
import org.exoplatform.emailConnector.model.CardDavContactData;
import org.exoplatform.emailConnector.model.CardDavRow;
import org.exoplatform.emailConnector.model.ContactSyncState;
import org.exoplatform.emailConnector.model.EmailConnector;
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
  void anEntryWithNoAddressIsSkippedRatherThanFailingTheRun() {
    givenServerHas(Map.of("/dav/nobody.vcf", "\"v1\""));
    givenServerReturns(resource("/dav/nobody.vcf", "\"v1\""), card("Nobody", null));

    syncService.syncAddressBook(USERNAME);

    verify(emailContactStorage, never()).saveCardDavContact(anyString(),
                                                            any(),
                                                            anyLong(),
                                                            anyString(),
                                                            anyString(),
                                                            any(),
                                                            any(),
                                                            anyBoolean());
    verify(userEmailSettingService, times(2)).setContactSyncState(any(), eq(USERNAME));
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
                           new byte[] { 1, 2, 3 },
                           "image/jpeg");
  }
}
