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

import java.util.List;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import org.exoplatform.emailConnector.dao.EmailAttachmentDAO;
import org.exoplatform.emailConnector.dao.EmailBoxDAO;
import org.exoplatform.emailConnector.entity.EmailAttachmentEntity;
import org.exoplatform.emailConnector.entity.EmailBoxEntity;
import org.exoplatform.emailConnector.model.Email;
import org.exoplatform.emailConnector.model.EmailAttachment;
import org.exoplatform.emailConnector.model.EmailContent;
import org.exoplatform.emailConnector.model.EmailSender;

import lombok.SneakyThrows;

/**
 * Storage service to access / load and save email box. This service will be
 * used, as well, to convert from JPA entity to DTO.
 */
@Component
public class EmailBoxStorage {

  @Autowired
  private EmailBoxDAO        emailBoxDao;

  @Autowired
  private EmailAttachmentDAO emailAttachmentDAO;

  public Email createEmail(Email email) {
    if (email == null) {
      throw new IllegalArgumentException("email is mandatory");
    }
    EmailBoxEntity emailBoxEntity = toEntity(email);
    emailBoxEntity = emailBoxDao.save(emailBoxEntity);
    return fromEntity(emailBoxEntity, false);
  }

  public void updateEmail(Email email) {
    if (email == null) {
      throw new IllegalArgumentException("email is mandatory");
    }
    EmailBoxEntity emailBoxEntity = toEntity(email);
    emailBoxDao.save(emailBoxEntity);
  }

  public Email getEmailByMailRemoteIdAndUserId(long mailRemoteId, String userId) {
    EmailBoxEntity emailBoxEntity = emailBoxDao.findByMailRemoteIdAndUserId(mailRemoteId, userId);
    return fromEntity(emailBoxEntity, false);
  }

  public List<Email> getEmails(String username) {
    List<EmailBoxEntity> emailBoxEntities = emailBoxDao.findByUserIdWithAttachments(username);
    return emailBoxEntities.stream().map(emailBoxEntity -> fromEntity(emailBoxEntity, true)).toList();
  }

  public void deleteUserEmails(String username) {
    emailBoxDao.deleteByUserId(username);
  }

  public void deleteEmails(List<Long> emailsIds) {
    emailBoxDao.deleteEmailsByIds(emailsIds);
  }

  public EmailAttachment getAttachmentByMailRemoteIdAnId(long mailRemoteId, String attachmentId) {
    EmailAttachmentEntity emailAttachmentEntity = emailAttachmentDAO.findByMailRemoteIdAndAttachmentId(mailRemoteId, attachmentId)
                                                                    .orElse(null);
    ;
    return fromEmailAttachmentEntity(emailAttachmentEntity);
  }

  private EmailBoxEntity toEntity(Email email) {
    if (email == null) {
      return null;
    } else {
      EmailBoxEntity emailBoxEntity = new EmailBoxEntity(email.getId(),
                                                         email.getMailRemoteId(),
                                                         email.getUserId(),
                                                         email.getSubject(),
                                                         email.getContent() != null ? email.getContent().getBody() : null,
                                                         email.getSender().getName(),
                                                         email.getRecievedDate(),
                                                         email.isRead(),
                                                         null);
      List<EmailAttachmentEntity> attachments = email.getContent() != null
          && email.getContent().getAttachments() != null ? email.getContent().getAttachments().stream().map(attachment -> {
            return toEmailAttachmentEntity(attachment, emailBoxEntity);
          }).toList() : null;
      emailBoxEntity.setAttachments(attachments);
      return emailBoxEntity;
    }
  }

  @SneakyThrows
  private Email fromEntity(EmailBoxEntity emailBoxEntity, boolean withAttachments) {
    if (emailBoxEntity == null) {
      return null;
    } else {
      List<EmailAttachment> attachments = withAttachments
          && emailBoxEntity.getAttachments() != null ? emailBoxEntity.getAttachments().stream().map(attachment -> {
            return fromEmailAttachmentEntity(attachment);
          }).toList() : null;
      return new Email(emailBoxEntity.getId(),
                       emailBoxEntity.getMailRemoteId(),
                       emailBoxEntity.getUserId(),
                       emailBoxEntity.getSubject(),
                       new EmailContent(emailBoxEntity.getExcerpt(), attachments),
                       emailBoxEntity.getRecievedDate(),
                       new EmailSender(emailBoxEntity.getSender(), null, null, null),
                       emailBoxEntity.isRead(),
                       null,
                       null,
                       null);
    }
  }

  private EmailAttachmentEntity toEmailAttachmentEntity(EmailAttachment emailAttachment, EmailBoxEntity emailBoxEntity) {
    if (emailAttachment == null) {
      return null;
    } else {
      return new EmailAttachmentEntity(emailAttachment.getId(),
                                       emailBoxEntity,
                                       emailAttachment.getAttachmentRemoteId(),
                                       emailAttachment.getName(),
                                       emailAttachment.getMimeType());
    }
  }

  private EmailAttachment fromEmailAttachmentEntity(EmailAttachmentEntity emailAttachmentEntity) {
    if (emailAttachmentEntity == null) {
      return null;
    } else {
      return new EmailAttachment(emailAttachmentEntity.getId(),
                                 emailAttachmentEntity.getEmail().getMailRemoteId(),
                                 emailAttachmentEntity.getAttachmentRemoteId(),
                                 emailAttachmentEntity.getName(),
                                 emailAttachmentEntity.getMimeType(),
                                 null);
    }
  }
}
