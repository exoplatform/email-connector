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
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
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
import org.exoplatform.emailConnector.dao.EmailContactDAO;
import org.exoplatform.emailConnector.entity.EmailContactEntity;
import org.exoplatform.emailConnector.model.EmailContact;
import org.exoplatform.emailConnector.model.EmailContactPage;
import org.exoplatform.emailConnector.model.EmailContactSource;
import org.exoplatform.upload.UploadService;

@ExtendWith(MockitoExtension.class)
@SpringBootTest(classes = { EmailContactStorage.class })
public class EmailContactStorageTest {

  private static final String USERNAME = "alice";

  @MockBean
  private EmailContactDAO     emailContactDAO;

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
  void addressLookupFallsBackToTheSecondaryAddresses() {
    EmailContactEntity entity = entity(7L, "jane@example.com", "Jane", 9);
    when(emailContactDAO.findByUserIdAndPrimaryEmail(USERNAME, "jane@other.org")).thenReturn(Optional.empty());
    when(emailContactDAO.findBySecondaryEmail(USERNAME, "jane@other.org")).thenReturn(List.of(entity));

    EmailContact contact = emailContactStorage.getContactByAddress(USERNAME, "jane@other.org");

    assertEquals(7L, contact.getId());
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

  /**
   * A minimal stored row.
   *
   * @param id the row id
   * @param primaryEmail the address
   * @param displayName the name
   * @param sortBucket the stored bucket
   * @return the entity
   */
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
