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
package org.exoplatform.emailConnector.dao;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.Date;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.util.ReflectionTestUtils;

import org.exoplatform.emailConnector.constant.SearchAttachmentType;
import org.exoplatform.emailConnector.entity.EmailAttachmentEntity;
import org.exoplatform.emailConnector.entity.EmailBoxEntity;
import org.exoplatform.emailConnector.model.MailFolder;
import org.exoplatform.emailConnector.storage.EmailBoxStorage;

/**
 * The reads behind the "has an attachment" criterion of the search of eXo's copy of a
 * shared mailbox (EXO-90838), and behind its kind of attachment and file name
 * (EXO-90910), executed by their real engine on in-memory HSQLDB: the JPQL is parsed and
 * run, its {@code DISTINCT}, its {@code IN} over a collection parameter and its
 * {@code LIKE ... ESCAPE} included. Index usage on the production databases is not what
 * this shows.
 */
@DataJpaTest(showSql = false)
@EnableAutoConfiguration
@TestPropertySource(properties = { "spring.liquibase.enabled=false", "spring.jpa.hibernate.ddl-auto=create-drop" })
public class EmailAttachmentSearchDAOTest {

  private static final String USERNAME = "alice";

  @Autowired
  private TestEntityManager   entityManager;

  @Autowired
  private EmailAttachmentDAO  emailAttachmentDAO;

  /**
   * The minimal Spring slice: the mailbox entities and their repositories, with Boot's
   * auto-configured in-memory database.
   */
  @Configuration
  @EntityScan(basePackageClasses = EmailBoxEntity.class)
  @EnableJpaRepositories(basePackageClasses = EmailAttachmentDAO.class)
  static class JpaSliceConfiguration {
  }

  /**
   * A message is listed once whatever its number of attachments, a message with none is
   * not, and the read keeps to the folders and the user it is asked about.
   */
  @Test
  void theMessagesWithAnAttachmentAreListedOnceForTheirFoldersAndTheirUserOnly() {
    EmailBoxEntity twoFiles = persistEmail(USERNAME, "CUSTOM:8", 1L);
    persistAttachment(twoFiles, "1");
    persistAttachment(twoFiles, "2");
    EmailBoxEntity oneFile = persistEmail(USERNAME, "CUSTOM:9", 2L);
    persistAttachment(oneFile, "2");
    persistEmail(USERNAME, "CUSTOM:8", 3L);
    EmailBoxEntity otherFolder = persistEmail(USERNAME, MailFolder.INBOX, 4L);
    persistAttachment(otherFolder, "2");
    EmailBoxEntity otherUser = persistEmail("bob", "CUSTOM:8", 5L);
    persistAttachment(otherUser, "2");
    entityManager.clear();

    List<Long> ids = emailAttachmentDAO.findEmailIdsWithAttachmentsByUserIdAndFolders(USERNAME, List.of("CUSTOM:8", "CUSTOM:9"));

    assertEquals(2, ids.size(), "each message once, however many files it carries");
    assertTrue(ids.containsAll(List.of(twoFiles.getId(), oneFile.getId())));
    assertEquals(List.of(twoFiles.getId()),
                 emailAttachmentDAO.findEmailIdsWithAttachmentsByUserIdAndFolders(USERNAME, List.of("CUSTOM:8")),
                 "the folders asked about only");
  }

  /**
   * EXO-90910 -- the attachment rows a search by kind of attachment reads, run on HSQLDB:
   * one row per attachment with its message, name and MIME type, for the folders and
   * the user asked about only.
   */
  @Test
  void theAttachmentRowsOfASearchAreTheFoldersAndTheUsersOnly() {
    EmailBoxEntity mine = persistEmail(USERNAME, MailFolder.INBOX, 1L);
    persistAttachment(mine, "1", "Report.PDF", "application/octet-stream");
    persistAttachment(mine, "2", "photo.png", "image/png");
    persistAttachment(persistEmail(USERNAME, MailFolder.SENT, 2L), "1", "sent.pdf", "application/pdf");
    persistAttachment(persistEmail("bob", MailFolder.INBOX, 3L), "1", "bobs.pdf", "application/pdf");
    entityManager.clear();

    List<Object[]> rows = emailAttachmentDAO.findAttachmentsForSearchByUserIdAndFolders(USERNAME, List.of(MailFolder.INBOX));

    assertEquals(Set.of(List.of(mine.getId(), "Report.PDF", "application/octet-stream"),
                        List.of(mine.getId(), "photo.png", "image/png")),
                 rows.stream().map(Arrays::asList).collect(Collectors.toSet()));
  }

