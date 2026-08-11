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

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;

import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;

import org.exoplatform.emailConnector.dao.EmailAttachmentDAO;
import org.exoplatform.emailConnector.dao.EmailBoxDAO;
import org.exoplatform.emailConnector.entity.EmailAttachmentEntity;
import org.exoplatform.emailConnector.entity.EmailBoxEntity;
import org.exoplatform.emailConnector.model.Email;
import org.exoplatform.emailConnector.model.EmailAttachment;
import org.exoplatform.emailConnector.model.EmailContent;
import org.exoplatform.emailConnector.model.EmailSender;

import io.meeds.social.category.service.CategoryLinkService;

@SpringBootTest(classes = { EmailBoxStorage.class })
@ExtendWith(MockitoExtension.class)
public class EmailBoxStorageTest {

  private static final Long   ID = 2l;

  @MockBean
  private EmailBoxDAO         emailBoxDAO;

  @MockBean
  private EmailAttachmentDAO  emailAttachmentDAO;

  @MockBean
  private CategoryLinkService categoryLinkService;

  @Autowired
  private EmailBoxStorage     emailBoxStorage;

  @BeforeEach
  void setup() {

    when(emailBoxDAO.save(any())).thenAnswer(invocation -> {
      EmailBoxEntity entity = invocation.getArgument(0);
      if (entity.getId() == null) {
        entity.setId(ID);
      }
      when(emailBoxDAO.findByUserIdWithAttachments("root")).thenReturn(Optional.of(entity)
                                                                               .stream()
                                                                               .filter(email -> email.getUserId().equals("root"))
                                                                               .toList());
      when(emailBoxDAO.findByMailRemoteIdAndUserIdAndFolder(1212l, "root", "INBOX")).thenReturn(entity);

      when(emailBoxDAO.findById(ID)).thenReturn(Optional.of(entity));

      return entity;
    });

    doAnswer(invocation -> {
      when(emailBoxDAO.findByUserIdWithAttachments("root")).thenReturn(Collections.emptyList());
      return null;
    }).when(emailBoxDAO).deleteByUserId(any());

    doAnswer(invocation -> {
      when(emailBoxDAO.findByUserIdWithAttachments("root")).thenReturn(Collections.emptyList());
      return null;
    }).when(emailBoxDAO).deleteEmailsByIds(any());

    doAnswer(invocation -> {
      boolean readStatus = invocation.getArgument(2);
      EmailBoxEntity entity = emailBoxDAO.findByMailRemoteIdAndUserIdAndFolder(1212L, "root", "INBOX");
      if (entity != null) {
        entity.setRead(readStatus);
      }
      return null;
    }).when(emailBoxDAO).updateReadStatusByMailRemoteIds(anyList(), anyString(), anyBoolean(), anyString());

    doAnswer(invocation -> {
      EmailBoxEntity entity = emailBoxDAO.findByMailRemoteIdAndUserIdAndFolder(1212L, "root", "INBOX");
      if (entity != null) {
        entity.setRecent(false);
      }
      return null;
    }).when(emailBoxDAO).markEmailAsNotRecent(anyLong(), anyString(), anyString());
  }

  @Test
  void createEmail() {
    assertThrows(IllegalArgumentException.class, () -> emailBoxStorage.createEmail(null));
    Email email = email("root");
    Email storedEmail = emailBoxStorage.createEmail(email);
    assertNotNull(storedEmail);
    assertNotNull(storedEmail.getId());
    assertTrue(storedEmail.getId() > 0);
  }

  @Test
  void markEmailAsNotRecent() {
    Email email = email("root");
    emailBoxStorage.createEmail(email);
    emailBoxStorage.markEmailAsNotRecent(1212l, "root", "INBOX");
    Email updatedEmail = emailBoxStorage.getEmailByMailRemoteIdAndUserId(1212l, "root", null, "INBOX", false, false, false);
    assertNotNull(updatedEmail);
    assertFalse(updatedEmail.isRecent());
  }

  @Test
  void updateEmailReadStatusByMailRemoteIds() {
    Email email = email("root");
    emailBoxStorage.createEmail(email);
    emailBoxStorage.updateEmailReadStatusByMailRemoteIds(List.of(1212l), "root", true, "INBOX");
    Email updatedEmail = emailBoxStorage.getEmailByMailRemoteIdAndUserId(1212l, "root", null, "INBOX", false, false, false);
    assertNotNull(updatedEmail);
    assertTrue(updatedEmail.isRead());
  }

  @Test
  void getEmailByMailRemoteIdAndUserId() {
    Email retrievedEmail = emailBoxStorage.getEmailByMailRemoteIdAndUserId(1212l, "root", null, "INBOX", false, false, false);
    assertNull(retrievedEmail);
    Email email1 = email("root");
    emailBoxStorage.createEmail(email1);
    retrievedEmail = emailBoxStorage.getEmailByMailRemoteIdAndUserId(1212l, "root", null, "INBOX", false, false, false);
    assertNotNull(retrievedEmail);
  }

