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
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Date;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import org.exoplatform.commons.file.services.FileService;
import org.exoplatform.emailConnector.dao.EmailBoxDAO;
import org.exoplatform.emailConnector.entity.EmailBoxEntity;
import org.exoplatform.emailConnector.model.Email;
import org.exoplatform.emailConnector.model.EmailAttachment;
import org.exoplatform.emailConnector.model.EmailContent;
import org.exoplatform.emailConnector.model.EmailRecipient;
import org.exoplatform.emailConnector.model.EmailSender;
import org.exoplatform.emailConnector.model.MailFolder;
import org.exoplatform.upload.UploadService;

import io.meeds.social.category.service.CategoryLinkService;

/**
 * What the search of eXo's copy of a shared mailbox reads (EXO-90838), on the shipped
 * Liquibase changelog: the To and Cc recipients of each row, for the recipient
 * criterion, and the ids of the rows carrying an attachment, for the attachment one.
 */
@DataJpaTest(showSql = false)
@EnableAutoConfiguration
@Import(EmailBoxStorage.class)
@TestPropertySource(properties = { "spring.liquibase.enabled=true",
    "spring.liquibase.change-log=classpath:db/changelog/emailConnector-rdbms.db.changelog-master.xml",
    "spring.jpa.hibernate.ddl-auto=none" })
@Transactional(propagation = Propagation.NOT_SUPPORTED)
public class EmailBoxSharedSearchStorageTest {

  // One mailbox per test: nothing rolls back here.
  private static final String RECIPIENTS_USER  = "mona";

  private static final String ATTACHMENTS_USER = "nina";

  @Autowired
  private EmailBoxStorage     emailBoxStorage;

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
   * The search read carries the To and the Cc recipients a row was cached with, name
   * and address, and never its Bcc ones.
   */
  @Test
  void theSearchReadCarriesTheToAndCcRecipients() {
    Email email = mail(RECIPIENTS_USER, "CUSTOM:21", 1L, null);
    email.setTo(List.of(new EmailRecipient("Dave Smith", "dave@acme.com", null, false)));
    email.setCc(List.of(new EmailRecipient("Erin", "erin@acme.com", null, false),
                        new EmailRecipient(null, "frank@acme.com", null, false)));
    email.setBcc(List.of(new EmailRecipient("Grace", "grace@acme.com", null, false)));
    emailBoxStorage.createEmail(email);

    Email read = emailBoxStorage.getEmailsForSearchInFolders(RECIPIENTS_USER, List.of("CUSTOM:21")).get(0);

    assertEquals(List.of("dave@acme.com"), read.getTo().stream().map(EmailRecipient::getAddress).toList());
    assertEquals("Dave Smith", read.getTo().get(0).getName());
    assertEquals(List.of("erin@acme.com", "frank@acme.com"), read.getCc().stream().map(EmailRecipient::getAddress).toList());
    assertTrue(read.getBcc() == null || read.getBcc().isEmpty(), "the Bcc recipients are not read");
  }

  /**
   * The ids of the rows with an attachment, in the folders asked about, for that user;
   * nothing is read for no folder.
   */
  @Test
  void theRowsWithAnAttachmentAreTheOnesOfTheFoldersAskedAbout() {
    Email withFile = emailBoxStorage.createEmail(mail(ATTACHMENTS_USER, "CUSTOM:31", 1L, attachment("2")));
    emailBoxStorage.createEmail(mail(ATTACHMENTS_USER, "CUSTOM:31", 2L, null));
    emailBoxStorage.createEmail(mail(ATTACHMENTS_USER, MailFolder.INBOX, 3L, attachment("2")));

    assertEquals(Set.of(withFile.getId()), emailBoxStorage.getEmailIdsWithAttachmentsInFolders(ATTACHMENTS_USER, List.of("CUSTOM:31")));
    assertTrue(emailBoxStorage.getEmailIdsWithAttachmentsInFolders(ATTACHMENTS_USER, List.of()).isEmpty());
  }

  /**
   * One cached message.
   *
   * @param userId whose copy it is
   * @param folder the folder key
   * @param mailRemoteId the IMAP UID
   * @param attachment the one attachment it carries, null for none
   * @return the message to write
   */
  private Email mail(String userId, String folder, long mailRemoteId, EmailAttachment attachment) {
    Email email = new Email();
    email.setUserId(userId);
    email.setFolder(folder);
    email.setMailRemoteId(mailRemoteId);
    email.setMailHeaderId("<" + userId + mailRemoteId + "@example.org>");
    email.setThreadId("<" + userId + mailRemoteId + "@example.org>");
    email.setSender(new EmailSender("Veronika", "veronika@example.org", null, null));
    email.setTo(List.of(new EmailRecipient("Alice", "alice@example.org", null, false)));
    email.setSubject("Figures");
    email.setContent(new EmailContent("<p>a message</p>", null, attachment == null ? null : List.of(attachment)));
    email.setReceivedDate(new Date());
    return email;
  }

  /**
   * An attachment as the sync mirrors one: a part of the message on the server.
   *
   * @param partPath its MIME part path
   * @return the attachment
   */
  private EmailAttachment attachment(String partPath) {
    EmailAttachment attachment = new EmailAttachment();
    attachment.setAttachmentRemoteId(partPath);
    attachment.setName("figures.pdf");
    attachment.setMimeType("application/pdf");
    return attachment;
  }
}
