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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.Date;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import org.exoplatform.emailConnector.dao.EmailAttachmentDAO;
import org.exoplatform.emailConnector.dao.EmailBoxDAO;
import org.exoplatform.emailConnector.entity.EmailAttachmentEntity;
import org.exoplatform.emailConnector.entity.EmailBoxEntity;
import org.exoplatform.emailConnector.model.EmailAttachment;
import org.exoplatform.emailConnector.model.MailFolder;

import io.meeds.social.category.service.CategoryLinkService;

/**
 * The attachment lookup against a real database, on the shipped Liquibase
 * changelog, with rows a mocked DAO cannot express.
 * <p>
 * The defect this pins is not visible under a mock, because a mock is asked for
 * one row by the very key the test invented: the whole question here is what a
 * REAL table answers when two rows match. IMAP numbers messages PER FOLDER, so
 * UID 1212 in the inbox and UID 1212 in Sent are two unrelated messages, and MIME
 * part paths are positional — "2" is simply the second part — so the same path
 * exists in nearly every message carrying an attachment at all. On the key the
 * query used before this change (uid, part path, owner) those two rows are
 * indistinguishable.
 * <p>
 * Run against that query, these tests fail in the two shapes the defect actually
 * takes, which is worth having on record: {@code IncorrectResultSizeDataAccess:
 * Query did not return a unique result: 2 results were returned} where both rows
 * exist (a 500 the user reads as a broken file), and a silent answer carrying the
 * WRONG row's name and content type where only the other folder's does.
 * <p>
 * Same rig as {@link EmailBoxDraftStorageTest}: in-memory HSQLDB on the shipped
 * changelog, {@code ddl-auto=none}, and the ambient transaction taken away so the
 * session boundaries are production's.
 */
@DataJpaTest(showSql = false)
@EnableAutoConfiguration
@Import(EmailBoxStorage.class)
@TestPropertySource(properties = { "spring.liquibase.enabled=true",
    "spring.liquibase.change-log=classpath:db/changelog/emailConnector-rdbms.db.changelog-master.xml",
    "spring.jpa.hibernate.ddl-auto=none" })
@Transactional(propagation = Propagation.NOT_SUPPORTED)
public class EmailBoxAttachmentFolderStorageTest {

  private static final String USERNAME = "alice";

  private static final long   SHARED_UID = 1212L;

  private static final String SHARED_PART_PATH = "2";

  @Autowired
  private EmailBoxStorage     emailBoxStorage;

  @Autowired
  private EmailBoxDAO         emailBoxDao;

  @Autowired
  private EmailAttachmentDAO  emailAttachmentDAO;

  @MockBean
  private CategoryLinkService categoryLinkService;

  /**
   * The minimal Spring slice: the mail entities and their repositories, over
   * Boot's auto-configured in-memory database.
   */
  @Configuration
  @EntityScan(basePackageClasses = EmailBoxEntity.class)
  @EnableJpaRepositories(basePackageClasses = EmailBoxDAO.class)
  static class JpaSliceConfiguration {
  }

  /**
   * The collision itself: one mailbox holding an inbox message and a sent message
   * that happen to share a UID — which costs nothing to arrange, because the two
   * folders number their messages independently — each with an attachment at part
   * path "2".
   * <p>
   * Red before this change, with the query refusing to answer at all: two rows match
   * a lookup declared to return at most one.
   */
  @Test
  void anAttachmentIsReadFromTheFolderItWasAskedFor() {
    cacheMessageWithAttachment(MailFolder.INBOX, SHARED_UID, "invoice.pdf", "application/pdf");
    cacheMessageWithAttachment(MailFolder.SENT, SHARED_UID, "holiday.jpg", "image/jpeg");

    EmailAttachment received = emailBoxStorage.getAttachmentByMailRemoteIdAnIdAndUserId(SHARED_UID,
                                                                                        SHARED_PART_PATH,
                                                                                        USERNAME,
                                                                                        MailFolder.INBOX);
    EmailAttachment sent = emailBoxStorage.getAttachmentByMailRemoteIdAnIdAndUserId(SHARED_UID,
                                                                                    SHARED_PART_PATH,
                                                                                    USERNAME,
                                                                                    MailFolder.SENT);

    assertNotNull(received);
    assertNotNull(sent);
    assertEquals("invoice.pdf", received.getName(), "the inbox lookup must not answer with the sent message's row");
    assertEquals("application/pdf", received.getMimeType());
    assertEquals("holiday.jpg", sent.getName(), "the sent lookup must not answer with the inbox message's row");
    assertEquals("image/jpeg", sent.getMimeType());
  }