  @Test
  void getEmailById() {
    Email retrievedEmail = emailBoxStorage.getEmailById(2l, "root", null);
    assertNull(retrievedEmail);
    Email email1 = email("root");
    emailBoxStorage.createEmail(email1);
    retrievedEmail = emailBoxStorage.getEmailById(2l, "root", null);
    assertNotNull(retrievedEmail);
  }

  @Test
  void getEmails() {
    List<Email> retrievedEmailEntities = emailBoxStorage.getEmails("root");
    assertEquals(0, retrievedEmailEntities.size());
    Email email1 = email("root");
    emailBoxStorage.createEmail(email1);
    retrievedEmailEntities = emailBoxStorage.getEmails("root");
    assertNotNull(retrievedEmailEntities);
    assertEquals(1, retrievedEmailEntities.size());
    assertNotNull(retrievedEmailEntities.get(0));
    assertEquals(2l, retrievedEmailEntities.get(0).getId());
    assertEquals(1212l, retrievedEmailEntities.get(0).getMailRemoteId());
    assertEquals("subject", retrievedEmailEntities.get(0).getSubject());
    assertEquals("body", retrievedEmailEntities.get(0).getContent().getBody());
    assertEquals("body", retrievedEmailEntities.get(0).getContent().getExcerpt());
    assertEquals("sender", retrievedEmailEntities.get(0).getSender().getName());
  }

  @Test
  void deleteEmailsByIds() {
    Email email1 = email("root");
    Email storedEmail = emailBoxStorage.createEmail(email1);
    List<Email> retrievedEmailEntities = emailBoxStorage.getEmails("root");
    assertNotNull(retrievedEmailEntities);
    assertEquals(1, retrievedEmailEntities.size());
    List<Long> emailsIds = List.of(storedEmail.getId());
    emailBoxStorage.deleteEmailsByIds(emailsIds);
    retrievedEmailEntities = emailBoxStorage.getEmails("root");
    assertEquals(0, retrievedEmailEntities.size());
  }

  @Test
  void getAttachmentByIdAndMailRemoteId() {
    EmailAttachment retrievedEmailAttachment = emailBoxStorage.getAttachmentByMailRemoteIdAnIdAndUserId(1212l, "2", "root");
    assertNull(retrievedEmailAttachment);
    EmailBoxEntity emailBoxEntity = new EmailBoxEntity(null,
                                                       1212l,
                                                       null,
                                                       "root",
                                                       "subject",
                                                       "body",
                                                       "sender",
                                                       "to",
                                                       "cc",
                                                       "bcc",
                                                       "replyTo",
                                                       new Date(),
                                                       false,
                                                       false,
                                                       null,
                                                       null,
                                                       null,
                                                       null,
                                                       "INBOX");
    Optional<EmailAttachmentEntity> emailAttachmentEntity = Optional.ofNullable(new EmailAttachmentEntity(2L,
                                                                                                          emailBoxEntity,
                                                                                                          "2",
                                                                                                          "attachment.pdf",
                                                                                                          "application/pdf"));
    when(emailAttachmentDAO.findByMailRemoteIdAndAttachmentIdAndUserId(1212l, "2", "root")).thenReturn(emailAttachmentEntity);
    retrievedEmailAttachment = emailBoxStorage.getAttachmentByMailRemoteIdAnIdAndUserId(1212l, "2", "root");
    assertNotNull(retrievedEmailAttachment);
    retrievedEmailAttachment = emailBoxStorage.getAttachmentByMailRemoteIdAnIdAndUserId(1212L, "2", "root");
    assertNotNull(retrievedEmailAttachment);
    assertEquals("attachment.pdf", retrievedEmailAttachment.getName());
    assertEquals("application/pdf", retrievedEmailAttachment.getMimeType());
    assertEquals(1212L, retrievedEmailAttachment.getMailRemoteId());
  }

  private Email email(String username) {
    EmailAttachment emailAttachment = emailAttachment();
    return new Email(null,
                     1212l,
                     null,
                     username,
                     null,
                     "subject",
                     new EmailContent("body", null, List.of(emailAttachment)),
                     new Date(),
                     new EmailSender("sender", null, null, null),
                     false,
                     false,
                     null,
                     null,
                     null,
                     null,
                     null,
                     null,
                     null,
                     null,
                     null,
                     "INBOX");
  }

  private EmailAttachment emailAttachment() {
    return new EmailAttachment(null, 1212l, "2", "attachment.pdf", "application/pdf", null);
  }
}
