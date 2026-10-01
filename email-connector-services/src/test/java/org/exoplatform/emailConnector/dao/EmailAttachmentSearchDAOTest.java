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

import java.util.Date;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.TestPropertySource;

import org.exoplatform.emailConnector.entity.EmailAttachmentEntity;
import org.exoplatform.emailConnector.entity.EmailBoxEntity;
import org.exoplatform.emailConnector.model.MailFolder;

/**
 * The read behind the "has an attachment" criterion of the search of eXo's copy of a
 * shared mailbox (EXO-90838), executed by its real engine on in-memory HSQLDB: the JPQL
 * is parsed and run, its {@code DISTINCT} and its {@code IN} over a collection parameter
 * included. Index usage on the production databases is not what this shows.
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
    EmailAttachmentEntity attachment = new EmailAttachmentEntity();
    attachment.setEmail(email);
    attachment.setAttachmentRemoteId(partPath);
    attachment.setName("file-" + partPath + ".pdf");
    attachment.setMimeType("application/pdf");
    entityManager.persist(attachment);
    entityManager.flush();
  }
}
