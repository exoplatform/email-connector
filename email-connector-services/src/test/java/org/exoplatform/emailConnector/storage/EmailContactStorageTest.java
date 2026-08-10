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
package org.exoplatform.emailConnector.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import java.util.Date;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import org.exoplatform.commons.file.services.FileService;
import org.exoplatform.emailConnector.dao.EmailContactAddressDAO;
import org.exoplatform.emailConnector.dao.EmailContactDAO;
import org.exoplatform.emailConnector.entity.EmailContactAddressEntity;
import org.exoplatform.emailConnector.entity.EmailContactEntity;
import org.exoplatform.emailConnector.model.CardDavContactData;
import org.exoplatform.emailConnector.model.CardDavRow;
import org.exoplatform.emailConnector.model.EmailContact;
import org.exoplatform.emailConnector.model.EmailContactPage;
import org.exoplatform.emailConnector.model.EmailContactSource;
import org.exoplatform.emailConnector.model.PostalAddress;
import org.exoplatform.emailConnector.utils.EmailContactUtils;
import org.exoplatform.upload.UploadService;

@ExtendWith(MockitoExtension.class)
@SpringBootTest(classes = { EmailContactStorage.class })
public class EmailContactStorageTest {

  private static final String USERNAME = "alice";

  @MockBean
  private EmailContactDAO     emailContactDAO;

  @MockBean
  private EmailContactAddressDAO emailContactAddressDAO;

  @MockBean
  private UploadService       uploadService;

  @MockBean
  private FileService         fileService;

  @Autowired
  private EmailContactStorage emailContactStorage;

  @Test
  void letterIndexIsOrderedAndCollectsTheOtherBucketUnderHash() {
    // Buckets as the GROUP BY returns them: A (12 rows), B (3), and the
    // everything-else bucket (2) - which must come out LAST, as "#", exactly
    // where its rows sort (bucket 26 is the largest).
    when(emailContactDAO.findContacts(eq(USERNAME), isNull(), any(Pageable.class)))
                                                                                   .thenReturn(new PageImpl<>(List.of(),
                                                                                                              PageRequest.of(0, 100),
                                                                                                              17));
    when(emailContactDAO.countBySortBucket(USERNAME, null)).thenReturn(List.<Object[]> of(new Object[] { 0, 12L },
                                                                               new Object[] { 1, 3L },
                                                                               new Object[] { 26, 2L }));

    EmailContactPage page = emailContactStorage.getContacts(USERNAME, null, null, 0, 100);

    assertEquals(List.of("A", "B", "#"), List.copyOf(page.getLetterIndex().keySet()));
    assertEquals(Map.of("A", 12L, "B", 3L, "#", 2L), page.getLetterIndex());
    assertEquals(17, page.getSize());
  }

  @Test
  void browseOrdersByBucketThenSortNameThenId() {
    when(emailContactDAO.findContacts(eq(USERNAME), isNull(), any(Pageable.class)))
                                                                                   .thenReturn(new PageImpl<>(List.of()));
    when(emailContactDAO.countBySortBucket(USERNAME, null)).thenReturn(List.of());

    emailContactStorage.getContacts(USERNAME, null, null, 0, 100);

    ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
    verify(emailContactDAO).findContacts(eq(USERNAME), isNull(), pageable.capture());
    assertEquals("sortBucket: ASC,sortName: ASC,id: ASC", pageable.getValue().getSort().toString());
  }

  @Test
  void suggestOrdersByUsefulnessAndLowercasesTheTerm() {
    // The compose type-ahead's whole difference from browse is this ordering, so
    // it is asserted where it is expressed rather than only where it is executed.
    when(emailContactDAO.suggestContacts(eq(USERNAME), anyString(), any(Pageable.class))).thenReturn(List.of());

    emailContactStorage.suggestContacts(USERNAME, "  JaNe ", 7);

    ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
    verify(emailContactDAO).suggestContacts(eq(USERNAME), eq("jane"), pageable.capture());
    assertEquals("seenCount: DESC,lastSeenDate: DESC, NULLS_LAST,sortName: ASC,id: ASC",
                 pageable.getValue().getSort().toString());
    assertEquals(7, pageable.getValue().getPageSize());
    assertEquals(0, pageable.getValue().getPageNumber());
  }

