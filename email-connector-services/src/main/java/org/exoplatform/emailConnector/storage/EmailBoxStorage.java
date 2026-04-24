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

import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

import javax.mail.internet.InternetAddress;

import org.jsoup.Jsoup;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import org.exoplatform.emailConnector.dao.EmailAttachmentDAO;
import org.exoplatform.emailConnector.dao.EmailBoxDAO;
import org.exoplatform.emailConnector.entity.EmailAttachmentEntity;
import org.exoplatform.emailConnector.entity.EmailBoxEntity;
import org.exoplatform.emailConnector.model.Email;
import org.exoplatform.emailConnector.model.EmailAttachment;
import org.exoplatform.emailConnector.model.EmailContent;
import org.exoplatform.emailConnector.model.EmailRecipient;
import org.exoplatform.emailConnector.plugin.EmailCategoryPlugin;
import org.exoplatform.emailConnector.utils.EmailConnectorUtils;
import org.exoplatform.social.core.identity.model.Identity;
import org.exoplatform.social.core.identity.model.Profile;
import org.exoplatform.social.core.manager.IdentityManager;

import io.meeds.social.category.model.CategoryObject;
import io.meeds.social.category.service.CategoryLinkService;
import lombok.SneakyThrows;

/**
 * Storage service to access / load and save email box. This service will be
 * used, as well, to convert from JPA entity to DTO.
 */
@Component
public class EmailBoxStorage {

  @Autowired
  private EmailBoxDAO         emailBoxDao;

  @Autowired
  private EmailAttachmentDAO  emailAttachmentDAO;

  @Autowired
  private CategoryLinkService categoryLinkService;

  @Autowired
  private IdentityManager     identityManager;

  public Email createEmail(Email email) {
    if (email == null) {
      throw new IllegalArgumentException("email is mandatory");
    }
    EmailBoxEntity emailBoxEntity = toEntity(email);
    emailBoxEntity = emailBoxDao.save(emailBoxEntity);
    return fromEntity(emailBoxEntity, false, false, null, true, false);
  }

  public void markEmailAsNotRecent(Long mailRemoteId, String userId) {
    emailBoxDao.markEmailAsNotRecent(mailRemoteId, userId);
  }

  public void updateEmailReadStatusByMailRemoteIds(List<Long> mailRemoteIds, String userId, boolean readStatus) {
    emailBoxDao.updateReadStatusByMailRemoteIds(mailRemoteIds, userId, readStatus);
  }

  public Email getEmailByMailRemoteIdAndUserId(long mailRemoteId,
                                               String userId,
                                               boolean withAttachments,
                                               boolean withRecipients,
                                               boolean withProfile) {
    EmailBoxEntity emailBoxEntity = emailBoxDao.findByMailRemoteIdAndUserId(mailRemoteId, userId);
    return fromEntity(emailBoxEntity, withAttachments, false, userId, withRecipients, withProfile);
  }

  public Email getEmailById(long id, String userId) {
    EmailBoxEntity emailBoxEntity = emailBoxDao.findById(id).orElse(null);
    return fromEntity(emailBoxEntity, true, false, userId, true, true);
  }

  public List<Email> getEmails(String userId) {
    List<EmailBoxEntity> emailBoxEntities = emailBoxDao.findByUserIdWithAttachments(userId);
    return emailBoxEntities.stream().map(emailBoxEntity -> fromEntity(emailBoxEntity, true, true, userId, false, false)).toList();
  }

  public void deleteEmailsByIds(List<Long> emailsIds) {
    emailBoxDao.deleteEmailsByIds(emailsIds);
  }

