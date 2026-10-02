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
 * The mail list's brand logo condition (EXO-90893), run through the real query on the
 * real changelog's schema: a mailbox holds genuine mail from an address only when one
 * of its rows from it passed DMARC and failed no sender check.
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
class EmailBoxVerifiedSenderStorageTest {

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

  /**
   * Only a row that passed DMARC and failed nothing counts, whatever the case of the
   * address, in any folder; an unverified, a failing or an older row does not, nor
   * another mailbox's.
   */
  @Test
  void onlyAVouchedForRowCounts() {
    save("rita", MailFolder.INBOX, "Brand,News@Brand.example", Boolean.TRUE, null);
    save("rita", "CUSTOM:1", "bare@brand.example", Boolean.TRUE, null);
    save("rita", MailFolder.INBOX, "Spoof,alerts@bank.example", Boolean.FALSE, null);
    save("rita", MailFolder.INBOX, "Old,old@shop.example", null, null);
    save("rita", MailFolder.INBOX, "Mixed,mixed@shop.example", Boolean.TRUE, "SPF");
    save("sam", MailFolder.INBOX, "Dan,dan@verified.example", Boolean.TRUE, null);

    assertTrue(emailBoxStorage.hasVerifiedMailFrom("rita", "news@brand.example"));
    assertTrue(emailBoxStorage.hasVerifiedMailFrom("rita", " BARE@brand.example "));
    assertFalse(emailBoxStorage.hasVerifiedMailFrom("rita", "alerts@bank.example"));
    assertFalse(emailBoxStorage.hasVerifiedMailFrom("rita", "old@shop.example"), "a row cached before the verdict was recorded");
    assertFalse(emailBoxStorage.hasVerifiedMailFrom("rita", "mixed@shop.example"), "a failed sender check");
    assertFalse(emailBoxStorage.hasVerifiedMailFrom("rita", "dan@verified.example"), "another mailbox's");
    assertFalse(emailBoxStorage.hasVerifiedMailFrom("", "news@brand.example"));
    assertFalse(emailBoxStorage.hasVerifiedMailFrom("rita", " "));
  }

  /** An address carrying LIKE wildcards is taken literally. */
  @Test
  void takesTheAddressLiterally() {
    save("tina", MailFolder.INBOX, "Bob,bob@brand.example", Boolean.TRUE, null);

    assertFalse(emailBoxStorage.hasVerifiedMailFrom("tina", "%@brand.example"));
    assertFalse(emailBoxStorage.hasVerifiedMailFrom("tina", "b_b@brand.example"));
    assertTrue(emailBoxStorage.hasVerifiedMailFrom("tina", "bob@brand.example"));
  }

  /**
   * Stores one cached message.
   *
   * @param owner the mailbox owner
   * @param folder the {@link MailFolder} discriminator
   * @param sender the stored sender
   * @param dmarcPass the stored DMARC pass, null for a row older than it
   * @param authFailure the stored failed check, or null
   */
  private void save(String owner, String folder, String sender, Boolean dmarcPass, String authFailure) {
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
    email.setDmarcPass(dmarcPass);
    email.setAuthFailure(authFailure);
    emailBoxDAO.save(email);
  }
}
