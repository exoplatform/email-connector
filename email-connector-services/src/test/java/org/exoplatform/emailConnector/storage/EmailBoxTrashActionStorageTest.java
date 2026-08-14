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
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import java.util.Date;
import java.util.List;
import java.util.Map;

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

import org.exoplatform.emailConnector.dao.EmailBoxDAO;
import org.exoplatform.emailConnector.entity.EmailBoxEntity;
import org.exoplatform.emailConnector.model.Email;
import org.exoplatform.emailConnector.model.EmailContent;
import org.exoplatform.emailConnector.model.EmailRecipient;
import org.exoplatform.emailConnector.model.EmailSender;
import org.exoplatform.emailConnector.model.MailFolder;

import org.exoplatform.commons.file.services.FileService;
import org.exoplatform.upload.UploadService;

import io.meeds.social.category.service.CategoryLinkService;

/**
 * The two persistence facts the Trash actions stand on: the read that picks the row up
 * is FOLDER-SCOPED, and the row put back after a remote failure is put back in the same
 * folder it was taken from.
 * <p>
 * At the persistence level, on the SHIPPED Liquibase changelog and with the ambient
 * transaction taken away ({@link Propagation#NOT_SUPPORTED}), for the reason
 * {@link EmailBoxTrashExclusionStorageTest} spells out: both facts ARE a SQL predicate
 * and a column value, and a mocked DAO handing back a list somebody built by hand would
 * assert neither of them.
 * <p>
 * The service-level tests next door drive the same two through Mockito and can only see
 * what was ASKED of the storage. These see what the database then answers, which is the
 * half that decides whether a failed permanent delete really does leave the message
 * where the user can still see it.
 */
@DataJpaTest(showSql = false)
@EnableAutoConfiguration
@Import(EmailBoxStorage.class)
@TestPropertySource(properties = { "spring.liquibase.enabled=true",
    "spring.liquibase.change-log=classpath:db/changelog/emailConnector-rdbms.db.changelog-master.xml",
    "spring.jpa.hibernate.ddl-auto=none" })
@Transactional(propagation = Propagation.NOT_SUPPORTED)
public class EmailBoxTrashActionStorageTest {

  // One mailbox per test: nothing rolls back here, so a cross-folder read would
  // otherwise pick up the rows of every earlier test.
  private static final String SCOPED_USER  = "mallory";

  private static final String REVERT_USER  = "niaj";

  private static final String COUNTED_USER = "olivia";

  private static final long   SHARED_UID   = 4242L;

  private static final long   MONDAY       = 1_000_000_000_000L;

  @Autowired
  private EmailBoxStorage     emailBoxStorage;

  @MockBean
  private CategoryLinkService categoryLinkService;

  // Mocked rather than exercised: nothing here attaches a file, and an unmocked bean
  // would simply fail to start the context.
  @MockBean
  private FileService         fileService;

  @MockBean
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
   * The reason the Trash actions read their rows folder-scoped, shown rather than
   * argued: an IMAP UID numbers messages within ONE folder, so the same number really
   * does name two different messages in one mailbox. An unscoped read would hand the
   * restore an inbox row — its subject in the confirmation, its Message-ID checked
   * against the Trash — and every subsequent step would be about the wrong message.
   */
  @Test
  void theSameUidNamesADifferentMessageInEachFolder() {
    emailBoxStorage.createEmail(mail(SCOPED_USER, MailFolder.INBOX, SHARED_UID, "<in-the-inbox@example.org>"));
    emailBoxStorage.createEmail(mail(SCOPED_USER, MailFolder.TRASH, SHARED_UID, "<in-the-bin@example.org>"));

    Email trashed = read(SCOPED_USER, MailFolder.TRASH);
    Email inboxed = read(SCOPED_USER, MailFolder.INBOX);

    assertNotNull(trashed);
    assertNotNull(inboxed);
    assertEquals("<in-the-bin@example.org>",
                 trashed.getMailHeaderId(),
                 "the trash action must be handed the message that is in the Trash");
    assertEquals("<in-the-inbox@example.org>",
                 inboxed.getMailHeaderId(),
                 "and the very same uid still names the inbox message it always did");
  }