  /**
   * EXO-90910 -- "file name contains", run on HSQLDB through the pattern the storage
   * builds: the text matched anywhere in the name, case ignored, and literally -- a
   * {@code %}, a {@code _} or the escape character {@code !} typed by the user is no
   * wildcard -- never another user's attachment, never a row with no name.
   */
  @Test
  void theFileNameIsMatchedLiterallyAndCaseIgnored() {
    EmailBoxEntity contract = persistEmail(USERNAME, MailFolder.INBOX, 1L);
    persistAttachment(contract, "1", "Signed-CONTRACT_v2.pdf", "application/pdf");
    EmailBoxEntity percent = persistEmail(USERNAME, MailFolder.INBOX, 2L);
    persistAttachment(percent, "1", "growth 100% q3!.xlsx", "application/octet-stream");
    EmailBoxEntity lookalike = persistEmail(USERNAME, MailFolder.INBOX, 3L);
    persistAttachment(lookalike, "1", "contractXv2.pdf", "application/pdf");
    persistAttachment(lookalike, "2", "growth 1000 q3.xlsx", "application/octet-stream");
    EmailBoxEntity nameless = persistEmail(USERNAME, MailFolder.INBOX, 4L);
    persistAttachment(nameless, "1", null, "application/pdf");
    persistAttachment(persistEmail("bob", MailFolder.INBOX, 5L), "1", "contract_v2.pdf", "application/pdf");
    entityManager.clear();

    assertEquals(Set.of(contract.getId(), lookalike.getId()), idsNamed("contract"), "anywhere in the name, case ignored");
    assertEquals(Set.of(contract.getId()), idsNamed("contract_v2"), "an underscore is no single-character wildcard");
    assertEquals(Set.of(percent.getId()), idsNamed("100%"), "a percent sign is no wildcard");
    assertEquals(Set.of(percent.getId()), idsNamed("Q3!"), "the escape character itself is matched");
    assertEquals(Set.of(), idsNamed("nothing like it"));
  }

