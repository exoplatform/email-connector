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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Date;

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
import org.exoplatform.emailConnector.model.MailFolder;
import org.exoplatform.upload.UploadService;

import io.meeds.social.category.service.CategoryLinkService;

/**
 * Whether a mailbox holds mail from an address (EXO-90891), end to end from the
 * storage to the SHIPPED Liquibase schema: the stored {@code name,address} sender and
 * the address stored alone, whatever the case, the owner's rows only, and an address
 * taken literally -- its LIKE wildcards match nothing else.
 * <p>
 * Nothing rolls back here ({@link Propagation#NOT_SUPPORTED}, as the sibling storage
 * rigs), so each test works in a mailbox of its own.
 */
@DataJpaTest(showSql = false)
@EnableAutoConfiguration
@Import(EmailBoxStorage.class)
@TestPropertySource(properties = { "spring.liquibase.enabled=true",
    "spring.liquibase.change-log=classpath:db/changelog/emailConnector-rdbms.db.changelog-master.xml",
    "spring.jpa.hibernate.ddl-auto=none" })
@Transactional(propagation = Propagation.NOT_SUPPORTED)
public class EmailBoxSenderPresenceStorageTest {

  @Autowired
  private EmailBoxStorage     emailBoxStorage;

  @Autowired
  private EmailBoxDAO         emailBoxDAO;

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

  /** A named sender and a bare one are found whatever the case, in any folder; another mailbox's never. */
  @Test
  void findsTheOwnersSendersWhateverTheCase() {
    save("rita", MailFolder.INBOX, "Carol Private,Carol.Private@Gmail.com");
    save("rita", "CUSTOM:1", "bare@example.org");
    save("sam", MailFolder.INBOX, "Dan,dan@example.org");

    assertTrue(emailBoxStorage.hasMailFrom("rita", "carol.private@gmail.com"));
    assertTrue(emailBoxStorage.hasMailFrom("rita", " BARE@example.org "));
    assertFalse(emailBoxStorage.hasMailFrom("rita", "dan@example.org"));
    assertFalse(emailBoxStorage.hasMailFrom("rita", "private@gmail.com"));
    assertFalse(emailBoxStorage.hasMailFrom("", "carol.private@gmail.com"));
    assertFalse(emailBoxStorage.hasMailFrom("rita", " "));
  }

  /** An address carrying LIKE wildcards is taken literally. */
  @Test
  void takesTheAddressLiterally() {
    save("tina", MailFolder.INBOX, "Bob,bob@example.org");

    assertFalse(emailBoxStorage.hasMailFrom("tina", "%@example.org"));
    assertFalse(emailBoxStorage.hasMailFrom("tina", "b_b@example.org"));
    assertTrue(emailBoxStorage.hasMailFrom("tina", "bob@example.org"));
  }

  /**
   * Stores one cached message.
   *
   * @param owner the mailbox owner
   * @param folder the {@link MailFolder} discriminator
   * @param sender the stored sender
   */
  private void save(String owner, String folder, String sender) {
    EmailBoxEntity email = new EmailBoxEntity();
    email.setMailRemoteId(System.nanoTime());
    email.setUserId(owner);
    email.setFolder(folder);
    email.setSubject("subject");
    email.setSender(sender);
    email.setTo("Someone,someone@example.org");
    email.setCc("");
    email.setReceivedDate(new Date());
    email.setBody("body");
    emailBoxDAO.save(email);
  }
}