  @Test
  void searchTermIsLowercasedAndSourceFilterRoutesToTheSourcesQuery() {
    Page<EmailContactEntity> empty = new PageImpl<>(List.of());
    when(emailContactDAO.findContactsBySources(eq(USERNAME), eq(List.of(EmailContactSource.COLLECTED)), anyString(),
                                               any(Pageable.class))).thenReturn(empty);
    when(emailContactDAO.countBySortBucketAndSources(eq(USERNAME), eq(List.of(EmailContactSource.COLLECTED)), anyString()))
                                                                                                                           .thenReturn(List.of());

    emailContactStorage.getContacts(USERNAME, List.of(EmailContactSource.COLLECTED), "  JaNe ", 0, 100);

    verify(emailContactDAO).findContactsBySources(eq(USERNAME),
                                                  eq(List.of(EmailContactSource.COLLECTED)),
                                                  eq("jane"),
                                                  any(Pageable.class));
  }

  @Test
  void contactsCarryTheLetterTheirStoredBucketSays() {
    EmailContactEntity entity = entity(1L, "zoe@example.com", "Zoe", 25);
    when(emailContactDAO.findContacts(eq(USERNAME), isNull(), any(Pageable.class)))
                                                                                   .thenReturn(new PageImpl<>(List.of(entity)));
    when(emailContactDAO.countBySortBucket(USERNAME, null)).thenReturn(List.<Object[]> of(new Object[] { 25, 1L }));

    EmailContactPage page = emailContactStorage.getContacts(USERNAME, null, null, 0, 100);

    assertEquals("Z", page.getContacts().get(0).getLetter());
  }

  @Test
  void aContactIsFoundByAnyOfItsAddresses() {
    // Not only by the one it happens to be filed under. The address table owns
    // uniqueness now, so this is one indexed read rather than a primary-column
    // hit followed by a LIKE over a joined string.
    EmailContactEntity entity = entity(7L, "jane@example.com", "Jane", 9);
    EmailContactAddressEntity address = new EmailContactAddressEntity();
    address.setContactId(7L);
    address.setUserId(USERNAME);
    address.setAddress("jane@other.org");
    when(emailContactAddressDAO.findByUserIdAndAddress(USERNAME, "jane@other.org")).thenReturn(Optional.of(address));
    when(emailContactDAO.findById(7L)).thenReturn(Optional.of(entity));

    EmailContact contact = emailContactStorage.getContactByAddress(USERNAME, "jane@other.org");

    assertEquals(7L, contact.getId());
  }

  @Test
  void everyAddressOfAContactIsFiledWhenItIsSaved() {
    when(emailContactDAO.save(any(EmailContactEntity.class))).thenAnswer(invocation -> {
      EmailContactEntity saved = invocation.getArgument(0);
      saved.setId(11L);
      return saved;
    });
    EmailContact contact = new EmailContact();
    contact.setUserId(USERNAME);
    contact.setSource(EmailContactSource.MANUAL);
    contact.setPrimaryEmail("jane@example.com");
    contact.setSecondaryEmails(List.of("jane@other.org"));

    emailContactStorage.createContact(contact);

    ArgumentCaptor<EmailContactAddressEntity> filed = ArgumentCaptor.forClass(EmailContactAddressEntity.class);
    verify(emailContactAddressDAO, times(2)).save(filed.capture());
    assertEquals(List.of("jane@example.com", "jane@other.org"),
                 filed.getAllValues().stream().map(EmailContactAddressEntity::getAddress).toList());
  }

  @Test
  void aContactWithNoAddressCanBeStored() {
    // The point of the whole change: an address book entry with a phone number and
    // no mail address is an ordinary contact, and the old model could not hold one.
    when(emailContactDAO.save(any(EmailContactEntity.class))).thenAnswer(invocation -> {
      EmailContactEntity saved = invocation.getArgument(0);
      saved.setId(12L);
      return saved;
    });
    EmailContact contact = new EmailContact();
    contact.setUserId(USERNAME);
    contact.setSource(EmailContactSource.CARDDAV);
    contact.setDisplayName("Somebody With A Phone");

    EmailContact stored = emailContactStorage.createContact(contact);

    assertEquals(12L, stored.getId());
    verify(emailContactAddressDAO, never()).save(any(EmailContactAddressEntity.class));
  }