  /**
   * EXO-90910 -- the kind of an attachment as the storage tells it from the rows the
   * database gives: by the stored MIME type, or by the name's extension when the MIME type
   * is a generic one, an image by any image type; several kinds keep a message carrying
   * one of them; the name and the kind both narrow; another user's mail never matches.
   */
  @Test
  void theKindOfAnAttachmentIsItsMimeTypeOrItsExtension() {
    EmailBoxEntity pdfByType = persistEmail(USERNAME, MailFolder.INBOX, 1L);
    persistAttachment(pdfByType, "1", "scan", "application/pdf");
    EmailBoxEntity pdfByName = persistEmail(USERNAME, MailFolder.INBOX, 2L);
    persistAttachment(pdfByName, "1", "Contract.PDF", "application/octet-stream");
    EmailBoxEntity spreadsheet = persistEmail(USERNAME, MailFolder.INBOX, 3L);
    persistAttachment(spreadsheet, "1", "budget.csv", "text/plain");
    EmailBoxEntity image = persistEmail(USERNAME, MailFolder.INBOX, 4L);
    persistAttachment(image, "1", "contract-photo", "image/heic");
    EmailBoxEntity archive = persistEmail(USERNAME, MailFolder.INBOX, 5L);
    persistAttachment(archive, "1", "contract.zip", "application/zip");
    EmailBoxEntity video = persistEmail(USERNAME, MailFolder.INBOX, 7L);
    persistAttachment(video, "1", "Demo.MKV", "application/octet-stream");
    persistAttachment(persistEmail("bob", MailFolder.INBOX, 6L), "1", "bobs.pdf", "application/pdf");
    entityManager.clear();
    EmailBoxStorage storage = new EmailBoxStorage();
    ReflectionTestUtils.setField(storage, "emailAttachmentDAO", emailAttachmentDAO);

    assertEquals(Set.of(pdfByType.getId(), pdfByName.getId()),
                 storage.getEmailIdsWithMatchingAttachmentsInFolders(USERNAME,
                                                                     List.of(MailFolder.INBOX),
                                                                     EnumSet.of(SearchAttachmentType.PDF),
                                                                     null));
    assertEquals(Set.of(spreadsheet.getId(), image.getId()),
                 storage.getEmailIdsWithMatchingAttachmentsInFolders(USERNAME,
                                                                     List.of(MailFolder.INBOX),
                                                                     EnumSet.of(SearchAttachmentType.SPREADSHEET,
                                                                                SearchAttachmentType.IMAGE),
                                                                     ""));
    assertEquals(Set.of(pdfByName.getId(), archive.getId()),
                 storage.getEmailIdsWithMatchingAttachmentsInFolders(USERNAME,
                                                                     List.of(MailFolder.INBOX),
                                                                     EnumSet.of(SearchAttachmentType.PDF,
                                                                                SearchAttachmentType.ARCHIVE),
                                                                     "CONTRACT"),
                 "the name and the kind both narrow");
    assertEquals(Set.of(pdfByName.getId(), image.getId(), archive.getId()),
                 storage.getEmailIdsWithMatchingAttachmentsInFolders(USERNAME, List.of(MailFolder.INBOX), Set.of(), "contract"),
                 "a name with no kind: any kind");
    assertEquals(Set.of(video.getId()),
                 storage.getEmailIdsWithMatchingAttachmentsInFolders(USERNAME,
                                                                     List.of(MailFolder.INBOX),
                                                                     EnumSet.of(SearchAttachmentType.VIDEO),
                                                                     null),
                 "a video by its extension");
    assertEquals(Set.of(),
                 storage.getEmailIdsWithMatchingAttachmentsInFolders(USERNAME, List.of(), Set.of(SearchAttachmentType.PDF), null),
                 "no folder, nothing read");
  }

  /**
   * The ids of the user's INBOX messages with an attachment whose name contains a text,
   * through the pattern the storage builds.
   *
   * @param text the text
   * @return the messages' ids
   */
  private Set<Long> idsNamed(String text) {
    return emailAttachmentDAO.findAttachmentsForSearchByUserIdAndFoldersAndName(USERNAME,
                                                                               List.of(MailFolder.INBOX),
                                                                               EmailBoxStorage.toContainsPattern(text, 100))
                             .stream()
                             .map(row -> (Long) row[0])
                             .collect(Collectors.toSet());
  }

  /**
   * Persists one cached message.
   *
   * @param userId the user whose copy it is
   * @param folder the folder key
   * @param remoteId the IMAP UID
   * @return the persisted row
   */
  private EmailBoxEntity persistEmail(String userId, String folder, long remoteId) {
    EmailBoxEntity email = new EmailBoxEntity();
    email.setMailRemoteId(remoteId);
    email.setUserId(userId);
    email.setFolder(folder);
    email.setSender("Bob Smith,bob@example.org");
    email.setTo("Alice,alice@example.com");
    email.setCc("");
    email.setReceivedDate(new Date());
    email.setBody("body");
    entityManager.persist(email);
    entityManager.flush();
    return email;
  }

  /**
   * Persists one attachment row of a cached message, as the sync writes it.
   *
   * @param email the message
   * @param partPath the MIME part path
   */
  private void persistAttachment(EmailBoxEntity email, String partPath) {
    persistAttachment(email, partPath, "file-" + partPath + ".pdf", "application/pdf");
  }

  /**
   * Persists one attachment row of a cached message with a given name and MIME type.
   *
   * @param email the message
   * @param partPath the MIME part path
   * @param name the file name, may be null
   * @param mimeType the stored MIME type
   */
  private void persistAttachment(EmailBoxEntity email, String partPath, String name, String mimeType) {
    EmailAttachmentEntity attachment = new EmailAttachmentEntity();
    attachment.setEmail(email);
    attachment.setAttachmentRemoteId(partPath);
    attachment.setName(name);
    attachment.setMimeType(mimeType);
    entityManager.persist(attachment);
    entityManager.flush();
  }
}
