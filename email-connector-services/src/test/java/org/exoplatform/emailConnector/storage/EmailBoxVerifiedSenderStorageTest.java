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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Date;
import java.util.List;

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
   * EXO-90909 -- the rows without a verdict are read newest first, a folder and a
   * mailbox at a time, within the limit, with their keys and sender; a verdict is
   * written on the owner's rows that still have none only, and a row given one by a
   * sync meanwhile keeps its own.
   */
  @Test
  void theRowsWithoutAVerdictAreFilledIn() {
    long day = 24L * 60 * 60 * 1000;
    long base = System.currentTimeMillis() - 10 * day;
    EmailBoxEntity oldest = save("uma", MailFolder.INBOX, "Old,old@brand.example", null, null, new Date(base));
    EmailBoxEntity middle = save("uma", MailFolder.INBOX, "bare@brand.example", null, null, new Date(base + day));
    EmailBoxEntity newest = save("uma", MailFolder.INBOX, "New,new@brand.example", null, null, new Date(base + 2 * day));
    EmailBoxEntity decided = save("uma", MailFolder.INBOX, "Done,done@brand.example", Boolean.FALSE, null, new Date(base + 3 * day));
    save("uma", MailFolder.ARCHIVE, "Arch,arch@brand.example", null, null, new Date(base + 4 * day));
    EmailBoxEntity others = save("vic", MailFolder.INBOX, "Vic,vic@brand.example", null, null, new Date(base + 5 * day));

    List<Email> rows = emailBoxStorage.getEmailsWithoutDmarcVerdict("uma", MailFolder.INBOX, 2);
    assertEquals(List.of(newest.getId(), middle.getId()), rows.stream().map(Email::getId).toList());
    Email first = rows.get(0);
    assertEquals(newest.getMailRemoteId(), first.getMailRemoteId());
    assertEquals(MailFolder.INBOX, first.getFolder());
    assertEquals("new@brand.example", first.getSender().getAddress());
    assertEquals("bare@brand.example", rows.get(1).getSender().getAddress());
    assertEquals(3, emailBoxStorage.getEmailsWithoutDmarcVerdict("uma", MailFolder.INBOX, 50).size());

    assertEquals(2, emailBoxStorage.setDmarcVerdict("uma", List.of(newest.getId(), decided.getId(), others.getId(), oldest.getId()), true));
    assertEquals(Boolean.TRUE, emailBoxDAO.findById(newest.getId()).orElseThrow().getDmarcPass());
    assertEquals(Boolean.TRUE, emailBoxDAO.findById(oldest.getId()).orElseThrow().getDmarcPass());
    assertEquals(Boolean.FALSE, emailBoxDAO.findById(decided.getId()).orElseThrow().getDmarcPass(), "a verdict is never overwritten");
    assertNull(emailBoxDAO.findById(others.getId()).orElseThrow().getDmarcPass(), "another mailbox's row");
    assertEquals(1, emailBoxStorage.setDmarcVerdict("uma", List.of(middle.getId()), false));
    assertEquals(List.of(), emailBoxStorage.getEmailsWithoutDmarcVerdict("uma", MailFolder.INBOX, 50));
    assertTrue(emailBoxStorage.hasVerifiedMailFrom("uma", "new@brand.example"), "a filled pass counts for the list");
    assertEquals(0, emailBoxStorage.setDmarcVerdict("uma", List.of(), true));
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
    save(owner, folder, sender, dmarcPass, authFailure, new Date());
  }

  /**
   * Stores one cached message received at a given date.
   *
   * @param owner the mailbox owner
   * @param folder the {@link MailFolder} discriminator
   * @param sender the stored sender
   * @param dmarcPass the stored DMARC pass, null for a row older than it
   * @param authFailure the stored failed check, or null
   * @param receivedDate when it was received
   * @return the stored row
   */
  private EmailBoxEntity save(String owner, String folder, String sender, Boolean dmarcPass, String authFailure, Date receivedDate) {
    EmailBoxEntity email = new EmailBoxEntity();
    email.setMailRemoteId(System.nanoTime());
    email.setUserId(owner);
    email.setFolder(folder);
    email.setSubject("subject");
    email.setSender(sender);
    email.setTo("Someone,someone@example.org");
    email.setCc("");
    email.setReceivedDate(receivedDate);
    email.setBody("body");
    email.setDmarcPass(dmarcPass);
    email.setAuthFailure(authFailure);
    return emailBoxDAO.save(email);
  }
}
