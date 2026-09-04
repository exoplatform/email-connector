/**
 * This file is part of the Meeds project (https://meeds.io/).
 *
 * Copyright (C) 2020 - 2026 Meeds Association contact@meeds.io
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 3 of the License, or (at your option) any later version.
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin Street, Fifth Floor, Boston, MA 02110-1301, USA.
 */
package org.exoplatform.emailConnector.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
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
import org.exoplatform.emailConnector.plugin.EmailCategoryPlugin;

import org.exoplatform.commons.file.services.FileService;
import org.exoplatform.upload.UploadService;

import io.meeds.social.category.service.CategoryLinkService;

/**
 * Which cached rows the Application Center badge is allowed to count: the unread
 * rows of the INBOX, and nothing from any other folder.
 * <p>
 * The regression this pins had no test in front of it. The badge landed
 * INBOX-scoped (EXO-88554), a squashed re-land put the every-folder count back, and
 * the Trash work then narrowed it only to {@code <> TRASH} — and every step stayed
 * green, because the one badge test ({@code EmailBoxTrashExclusionStorageTest})
 * writes an INBOX row and a TRASH row and nothing else, which {@code folder = INBOX}
 * and {@code folder <> TRASH} answer identically. So the unread SENT, ARCHIVE,
 * ALL_MAIL and DRAFTS rows are written here by hand, ahead of the count, and the
 * count is asked what it answers. Each of them is a row the user cannot mark read
 * from this product, which is what makes a wrong answer permanent.
 * <p>
 * On the SHIPPED Liquibase changelog and with the ambient transaction taken away
 * ({@link Propagation#NOT_SUPPORTED}, the rig {@code EmailBoxDraftStorageTest}
 * documents), because what is under test is a SQL predicate: a mocked DAO would
 * assert that the storage still calls it and nothing whatsoever about the folder
 * clause that is the change.
 */
@DataJpaTest(showSql = false)
@EnableAutoConfiguration
@Import(EmailBoxStorage.class)
@TestPropertySource(properties = { "spring.liquibase.enabled=true",
    "spring.liquibase.change-log=classpath:db/changelog/emailConnector-rdbms.db.changelog-master.xml",
    "spring.jpa.hibernate.ddl-auto=none" })
@Transactional(propagation = Propagation.NOT_SUPPORTED)
public class EmailBoxBadgeScopeStorageTest {

  // One mailbox per test: nothing rolls back here, so a second test's rows would
  // otherwise land in the first test's count.
  private static final String SCOPE_USER      = "karl";

  private static final String PROJECTION_USER = "laura";

  private static final long   MONDAY          = 1_000_000_000_000L;

  @Autowired
  private EmailBoxStorage     emailBoxStorage;

  @MockitoBean
  private CategoryLinkService categoryLinkService;

  // Mocked rather than exercised: nothing here attaches a file, and an unmocked bean
  // would simply fail to start the context.
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
   * The pin. Two unread INBOX messages and one read one, and an unread row in every
   * other folder the mirror can hold — the SENT copy a server appended without
   * {@code \Seen}, an archived message never opened, the ALL_MAIL duplicate, a
   * server-side draft, a deleted message. The badge says two: the rows a user can
   * clear from the inbox, and only those.
   */
  @Test
  void onlyTheUnreadInboxFeedsTheBadge() {
    emailBoxStorage.createEmail(unread(mail(SCOPE_USER, MailFolder.INBOX, 1L, "<inbox-1@example.org>")));
    emailBoxStorage.createEmail(unread(mail(SCOPE_USER, MailFolder.INBOX, 2L, "<inbox-2@example.org>")));
    emailBoxStorage.createEmail(read(mail(SCOPE_USER, MailFolder.INBOX, 3L, "<inbox-read@example.org>")));
    emailBoxStorage.createEmail(unread(mail(SCOPE_USER, MailFolder.SENT, 1L, "<sent@example.org>")));
    emailBoxStorage.createEmail(unread(mail(SCOPE_USER, MailFolder.ARCHIVE, 1L, "<archived@example.org>")));
    emailBoxStorage.createEmail(unread(mail(SCOPE_USER, MailFolder.ALL_MAIL, 1L, "<all-mail@example.org>")));
    emailBoxStorage.createEmail(unread(mail(SCOPE_USER, MailFolder.DRAFTS, 1L, "<server-draft@example.org>")));
    emailBoxStorage.createEmail(unread(mail(SCOPE_USER, MailFolder.TRASH, 1L, "<deleted@example.org>")));

    assertEquals(2,
                 emailBoxStorage.countUnreadEmails(SCOPE_USER),
                 "the badge counts the unread inbox: a row the user cannot mark read from here must never count");
  }

