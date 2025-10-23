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

import org.exoplatform.emailConnector.dao.EmailBoxDAO;
import org.exoplatform.emailConnector.entity.EmailBoxEntity;
import org.exoplatform.emailConnector.model.Email;
import org.exoplatform.emailConnector.model.EmailSender;

import lombok.SneakyThrows;

/**
 * Storage service to access / load and save email box. This service will be
 * used, as well, to convert from JPA entity to DTO.
 */
@Component
public class EmailBoxStorage {

  @Autowired
  private EmailBoxDAO emailBoxDao;

  
  public Email createEmail(Email email) {
    if (email == null) {
      throw new IllegalArgumentException("email is mandatory");
    }
    EmailBoxEntity emailBoxEntity = toEntity(email);
    emailBoxEntity = emailBoxDao.save(emailBoxEntity);
    return fromEntity(emailBoxEntity);
  }

  public Email getEmailByMailRemoteIdAndUserId(String userId, long mailRemoteId) {
    EmailBoxEntity emailBoxEntity = emailBoxDao.findByUserIdAndMailRemoteId(userId, mailRemoteId);
    return fromEntity(emailBoxEntity);
  }

  public List<Email> getEmails(String username) {
    List<EmailBoxEntity> emailBoxEntities = emailBoxDao.findByUserIdOrderBySentDateDesc(username);
    return emailBoxEntities.stream().map(emailBoxEntity -> fromEntity(emailBoxEntity)).toList();
  }

  public void deleteUserEmails(String username) {
    emailBoxDao.deleteByUserId(username);
  }

  public void deleteEmails(List<Long> emailsIds) {
    emailBoxDao.deleteEmailsByIds(emailsIds);
  }

  private EmailBoxEntity toEntity(Email email) {
    if (email == null) {
      return null;
    } else {
      return new EmailBoxEntity(email.getId(),
                                email.getMailRemoteId(),
                                email.getUserId(),
                                email.getSubject(),
                                email.getContent(),
                                email.getSender().getName(),
                                email.getSentDate());
    }
  }

  @SneakyThrows
  private Email fromEntity(EmailBoxEntity emailBoxEntity) {
    if (emailBoxEntity == null) {
      return null;
    } else {
      return new Email(emailBoxEntity.getId(),
                       emailBoxEntity.getMailRemoteId(),
                       emailBoxEntity.getUserId(),
                       emailBoxEntity.getSubject(),
                       emailBoxEntity.getExcerpt(),
                       emailBoxEntity.getSentDate(),
                       new EmailSender(emailBoxEntity.getSender(), null, null, null),
                       null,
                       null,
                       null);
    }
  }
}
