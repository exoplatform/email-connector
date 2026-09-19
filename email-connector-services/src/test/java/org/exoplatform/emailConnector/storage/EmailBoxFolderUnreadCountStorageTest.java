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
package org.exoplatform.emailConnector.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.Date;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import org.exoplatform.commons.file.services.FileService;
import org.exoplatform.emailConnector.dao.EmailBoxDAO;
import org.exoplatform.emailConnector.entity.EmailBoxEntity;
import org.exoplatform.emailConnector.model.Email;
import org.exoplatform.emailConnector.model.EmailContent;
import org.exoplatform.emailConnector.model.EmailRecipient;
import org.exoplatform.emailConnector.model.EmailSender;
import org.exoplatform.emailConnector.model.FolderMessageCounts;
import org.exoplatform.emailConnector.model.MailFolder;
import org.exoplatform.upload.UploadService;

import io.meeds.social.category.service.CategoryLinkService;

/**
 * The per-folder counts the folder list is built from, counted by the real query on the
 * SHIPPED Liquibase changelog (EXO-90415): the unread messages of each folder are
 * counted in the same grouped read as the totals, with a {@code SUM(CASE ...)} over
 * the read flag -- a JPQL construct a mocked DAO would accept whatever it said, which
 * is why it is executed here, on the rig {@link EmailBoxTrashExclusionStorageTest}
 * documents (no ambient transaction; one mailbox per test, nothing rolls back).
 */
@DataJpaTest(showSql = false)
@EnableAutoConfiguration
@Import(EmailBoxStorage.class)
@TestPropertySource(properties = { "spring.liquibase.enabled=true",
    "spring.liquibase.change-log=classpath:db/changelog/emailConnector-rdbms.db.changelog-master.xml",
    "spring.jpa.hibernate.ddl-auto=none" })
@Transactional(propagation = Propagation.NOT_SUPPORTED)
public class EmailBoxFolderUnreadCountStorageTest {

  private static final String MIXED_USER  = "unread-mixed";

  private static final String ALL_READ    = "unread-none";

  private static final String OTHER_USER  = "unread-other";

  private static final String NULL_READ   = "unread-null";

  private static final long   MONDAY      = 1_000_000_000_000L;

  @Autowired
  private EmailBoxStorage     emailBoxStorage;

  @Autowired
  private JdbcTemplate        jdbcTemplate;

  @MockitoBean
  private CategoryLinkService categoryLinkService;

  @MockitoBean
  private FileService         fileService;

  @MockitoBean
  private UploadService       uploadService;

  /**
   * The minimal Spring slice: the mail entities and their repositories, over Boot's
   * auto-configured in-memory database.
   */
  @Configuration
  @EntityScan(basePackageClasses = EmailBoxEntity.class)
  @EnableJpaRepositories(basePackageClasses = EmailBoxDAO.class)
  static class JpaSliceConfiguration {
  }

  /**
   * Each folder counts its own unread messages beside its total, and only the owner's.
   */
  @Test
  void eachFolderCountsItsUnreadMailBesideItsTotal() {
    emailBoxStorage.createEmail(mail(MIXED_USER, MailFolder.INBOX, 1L, false));
    emailBoxStorage.createEmail(mail(MIXED_USER, MailFolder.INBOX, 2L, false));
    emailBoxStorage.createEmail(mail(MIXED_USER, MailFolder.INBOX, 3L, true));
    emailBoxStorage.createEmail(mail(MIXED_USER, MailFolder.JUNK, 4L, false));
    emailBoxStorage.createEmail(mail(MIXED_USER, MailFolder.SENT, 5L, true));
    // Another mailbox's unread mail is not this one's.
    emailBoxStorage.createEmail(mail(OTHER_USER, MailFolder.INBOX, 1L, false));

    FolderMessageCounts counts = emailBoxStorage.getFolderCounts(MIXED_USER);

    assertEquals(Map.of(MailFolder.INBOX, 3, MailFolder.JUNK, 1, MailFolder.SENT, 1), counts.getMessageCounts());
    assertEquals(Map.of(MailFolder.INBOX, 2, MailFolder.JUNK, 1, MailFolder.SENT, 0), counts.getUnreadCounts());
    assertEquals(counts.getMessageCounts(), emailBoxStorage.getFolderMessageCounts(MIXED_USER),
                 "the totals the folder switch was built from are unchanged");
  }

  /**
   * A folder whose every message is read counts none unread -- 0, not a missing entry:
   * the column tells "nothing unread" from "no such folder".
   */
  @Test
  void aFolderReadThroughCountsNoneUnread() {
    emailBoxStorage.createEmail(mail(ALL_READ, MailFolder.INBOX, 1L, true));

    FolderMessageCounts counts = emailBoxStorage.getFolderCounts(ALL_READ);

    assertEquals(Integer.valueOf(0), counts.getUnreadCounts().get(MailFolder.INBOX));
    assertNull(counts.getUnreadCounts().get(MailFolder.JUNK), "an empty folder has no entry at all");
  }

  /**
   * A row with no read flag counts as unread, as the badge counts it
   * ({@code EmailBoxDAO#countUnreadByUserIdAndFolder}): the column's inbox figure and
   * the badge's agree by construction. The column is nullable in the changelog; the
   * row is written by hand because the entity's primitive flag never writes a null.
   */
  @Test
  void aRowWithNoReadFlagCountsAsUnreadAsTheBadgeCountsIt() {
    emailBoxStorage.createEmail(mail(NULL_READ, MailFolder.INBOX, 1L, true));
    emailBoxStorage.createEmail(mail(NULL_READ, MailFolder.INBOX, 2L, true));
    jdbcTemplate.update("UPDATE EMAIL_BOX SET IS_READ = NULL WHERE USER_ID = ? AND MAIL_REMOTE_ID = 2", NULL_READ);

    assertEquals(Integer.valueOf(1), emailBoxStorage.getFolderCounts(NULL_READ).getUnreadCounts().get(MailFolder.INBOX));
  }

  /**
   * One message of a folder, read or not.
   *
   * @param userId the mailbox owner
   * @param folder the folder it is filed in
   * @param mailRemoteId its UID in that folder
   * @param read whether it is read
   * @return the message to write
   */
  private Email mail(String userId, String folder, long mailRemoteId, boolean read) {
    Email email = new Email();
    email.setUserId(userId);
    email.setFolder(folder);
    email.setMailRemoteId(mailRemoteId);
    email.setMailHeaderId("<" + userId + "-" + folder + "-" + mailRemoteId + "@example.org>");
    email.setThreadId("<" + userId + "-" + folder + "-" + mailRemoteId + "@example.org>");
    email.setSender(new EmailSender("Veronika", "veronika@example.org", null, null));
    email.setTo(List.of(new EmailRecipient("Alice", "alice@example.org", null, false)));
    email.setSubject("A message");
    email.setContent(new EmailContent("<p>a message</p>", null, null));
    email.setReceivedDate(new Date(MONDAY + mailRemoteId));
    email.setRead(read);
    return email;
  }
}