  /**
   * The category-filtered badge reads the same rows the count does, as ids with
   * their links — and asks social for those ids in one batch, not one per row. The
   * unread SENT row and the read INBOX row are there to be left out; the unlinked
   * INBOX row is there to come back with an empty list rather than to disappear,
   * because "uncategorized" is a case the rule decides on and must be able to see.
   */
  @Test
  void theProjectionReadsTheRowsTheCountDoesAndAsksForTheirLinksOnce() {
    emailBoxStorage.createEmail(unread(mail(PROJECTION_USER, MailFolder.INBOX, 1L, "<categorized@example.org>")));
    emailBoxStorage.createEmail(unread(mail(PROJECTION_USER, MailFolder.INBOX, 2L, "<uncategorized@example.org>")));
    emailBoxStorage.createEmail(read(mail(PROJECTION_USER, MailFolder.INBOX, 3L, "<inbox-read@example.org>")));
    emailBoxStorage.createEmail(unread(mail(PROJECTION_USER, MailFolder.SENT, 1L, "<sent@example.org>")));
    long categorizedId = idOf(PROJECTION_USER, "<categorized@example.org>");
    long uncategorizedId = idOf(PROJECTION_USER, "<uncategorized@example.org>");
    // The id lookups above read through the listing, which asks for links itself;
    // only the projection's own lookup is under test.
    clearInvocations(categoryLinkService);
    when(categoryLinkService.getLinkedIds(eq(EmailCategoryPlugin.OBJECT_TYPE), anyList()))
                                                                                      .thenReturn(Map.of(String.valueOf(categorizedId),
                                                                                                         List.of(7L)));

    Map<Long, List<Long>> categoryIdsByEmailId = emailBoxStorage.getUnreadInboxCategoryIds(PROJECTION_USER);

    assertEquals(Set.of(categorizedId, uncategorizedId),
                 categoryIdsByEmailId.keySet(),
                 "the projection is the unread inbox and nothing else — the same rows the count answers with");
    assertEquals(List.of(7L), categoryIdsByEmailId.get(categorizedId));
    assertTrue(categoryIdsByEmailId.get(uncategorizedId).isEmpty(),
               "an unlinked row is present with no categories, not absent: uncategorized is a case, not a gap");
    @SuppressWarnings("unchecked")
    ArgumentCaptor<List<String>> asked = ArgumentCaptor.forClass(List.class);
    verify(categoryLinkService).getLinkedIds(eq(EmailCategoryPlugin.OBJECT_TYPE), asked.capture());
    assertEquals(Set.of(String.valueOf(categorizedId), String.valueOf(uncategorizedId)),
                 Set.copyOf(asked.getValue()),
                 "one batched lookup over exactly the unread inbox ids — never one lookup per row");
  }

  /**
   * The database id of one of the mailbox's rows, by its Message-ID.
   *
   * @param userId the mailbox owner
   * @param messageId the row's Message-ID
   * @return the row's id
   */
  private long idOf(String userId, String messageId) {
    return emailBoxStorage.getEmails(userId)
                          .stream()
                          .filter(email -> messageId.equals(email.getMailHeaderId()))
                          .map(Email::getId)
                          .findFirst()
                          .orElseThrow();
  }

  /**
   * Marks a message unread, as it arrives — and as it stays in any folder the user
   * cannot mark it read from.
   *
   * @param email the message
   * @return the same message
   */
  private Email unread(Email email) {
    email.setRead(false);
    return email;
  }

  /**
   * Marks a message read, as the inbox does when the user opens it.
   *
   * @param email the message
   * @return the same message
   */
  private Email read(Email email) {
    email.setRead(true);
    return email;
  }

  /**
   * A cached message, in whichever folder it is cached.
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
    email.setTo(List.of(new EmailRecipient("Karl", "karl@example.org", null, false)));
    email.setSubject("A message");
    email.setContent(new EmailContent("<p>a message</p>", null, null));
    email.setReceivedDate(new Date(MONDAY + mailRemoteId));
    return email;
  }
}