  public EmailAttachment getAttachmentByMailRemoteIdAnIdAndUserId(long mailRemoteId, String attachmentId, String userId) {
    EmailAttachmentEntity emailAttachmentEntity = emailAttachmentDAO
                                                                    .findByMailRemoteIdAndAttachmentIdAndUserId(mailRemoteId,
                                                                                                                attachmentId,
                                                                                                                userId)
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
                                                         email.getMailHeaderId(),
                                                         email.getUserId(),
                                                         email.getSubject(),
                                                         email.getContent() != null ? email.getContent().getBody() : null,
                                                         email.getSender() != null ? email.getSender().getName() + ","
                                                             + email.getSender().getAddress() : "",
                                                         toRecipientsString(email.getTo()),
                                                         toRecipientsString(email.getCc()),
                                                         toRecipientsString(email.getBcc()),
                                                         email.getReceivedDate(),
                                                         email.isRead(),
                                                         email.isRecent(),
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
  private Email fromEntity(EmailBoxEntity emailBoxEntity,
                           boolean withAttachments,
                           boolean isExcerpt,
                           String userId,
                           boolean withRecipients,
                           boolean withProfile) {
    if (emailBoxEntity == null) {
      return null;
    } else {
      List<EmailAttachment> attachments = withAttachments
          && emailBoxEntity.getAttachments() != null ? emailBoxEntity.getAttachments().stream().map(this::fromEmailAttachmentEntity).filter(Objects::nonNull).toList() : null;
      String excerpt = null;
      if (isExcerpt) {
        excerpt = Jsoup.parse(emailBoxEntity.getBody()).text().trim();
        if (excerpt.length() > 50) {
          excerpt = excerpt.substring(0, 50) + "...";
        }
      }
      String[] emailSenderParts = emailBoxEntity.getSender().split(",");
      InternetAddress emailSenderAddress = new InternetAddress(emailSenderParts[1], emailSenderParts[0]);
      List<Long> categoryIds = categoryLinkService.getLinkedIds(new CategoryObject(EmailCategoryPlugin.OBJECT_TYPE,
                                                                                   String.valueOf(emailBoxEntity.getId()),
                                                                                   0));
      Identity currentIdentity = identityManager.getOrCreateUserIdentity(userId);
      Email email = new Email(emailBoxEntity.getId(),
                              emailBoxEntity.getMailRemoteId(),
                              emailBoxEntity.getMailHeaderId(),
                              emailBoxEntity.getUserId(),
                              currentIdentity != null ? currentIdentity.getProfile().getEmail() : null,
                              emailBoxEntity.getSubject(),
                              new EmailContent(emailBoxEntity.getBody(), excerpt, attachments),
                              emailBoxEntity.getReceivedDate(),
                              EmailConnectorUtils.getEmailSender(emailSenderAddress, withProfile),
                              emailBoxEntity.isRead(),
                              emailBoxEntity.isRecent(),
                              null,
                              null,
                              null,
                              categoryIds);

      if (withRecipients) {
        InternetAddress[] emailToRecipientsInternetAddresses = toRecipientsInternetAddresses(emailBoxEntity.getTo());
        InternetAddress[] emailCcRecipientsInternetAddresses = toRecipientsInternetAddresses(emailBoxEntity.getCc());
        InternetAddress[] emailBccRecipientsInternetAddresses = toRecipientsInternetAddresses(emailBoxEntity.getBcc());
        email.setTo(EmailConnectorUtils.getEmailRecipients(emailToRecipientsInternetAddresses, userId, withProfile));
        email.setCc(EmailConnectorUtils.getEmailRecipients(emailCcRecipientsInternetAddresses, userId, withProfile));
        email.setBcc(EmailConnectorUtils.getEmailRecipients(emailBccRecipientsInternetAddresses, userId, withProfile));
      }
      return email;
    }
  }

  private EmailAttachmentEntity toEmailAttachmentEntity(EmailAttachment emailAttachment, EmailBoxEntity emailBoxEntity) {
    if (emailAttachment == null || emailAttachment.getName() == null) {
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

  private String toRecipientsString(List<EmailRecipient> recipients) {
    if (recipients == null || recipients.isEmpty()) {
      return "";
    }
    return recipients.stream()
                     .map(recipient -> recipient.getName() + "," + recipient.getAddress())
                     .collect(Collectors.joining(";"));
  }

  private static InternetAddress[] toRecipientsInternetAddresses(String recipientsString) {
    if (recipientsString == null || recipientsString.trim().isEmpty()) {
      return new InternetAddress[0];
    }
    return Arrays.stream(recipientsString.split(";")).map(entry -> {
      String[] parts = entry.split(",", 2);
      String name = parts.length > 0 ? parts[0] : "";
      String address = parts.length > 1 ? parts[1] : "";
      try {
        return new InternetAddress(address, name);
      } catch (Exception e) {
        return null;
      }
    }).filter(Objects::nonNull).toArray(InternetAddress[]::new);
  }
}