  @Test
  void createDerivesSortKeyBucketAndDates() {
    when(emailContactDAO.save(any(EmailContactEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));
    EmailContact contact = new EmailContact();
    contact.setUserId(USERNAME);
    contact.setSource(EmailContactSource.COLLECTED);
    contact.setPrimaryEmail("erwin.mueller@example.com");
    contact.setDisplayName("Érwin Müller");

    emailContactStorage.createContact(contact);

    ArgumentCaptor<EmailContactEntity> saved = ArgumentCaptor.forClass(EmailContactEntity.class);
    verify(emailContactDAO).save(saved.capture());
    assertEquals("ERWIN MULLER", saved.getValue().getSortName());
    assertEquals(4, saved.getValue().getSortBucket());
    assertNull(saved.getValue().getId());
    assertTrue(saved.getValue().getCreatedDate() != null && saved.getValue().getUpdatedDate() != null);
  }

  @Test
  void updateKeepsTheColumnsTheDtoKnowsNothingAbout() {
    // The CardDAV columns live only on the entity: the DTO cannot carry them, so
    // an update that rebuilt the row from the DTO erased them -- silently, and
    // only once phase 3 had filled them in. The created date has the same shape
    // of problem and used to be rescued by hand.
    Date created = new Date(1000L);
    EmailContactEntity stored = entity(7L, "jane@example.com", "Jane Doe", 10);
    stored.setHref("/dav/addressbooks/jane.vcf");
    stored.setEtag("\"abc123\"");
    stored.setVcardUid("uid-42");
    stored.setConnectorId(3L);
    stored.setCreatedDate(created);
    when(emailContactDAO.findById(7L)).thenReturn(java.util.Optional.of(stored));
    when(emailContactDAO.save(any(EmailContactEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));

    EmailContact contact = new EmailContact();
    contact.setId(7L);
    contact.setUserId(USERNAME);
    contact.setSource(EmailContactSource.COLLECTED);
    contact.setPrimaryEmail("jane@example.com");
    contact.setDisplayName("Jane Doe");
    contact.setOrganization("Acme");

    emailContactStorage.updateContact(contact);

    ArgumentCaptor<EmailContactEntity> saved = ArgumentCaptor.forClass(EmailContactEntity.class);
    verify(emailContactDAO).save(saved.capture());
    assertEquals("/dav/addressbooks/jane.vcf", saved.getValue().getHref());
    assertEquals("\"abc123\"", saved.getValue().getEtag());
    assertEquals("uid-42", saved.getValue().getVcardUid());
    assertEquals(3L, saved.getValue().getConnectorId());
    assertEquals(created, saved.getValue().getCreatedDate());
    // and the payload still lands
    assertEquals("Acme", saved.getValue().getOrganization());
  }

  @Test
  void theCardFieldsSurviveTheEntityRoundTrip() {
    // Each new column through both mappers: what the DTO says lands on the row,
    // and what the row holds comes back as the same DTO — the address as its
    // components, never re-joined into a string.
    when(emailContactDAO.save(any(EmailContactEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));
    EmailContact contact = new EmailContact();
    contact.setUserId(USERNAME);
    contact.setSource(EmailContactSource.MANUAL);
    contact.setPrimaryEmail("jane@example.com");
    contact.setBirthday("--04-12");
    contact.setPostalAddress(new PostalAddress("12 rue de la Paix", "Paris", "Île-de-France", "75002", "France"));
    contact.setNote("Met at FOSDEM.\nPrefers email.");
    contact.setWebsite("https://janedoe.example");

    EmailContact saved = emailContactStorage.createContact(contact);

    ArgumentCaptor<EmailContactEntity> written = ArgumentCaptor.forClass(EmailContactEntity.class);
    verify(emailContactDAO).save(written.capture());
    assertEquals("--04-12", written.getValue().getBirthday());
    assertEquals("12 rue de la Paix", written.getValue().getAddressStreet());
    assertEquals("Paris", written.getValue().getAddressCity());
    assertEquals("Île-de-France", written.getValue().getAddressRegion());
    assertEquals("75002", written.getValue().getAddressPostalCode());
    assertEquals("France", written.getValue().getAddressCountry());
    assertEquals("Met at FOSDEM.\nPrefers email.", written.getValue().getNote());
    assertEquals("https://janedoe.example", written.getValue().getWebsite());
    assertEquals(contact.getPostalAddress(), saved.getPostalAddress());
    assertEquals(contact.getBirthday(), saved.getBirthday());
    assertEquals(contact.getNote(), saved.getNote());
    assertEquals(contact.getWebsite(), saved.getWebsite());
  }

  @Test
  void aNoteOverTheCapIsTruncatedNotRefused() {
    when(emailContactDAO.save(any(EmailContactEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));
    EmailContact contact = new EmailContact();
    contact.setUserId(USERNAME);
    contact.setSource(EmailContactSource.MANUAL);
    contact.setPrimaryEmail("jane@example.com");
    contact.setNote("x".repeat(EmailContactUtils.MAX_NOTE_LENGTH + 100));

    emailContactStorage.createContact(contact);

    ArgumentCaptor<EmailContactEntity> written = ArgumentCaptor.forClass(EmailContactEntity.class);
    verify(emailContactDAO).save(written.capture());
    assertEquals(EmailContactUtils.MAX_NOTE_LENGTH, written.getValue().getNote().length());
  }

  /**
   * The sync's lookup, which used to ask the contact's PRIMARY_EMAIL column and
   * so could only ever recognise somebody filed under their preferred address.
   */
  @Test
  void theSyncsLookupFindsAContactHeldUnderASecondaryAddress() {
    EmailContactEntity entity = entity(9L, "jane@work.example", "Jane Doe", 3);
    EmailContactAddressEntity address = new EmailContactAddressEntity();
    address.setContactId(9L);
    address.setUserId(USERNAME);
    address.setAddress("jane@home.example");
    when(emailContactAddressDAO.findByUserIdAndAddress(USERNAME, "jane@home.example")).thenReturn(Optional.of(address));
    when(emailContactDAO.findById(9L)).thenReturn(Optional.of(entity));

    CardDavRow row = emailContactStorage.getCardDavRowByAddress(USERNAME, "jane@home.example");

    // Without this the card looks like a new person, a second row is attempted,
    // the unique index refuses it and three such runs BLOCK the address book.
    assertEquals(9L, row.id());
    verify(emailContactDAO, never()).findByUserIdAndPrimaryEmail(anyString(), anyString());
  }

  /**
   * An address left pointing at a contact that is gone holds the unique key with
   * nothing behind it, so the sync's lookup clears it the way collection does.
   */
  @Test
  void theSyncsLookupClearsAnAddressWhoseContactIsGone() {
    EmailContactAddressEntity orphan = new EmailContactAddressEntity();
    orphan.setContactId(404L);
    orphan.setUserId(USERNAME);
    orphan.setAddress("ghost@example.org");
    when(emailContactAddressDAO.findByUserIdAndAddress(USERNAME, "ghost@example.org")).thenReturn(Optional.of(orphan));
    when(emailContactDAO.findById(404L)).thenReturn(Optional.empty());

    assertNull(emailContactStorage.getCardDavRowByAddress(USERNAME, "ghost@example.org"));

    verify(emailContactAddressDAO).delete(orphan);
  }

  /**
   * Nothing cascades from the contact table, so the hard delete owes the address
   * table its own cleanup — at the one method every delete call site uses.
   */
  @Test
  void deletingAContactUnfilesTheAddressesItHeld() {
    emailContactStorage.deleteContact(5L);

    // Left behind, these still hold (USER_ID, ADDRESS): the next contact reaching
    // that address cannot be written at all, and the sync reports a failed run.
    verify(emailContactAddressDAO).deleteByContactId(5L);
    verify(emailContactDAO).deleteById(5L);
  }

  /**
   * A refresh adds what the card carries and keeps what it does not — the set is
   * a union, not the vCard's to replace.
   */
  @Test
  void aCardDavRefreshKeepsAddressesTheCardDoesNotCarry() {
    when(emailContactDAO.findById(9L)).thenReturn(Optional.of(entity(9L, "jane@work.example", "Jane Doe", 3)));
    when(emailContactDAO.save(any(EmailContactEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));
    EmailContactAddressEntity held = new EmailContactAddressEntity();
    held.setContactId(9L);
    held.setUserId(USERNAME);
    held.setAddress("jane@home.example");
    when(emailContactAddressDAO.findByContactId(9L)).thenReturn(List.of(held));
    when(emailContactAddressDAO.findByUserIdAndAddress(USERNAME, "jane@work.example")).thenReturn(Optional.empty());
    CardDavContactData data = cardData("jane@work.example");

    emailContactStorage.saveCardDavContact(USERNAME, 9L, 2L, "/dav/jane.vcf", "\"v1\"", data, null, false);

    // The address the card does not mention is still the person's: nothing may
    // unfile it behind their back.
    verify(emailContactAddressDAO, never()).deleteByContactId(anyLong());
    ArgumentCaptor<EmailContactAddressEntity> filed = ArgumentCaptor.forClass(EmailContactAddressEntity.class);
    verify(emailContactAddressDAO).save(filed.capture());
    assertEquals("jane@work.example", filed.getValue().getAddress());
  }

  /**
   * An address another contact already holds stays with them: the unique index
   * is the authority on who owns one, and one contested address must not fail
   * the whole run.
   */
  @Test
  void aCardDavRefreshLeavesAnAddressAnotherContactAlreadyHolds() {
    when(emailContactDAO.findById(9L)).thenReturn(Optional.of(entity(9L, "jane@work.example", "Jane Doe", 3)));
    when(emailContactDAO.save(any(EmailContactEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));
    when(emailContactAddressDAO.findByContactId(9L)).thenReturn(List.of());
    EmailContactAddressEntity someoneElses = new EmailContactAddressEntity();
    someoneElses.setContactId(42L);
    someoneElses.setUserId(USERNAME);
    someoneElses.setAddress("jane@work.example");
    when(emailContactAddressDAO.findByUserIdAndAddress(USERNAME, "jane@work.example")).thenReturn(Optional.of(someoneElses));

    emailContactStorage.saveCardDavContact(USERNAME, 9L, 2L, "/dav/jane.vcf", "\"v1\"", cardData("jane@work.example"), null, false);

    verify(emailContactAddressDAO, never()).save(any(EmailContactAddressEntity.class));
  }

  /**
   * A card carrying one address and nothing else.
   *
   * @param primaryEmail the address
   * @return the card data
   */
  private CardDavContactData cardData(String primaryEmail) {
    return new CardDavContactData(primaryEmail,
                                  List.of(),
                                  "Jane Doe",
                                  "Jane",
                                  "Doe",
                                  List.of(),
                                  null,
                                  null,
                                  null,
                                  null,
                                  null,
                                  null,
                                  "uid-1",
                                  null,
                                  null);
  }

  /**
   * A minimal stored row.
   *
   * @param id the row id
   * @param primaryEmail the address
   * @param displayName the name
   * @param sortBucket the stored bucket
   * @return the entity
   */
  @Test
  void aReboundMailboxHandsItsCollectedContactsToTheUser() {
    // The mailbox they were derived from is gone: they stay, as the user's own,
    // and stop ranking on correspondence that no longer exists here.
    EmailContactEntity collected = entity(1L, "jane@example.com", "Jane Doe", 10);
    collected.setSeenCount(42);
    collected.setLastSeenDate(new Date());
    when(emailContactDAO.findByUserIdAndSource(USERNAME, EmailContactSource.COLLECTED)).thenReturn(List.of(collected));

    int released = emailContactStorage.releaseCollectedContacts(USERNAME);

    assertEquals(1, released);
    ArgumentCaptor<EmailContactEntity> saved = ArgumentCaptor.forClass(EmailContactEntity.class);
    verify(emailContactDAO).save(saved.capture());
    assertEquals(EmailContactSource.MANUAL, saved.getValue().getSource());
    assertEquals(0, saved.getValue().getSeenCount());
    assertNull(saved.getValue().getLastSeenDate());
  }

  @Test
  void aSuppressedContactIsNotRevivedByTheHandover() {
    // A tombstone is a decision to not see somebody. Handing it over would put
    // them back in the list on the very screen where the account changed.
    EmailContactEntity tombstone = entity(2L, "removed@example.com", "Removed Person", 18);
    tombstone.setSuppressed(true);
    when(emailContactDAO.findByUserIdAndSource(USERNAME, EmailContactSource.COLLECTED)).thenReturn(List.of(tombstone));

    int released = emailContactStorage.releaseCollectedContacts(USERNAME);

    assertEquals(0, released);
    verify(emailContactDAO, never()).save(any());
  }

  private EmailContactEntity entity(Long id, String primaryEmail, String displayName, int sortBucket) {
    EmailContactEntity entity = new EmailContactEntity();
    entity.setId(id);
    entity.setUserId(USERNAME);
    entity.setSource(EmailContactSource.COLLECTED);
    entity.setPrimaryEmail(primaryEmail);
    entity.setDisplayName(displayName);
    entity.setSortBucket(sortBucket);
    return entity;
  }
}