  /**
   * What a failed remote step has to leave behind. The row is deleted before the
   * server is touched, so the compensation is the only thing standing between "the
   * permanent delete failed" and "the message is gone from the mirror and alive on the
   * server" — invisible to the user, and invisible for good once the 30-message Trash
   * window has moved past it.
   * <p>
   * Re-created, not restored: the row comes back with a NEW id, which is what makes
   * {@code setId(null)} the right way to do it rather than an update of a row that no
   * longer exists.
   */
  @Test
  void aRowPutBackAfterAFailureIsInTheTrashAgainAndReadableAgain() {
    Email row = mail(REVERT_USER, MailFolder.TRASH, SHARED_UID, "<survives@example.org>");
    emailBoxStorage.createEmail(row);
    Email cached = read(REVERT_USER, MailFolder.TRASH);
    Long originalId = cached.getId();

    // Exactly what the service does: delete first, then put it back when the server
    // refuses.
    emailBoxStorage.deleteEmailsByIds(List.of(originalId));
    cached.setId(null);
    emailBoxStorage.createEmail(cached);

    Email back = read(REVERT_USER, MailFolder.TRASH);
    assertNotNull(back, "a permanent delete that failed must leave the message visible in the Trash");
    assertEquals("<survives@example.org>", back.getMailHeaderId());
    assertEquals(MailFolder.TRASH, back.getFolder(), "and visible THERE — a row that came back into the inbox would be a restore nobody asked for");
    assertNotEquals(originalId, back.getId(), "the row was really deleted, so it really is a new one");
  }

  /**
   * The compensation seen from the folder switch, which is what actually decides
   * whether the user can get back to the message: the Trash entry is count-gated, so a
   * row that came back has to be counted back or the folder quietly disappears from
   * the menu with the message still in it.
   */
  @Test
  void aRowPutBackIsCountedBackIntoTheTrashFolderEntry() {
    Email row = mail(COUNTED_USER, MailFolder.TRASH, SHARED_UID, "<counted@example.org>");
    emailBoxStorage.createEmail(row);
    Email cached = read(COUNTED_USER, MailFolder.TRASH);

    emailBoxStorage.deleteEmailsByIds(List.of(cached.getId()));
    assertEquals(null,
                 emailBoxStorage.getFolderMessageCounts(COUNTED_USER).get(MailFolder.TRASH),
                 "with the row gone the folder holds nothing");

    cached.setId(null);
    emailBoxStorage.createEmail(cached);

    Map<String, Integer> counts = emailBoxStorage.getFolderMessageCounts(COUNTED_USER);
    assertEquals(1, counts.get(MailFolder.TRASH), "and the folder the user has to reach it through is offered again");
  }

  /**
   * The folder-scoped single-message read the Trash actions use.
   *
   * @param userId the mailbox owner
   * @param folder the {@link MailFolder} discriminator
   * @return the cached row, or null when that folder holds no such uid
   */
  private Email read(String userId, String folder) {
    return emailBoxStorage.getEmailByMailRemoteIdAndUserId(SHARED_UID, userId, null, folder, false, false, false);
  }

  /**
   * A cached message in a given folder.
   *
   * @param userId the mailbox owner
   * @param folder the {@link MailFolder} discriminator
   * @param mailRemoteId the IMAP UID within that folder
   * @param messageId its Message-ID
   * @return the message to write
   */
  private Email mail(String userId, String folder, long mailRemoteId, String messageId) {
    Email email = new Email();
    email.setUserId(userId);
    email.setFolder(folder);
    email.setMailRemoteId(mailRemoteId);
    email.setMailHeaderId(messageId);
    email.setThreadId(messageId);
    email.setSender(new EmailSender("Veronika", "veronika@example.org", null, null));
    email.setTo(List.of(new EmailRecipient("Mallory", "mallory@example.org", null, false)));
    email.setSubject("A message");
    email.setContent(new EmailContent("<p>a message</p>", null, null));
    email.setReceivedDate(new Date(MONDAY));
    return email;
  }
}
