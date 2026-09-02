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
package org.exoplatform.emailConnector.dao;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
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

import org.exoplatform.emailConnector.entity.EmailBoxEntity;
import org.exoplatform.emailConnector.model.MailFolder;

/**
 * Real-database coverage of the cached message's IS_HTML column, on in-memory HSQLDB.
 * <p>
 * The column carries three states and the difference between two of them is the whole
 * design: true and false are what the message said about its own body, and null is "this
 * row was cached before anyone asked". Only a real round trip through SQL can show that
 * null survives as null rather than arriving back as the primitive false that a mocked
 * DAO would happily hand over — and were it to arrive as false, every HTML mail already
 * in the cache would render as escaped source.
 */
@DataJpaTest(showSql = false)
@EnableAutoConfiguration
@TestPropertySource(properties = { "spring.liquibase.enabled=false", "spring.jpa.hibernate.ddl-auto=create-drop" })
public class EmailBoxDAOTest {

  private static final String USERNAME = "alice";

  @Autowired
  private TestEntityManager   entityManager;

  @Autowired
  private EmailBoxDAO         emailBoxDAO;

  /**
   * The minimal Spring slice: the mailbox entities and their repository, with Boot's
   * auto-configured in-memory database.
   */
  @Configuration
  @EntityScan(basePackageClasses = EmailBoxEntity.class)
  @EnableJpaRepositories(basePackageClasses = EmailBoxDAO.class)
  static class JpaSliceConfiguration {
  }

  /**
   * Each of the three states makes it to the database and back unchanged.
   */
  @Test
  void bodyFormatSurvivesTheRoundTrip() {
    Long htmlId = persistEmail(1L, "<p>rich</p>", Boolean.TRUE);
    Long plainId = persistEmail(2L, "just text", Boolean.FALSE);
    Long legacyId = persistEmail(3L, "cached before the column existed", null);
    entityManager.clear();

    assertTrue(emailBoxDAO.findById(htmlId).orElseThrow().getHtml());
    assertFalse(emailBoxDAO.findById(plainId).orElseThrow().getHtml());
    assertNull(emailBoxDAO.findById(legacyId).orElseThrow().getHtml(), "an unanswered row must stay unanswered");
  }

  /**
   * The body and its format travel together: reading one back without the other would
   * describe some other message.
   */
  @Test
  void theFormatBelongsToTheBodyItDescribes() {
    Long id = persistEmail(4L, "<div dir=\"ltr\">Hello</div>", Boolean.TRUE);
    entityManager.clear();

    EmailBoxEntity reloaded = emailBoxDAO.findById(id).orElseThrow();
    assertEquals("<div dir=\"ltr\">Hello</div>", reloaded.getBody());
    assertTrue(reloaded.getHtml());
  }

  /**
   * The "whole mailbox as it may be SHOWN" read leaves out every hidden folder in one
   * bound list. Executed here against the engine because nothing in the product calls
   * it yet — it is the read the total one's javadoc tells a future caller to use, so
   * its grammar ({@code NOT IN} over a collection parameter) has to be known to parse
   * and to answer right before that caller exists, not after.
   */
  @Test
  void theShowableReadLeavesOutEveryHiddenFolderAtOnce() {
    persistEmail(10L, MailFolder.INBOX, "kept", Boolean.TRUE);
    persistEmail(11L, MailFolder.SENT, "also kept", Boolean.TRUE);
    persistEmail(12L, MailFolder.TRASH, "deleted", Boolean.TRUE);
    persistEmail(13L, MailFolder.JUNK, "quarantined", Boolean.TRUE);
    entityManager.clear();

    List<Long> shown = emailBoxDAO.findByUserIdExcludingFoldersWithAttachments(USERNAME, MailFolder.HIDDEN_FOLDERS)
                                  .stream()
                                  .map(EmailBoxEntity::getMailRemoteId)
                                  .sorted()
                                  .toList();

    assertEquals(List.of(10L, 11L), shown, "the inbox and sent rows are shown; the deleted and the quarantined ones are not");
  }

  /**
   * Persists one cached inbox message.
   *
   * @param remoteId the IMAP UID
   * @param body the cached body
   * @param html what the message said about that body, null when it was never asked
   * @return the row's generated id
   */
  private Long persistEmail(long remoteId, String body, Boolean html) {
    return persistEmail(remoteId, MailFolder.INBOX, body, html);
  }

  /**
   * Persists one cached message in a given folder.
   *
   * @param remoteId the IMAP UID, within that folder
   * @param folder the {@link MailFolder} discriminator
   * @param body the cached body
   * @param html what the message said about that body, null when it was never asked
   * @return the row's generated id
   */
  private Long persistEmail(long remoteId, String folder, String body, Boolean html) {
    EmailBoxEntity email = new EmailBoxEntity();
    email.setMailRemoteId(remoteId);
    email.setUserId(USERNAME);
    email.setFolder(folder);
    email.setSender("Bob Smith,bob@example.org");
    email.setTo("Alice,alice@example.com");
    email.setCc("");
    email.setReceivedDate(new Date());
    email.setBody(body);
    email.setHtml(html);
    entityManager.persist(email);
    entityManager.flush();
    return email.getId();
  }
}