  /**
   * The half of the report a user actually reports: an attachment on a message in
   * Sent or Archive could not be downloaded, because the read looked for it in the
   * inbox and there is no such row there.
   * <p>
   * Asserted as "the archive row is found under ARCHIVE and there is nothing under
   * INBOX", which is both halves of the fix in one statement — and the INBOX half is
   * the one that was red the other way round: the archive row WAS returned for an
   * inbox lookup, name, content type and all.
   */
  @Test
  void anAttachmentOutsideTheInboxIsFoundAtAll() {
    cacheMessageWithAttachment(MailFolder.ARCHIVE, 900L, "contract.pdf", "application/pdf");

    assertNull(emailBoxStorage.getAttachmentByMailRemoteIdAnIdAndUserId(900L,
                                                                        SHARED_PART_PATH,
                                                                        USERNAME,
                                                                        MailFolder.INBOX),
               "the inbox holds no such message, and saying so is what lets the caller answer 404");
    EmailAttachment archived = emailBoxStorage.getAttachmentByMailRemoteIdAnIdAndUserId(900L,
                                                                                        SHARED_PART_PATH,
                                                                                        USERNAME,
                                                                                        MailFolder.ARCHIVE);
    assertNotNull(archived, "an archived message's attachment could not be downloaded at all before this change");
    assertEquals("contract.pdf", archived.getName());
  }

  /**
   * The folder travels back on the attachment, which is what lets every consumer
   * address the bytes without holding the message they came from — the front end's
   * download URL, the preview, the vCard prefill.
   */
  @Test
  void anAttachmentCarriesTheFolderItLivesIn() {
    cacheMessageWithAttachment(MailFolder.SENT, 77L, "proposal.docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document");

    EmailAttachment attachment = emailBoxStorage.getAttachmentByMailRemoteIdAnIdAndUserId(77L,
                                                                                          SHARED_PART_PATH,
                                                                                          USERNAME,
                                                                                          MailFolder.SENT);

    assertNotNull(attachment);
    assertEquals(MailFolder.SENT, attachment.getFolder(), "a UID without its folder names nothing; the pair has to travel together");
    assertEquals(Long.valueOf(77L), attachment.getMailRemoteId());
  }

  /**
   * One cached message carrying one attachment at the shared part path.
   *
   * @param folder the {@link MailFolder} discriminator to file it under
   * @param mailRemoteId the IMAP UID within that folder
   * @param name the attachment's file name
   * @param mimeType the attachment's content type
   */
  private void cacheMessageWithAttachment(String folder, long mailRemoteId, String name, String mimeType) {
    EmailBoxEntity message = new EmailBoxEntity();
    message.setUserId(USERNAME);
    message.setFolder(folder);
    message.setMailRemoteId(mailRemoteId);
    message.setMailHeaderId("<" + folder + "-" + mailRemoteId + "@example.org>");
    message.setSubject("A message with a file");
    message.setBody("body");
    message.setSender("Bob,bob@example.org");
    message.setReceivedDate(new Date());
    message = emailBoxDao.save(message);

    EmailAttachmentEntity attachment = new EmailAttachmentEntity();
    attachment.setEmail(message);
    attachment.setAttachmentRemoteId(SHARED_PART_PATH);
    attachment.setName(name);
    attachment.setMimeType(mimeType);
    emailAttachmentDAO.save(attachment);
  }
}
