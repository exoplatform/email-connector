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
package org.exoplatform.emailConnector.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import java.util.Date;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import org.exoplatform.commons.api.settings.SettingService;
import org.exoplatform.commons.file.services.FileService;
import org.exoplatform.emailConnector.dao.EmailAttachmentDAO;
import org.exoplatform.emailConnector.dao.EmailBoxDAO;
import org.exoplatform.emailConnector.entity.EmailAttachmentEntity;
import org.exoplatform.emailConnector.entity.EmailBoxEntity;
import org.exoplatform.emailConnector.model.Email;
import org.exoplatform.emailConnector.model.MailFolder;
import org.exoplatform.emailConnector.model.UserEmailSetting;
import org.exoplatform.emailConnector.provider.EmailCredentialsResolver;
import org.exoplatform.emailConnector.storage.EmailBoxStorage;
import org.exoplatform.emailConnector.storage.EmailReadReceiptAnswerStorage;
import org.exoplatform.emailConnector.storage.EmailScheduledSendStorage;
import org.exoplatform.emailConnector.storage.EmailSyncStateStorage;
import org.exoplatform.services.listener.ListenerService;
import org.exoplatform.upload.UploadService;

import io.meeds.social.category.service.CategoryLinkService;
import io.meeds.social.category.service.CategoryService;

/**
 * The transaction boundary of the reads an agent reaches, through the real Spring proxy
 * of {@link EmailBoxService}, the real storage and the real transaction manager over the
 * shipped changelog. A mock-based test cannot see this failure: it lives in the proxy,
 * where a public method that is not itself transactional and calls a transactional
 * method of the same bean runs that call with no transaction at all, and the mapping of
 * a message's lazily loaded attachments then throws
 * {@code LazyInitializationException}. The MCP tools call the service off any portal
 * request life cycle, so nothing else opens a session for them.
 * <p>
 * The test class itself runs outside any transaction, as the MCP tool does.
 */
@DataJpaTest(showSql = false)
@EnableAutoConfiguration
@Import({ EmailBoxStorage.class, EmailBoxService.class })
@TestPropertySource(properties = { "spring.liquibase.enabled=true",
    "spring.liquibase.change-log=classpath:db/changelog/emailConnector-rdbms.db.changelog-master.xml",
    "spring.jpa.hibernate.ddl-auto=none" })
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class EmailBoxServiceTransactionBoundaryTest {

  private static final String           USERNAME = "alice";

  @Autowired
  private EmailBoxService               emailBoxService;

  @Autowired
  private EmailBoxDAO                   emailBoxDao;

  @Autowired
  private EmailAttachmentDAO            emailAttachmentDAO;

  @MockitoBean
  private CategoryLinkService           categoryLinkService;

  @MockitoBean
  private FileService                   fileService;

  @MockitoBean
  private UploadService                 uploadService;

  @MockitoBean
  private EmailFavoriteService          emailFavoriteService;

  @MockitoBean
  private EmailFolderService            emailFolderService;

  @MockitoBean
  private EmailDelegationService        emailDelegationService;

  @MockitoBean
  private CategoryService               categoryService;

  @MockitoBean
  private UserEmailSettingService       userEmailSettingService;

  @MockitoBean
  private EmailSignatureService         emailSignatureService;

  @MockitoBean
  private SettingService                settingService;

  @MockitoBean
  private EmailSyncStateStorage         emailSyncStateStorage;

  @MockitoBean
  private ListenerService               listenerService;

  @MockitoBean
  private EmailConnectorService         emailConnectorService;

  @MockitoBean
  private EmailCredentialsResolver      emailCredentialsResolver;

  @MockitoBean
  private ApplicationEventPublisher     eventPublisher;

  @MockitoBean
  private EmailScheduledSendStorage     emailScheduledSendStorage;

  @MockitoBean
  private SmtpTransmitter               smtpTransmitter;

  @MockitoBean
  private EmailReadReceiptAnswerStorage readReceiptAnswerStorage;

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
   * The reader has a connected mailbox, and no folder of theirs is a shared one.
   */
  @BeforeEach
  void connectedAndNothingShared() {
    UserEmailSetting setting = new UserEmailSetting();
    setting.setEmailConnectorId("1");
    setting.setEmailAddress("alice@example.org");
    when(userEmailSettingService.getUserEmailSetting(USERNAME)).thenReturn(setting);
    when(emailDelegationService.delegationOf(anyString(), any())).thenReturn(null);
  }

  /**
   * The context under test is the proxied one: without it, this class would prove
   * nothing about transaction boundaries.
   */
  @Test
  void theServiceIsTheTransactionalProxy() {
    assertEquals(true, AopUtils.isAopProxy(emailBoxService));
  }

  /**
   * The live regression (MCP {@code get_email_by_id}, every call): the agent's read of
   * one of the user's own messages maps its attachments inside a transaction, so the
   * message comes back whole instead of failing on the lazy collection.
   */
  @Test
  void anAgentsReadOfAMessageLoadsItsAttachments() throws Exception {
    long id = cacheMessageWithAttachment();

    Email email = emailBoxService.getOwnMailboxEmailById(id, USERNAME);

    assertNotNull(email);
    assertNotNull(email.getContent().getAttachments());
    assertEquals(1, email.getContent().getAttachments().size());
  }

  /**
   * The control: the transactional read the agent's entry point delegates to works in
   * this harness, so a failure of the test above is the entry point's own boundary.
   */
  @Test
  void theTransactionalReadItDelegatesToLoadsTheAttachments() throws Exception {
    long id = cacheMessageWithAttachment();

    Email email = emailBoxService.getOwnedEmailById(id, USERNAME);

    assertEquals(1, email.getContent().getAttachments().size());
  }

  /**
   * One cached INBOX message of the reader, carrying one attachment.
   *
   * @return the message's local id
   */
  private long cacheMessageWithAttachment() {
    EmailBoxEntity message = new EmailBoxEntity();
    message.setUserId(USERNAME);
    message.setFolder(MailFolder.INBOX);
    message.setMailRemoteId(7L);
    message.setMailHeaderId("<inbox-7@example.org>");
    message.setSubject("A message with a file");
    message.setBody("body");
    message.setSender("Bob,bob@example.org");
    message.setReceivedDate(new Date());
    message = emailBoxDao.save(message);

    EmailAttachmentEntity attachment = new EmailAttachmentEntity();
    attachment.setEmail(message);
    attachment.setAttachmentRemoteId("2");
    attachment.setName("report.pdf");
    attachment.setMimeType("application/pdf");
    emailAttachmentDAO.save(attachment);
    return message.getId();
  }
}
