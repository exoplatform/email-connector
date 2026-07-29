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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
import org.exoplatform.emailConnector.model.MailFolder;
import org.exoplatform.emailConnector.plugin.EmailCategoryPlugin;
import org.exoplatform.emailConnector.utils.EmailConnectorUtils;

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

  public Email createEmail(Email email) {
    if (email == null) {
      throw new IllegalArgumentException("email is mandatory");
    }
    EmailBoxEntity emailBoxEntity = toEntity(email);
    emailBoxEntity = emailBoxDao.save(emailBoxEntity);
    return fromEntity(emailBoxEntity, false, false, null, null, true, false);
  }

  public void markEmailAsNotRecent(Long mailRemoteId, String userId, String folder) {
    emailBoxDao.markEmailAsNotRecent(mailRemoteId, userId, folder);
  }

  public void updateEmailReadStatusByMailRemoteIds(List<Long> mailRemoteIds, String userId, boolean readStatus, String folder) {
    emailBoxDao.updateReadStatusByMailRemoteIds(mailRemoteIds, userId, readStatus, folder);
  }

  /**
   * The {@code References} header of a cached message, looked up by its Message-ID, so
   * a reply can extend the parent's chain rather than replace it. Null when the parent
   * is no longer cached (its window slot was reclaimed).
   */
  public String getMailReferencesByMailHeaderId(String mailHeaderId, String userId) {
    List<EmailBoxEntity> entities = emailBoxDao.findByMailHeaderIdAndUserId(mailHeaderId, userId);
    return entities.isEmpty() ? null : entities.get(0).getMailReferences();
  }

  /**
   * The distinct thread ids of the cached messages a new message points back to (by
   * Message-ID). Empty when it references nothing cached — i.e. it starts a new thread.
   */
  public List<String> getSiblingThreadIds(String userId, List<String> mailHeaderIds) {
    if (mailHeaderIds == null || mailHeaderIds.isEmpty()) {
      return List.of();
    }
    return emailBoxDao.findDistinctThreadIdsByMailHeaderIds(userId, mailHeaderIds);
  }

  /**
   * The distinct thread ids of cached messages sharing an Exchange Thread-Index
   * conversation root — the same conversation even when References is broken.
   */
  public List<String> getThreadIdsByThreadIndexRoot(String userId, String threadIndexRoot) {
    if (threadIndexRoot == null || threadIndexRoot.isEmpty()) {
      return List.of();
    }
    return emailBoxDao.findDistinctThreadIdsByThreadIndexRoot(userId, threadIndexRoot);
  }

  /**
   * Of several thread ids, the one whose earliest message is oldest — the canonical id
   * a merge collapses the others into.
   */
  public String getOldestThreadId(String userId, List<String> threadIds) {
    if (threadIds == null || threadIds.isEmpty()) {
      return null;
    }
    List<String> ordered = emailBoxDao.findThreadIdsOrderedByAge(userId, threadIds);
    return ordered.isEmpty() ? null : ordered.get(0);
  }

  public void mergeThreads(String userId, String canonicalThreadId, List<String> threadIds) {
    if (threadIds != null && !threadIds.isEmpty()) {
      emailBoxDao.mergeThreads(userId, canonicalThreadId, threadIds);
    }
  }

  public void updateThreadInfo(String userId, Long mailRemoteId, String threadId, String inReplyTo, String mailReferences, String folder, String threadIndexRoot) {
    emailBoxDao.updateThreadInfo(userId, mailRemoteId, threadId, inReplyTo, mailReferences, folder, threadIndexRoot);
  }

  public void updateThreadIndexRoot(String userId, Long mailRemoteId, String folder, String threadIndexRoot) {
    emailBoxDao.updateThreadIndexRoot(userId, mailRemoteId, folder, threadIndexRoot);
  }

  public Email getEmailByMailRemoteIdAndUserId(long mailRemoteId,
                                               String userId,
                                               String userEmail,
                                               String folder,
                                               boolean withAttachments,
                                               boolean withRecipients,
                                               boolean withProfile) {
    EmailBoxEntity emailBoxEntity = emailBoxDao.findByMailRemoteIdAndUserIdAndFolder(mailRemoteId, userId, folder);
    return fromEntity(emailBoxEntity, withAttachments, false, userId, userEmail, withRecipients, withProfile);
  }

  public Email getEmailById(long id, String userId, String userEmail) {
    EmailBoxEntity emailBoxEntity = emailBoxDao.findById(id).orElse(null);
    return fromEntity(emailBoxEntity, true, false, userId, userEmail, true, true);
  }

  public List<Email> getEmails(String userId) {
    List<EmailBoxEntity> emailBoxEntities = emailBoxDao.findByUserIdWithAttachments(userId);
    return emailBoxEntities.stream()
                           .map(emailBoxEntity -> fromEntity(emailBoxEntity, true, true, userId, null, false, false))
                           .toList();
  }

  public List<Email> getEmails(String userId, String folder) {
    List<EmailBoxEntity> emailBoxEntities = emailBoxDao.findByUserIdAndFolderWithAttachments(userId, folder);
    return emailBoxEntities.stream()
                           .map(emailBoxEntity -> fromEntity(emailBoxEntity, true, true, userId, null, false, false))
                           .toList();
  }

  /**
   * The total number of cached messages per conversation, across every folder, keyed
   * by thread id — so the inbox list can show the full conversation count (Gmail-style)
   * rather than only the messages that happen to be in the inbox.
   *
   * @param userId the mailbox owner
   * @return a map of thread id to its cached message count
   */
  public Map<String, Integer> getThreadMessageCounts(String userId) {
    Map<String, Integer> counts = new HashMap<>();
    for (Object[] row : emailBoxDao.countMessagesByThread(userId)) {
      counts.put((String) row[0], ((Number) row[1]).intValue());
    }
    return counts;
  }

  /**
   * The number of cached messages per folder, so the list's folder switch can hide
   * folders that have no mail (e.g. Archive on a Gmail account).
   *
   * @param userId the mailbox owner
   * @return a map of folder discriminator to its message count
   */
  public Map<String, Integer> getFolderMessageCounts(String userId) {
    Map<String, Integer> counts = new HashMap<>();
    for (Object[] row : emailBoxDao.countMessagesByFolder(userId)) {
      counts.put((String) row[0], ((Number) row[1]).intValue());
    }
    return counts;
  }

  /**
   * All cached messages of a conversation, across every folder (INBOX, SENT,
   * ARCHIVE), oldest first — the read model for the conversation reader. Bodies
   * and recipients are loaded so each message renders in full.
   */
  public List<Email> getEmailsByThreadId(String userId, String threadId, String userEmail) {
    List<EmailBoxEntity> emailBoxEntities = emailBoxDao.findByUserIdAndThreadIdWithAttachments(userId, threadId);
    return emailBoxEntities.stream()
                           .map(emailBoxEntity -> fromEntity(emailBoxEntity, true, false, userId, userEmail, true, true))
                           .toList();
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
                                                         toRecipientsString(email.getReplyTo()),
                                                         email.getReceivedDate(),
                                                         email.isRead(),
                                                         email.isRecent(),
                                                         null,
                                                         email.getThreadId(),
                                                         email.getInReplyTo(),
                                                         email.getMailReferences(),
                                                         email.getFolder() != null ? email.getFolder() : MailFolder.INBOX,
                                                         email.getThreadIndexRoot(),
                                                         email.isAutoSubmitted(),
                                                         email.isHasListId(),
                                                         email.isHasListPost(),
                                                         email.isHasListUnsubscribe(),
                                                         email.getOriginalSender());
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
                           String userEmail,
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
      }
      String[] emailSenderParts = emailBoxEntity.getSender().split(",");
      InternetAddress emailSenderAddress = new InternetAddress(emailSenderParts[1], emailSenderParts[0]);
      List<Long> categoryIds = categoryLinkService.getLinkedIds(new CategoryObject(EmailCategoryPlugin.OBJECT_TYPE,
                                                                                   String.valueOf(emailBoxEntity.getId()),
                                                                                   0));
      Email email = new Email(emailBoxEntity.getId(),
                              emailBoxEntity.getMailRemoteId(),
                              emailBoxEntity.getMailHeaderId(),
                              emailBoxEntity.getUserId(),
                              userEmail,
                              emailBoxEntity.getSubject(),
                              new EmailContent(emailBoxEntity.getBody(), excerpt, attachments),
                              emailBoxEntity.getReceivedDate(),
                              EmailConnectorUtils.getEmailSender(emailSenderAddress, withProfile),
                              emailBoxEntity.isRead(),
                              emailBoxEntity.isRecent(),
                              null,
                              null,
                              null,
                              null,
                              categoryIds,
                              null,
                              emailBoxEntity.getThreadId(),
                              emailBoxEntity.getInReplyTo(),
                              emailBoxEntity.getMailReferences(),
                              emailBoxEntity.getFolder(),
                              emailBoxEntity.getThreadIndexRoot(),
                              emailBoxEntity.isAutoSubmitted(),
                              emailBoxEntity.isHasListId(),
                              emailBoxEntity.isHasListPost(),
                              emailBoxEntity.isHasListUnsubscribe(),
                              emailBoxEntity.getOriginalSender());

      if (withRecipients) {
        InternetAddress[] emailToRecipientsInternetAddresses = toRecipientsInternetAddresses(emailBoxEntity.getTo());
        InternetAddress[] emailCcRecipientsInternetAddresses = toRecipientsInternetAddresses(emailBoxEntity.getCc());
        InternetAddress[] emailBccRecipientsInternetAddresses = toRecipientsInternetAddresses(emailBoxEntity.getBcc());
        InternetAddress[] emailReplyToRecipientsInternetAddresses = toRecipientsInternetAddresses(emailBoxEntity.getReplyTo());
        email.setTo(EmailConnectorUtils.getEmailRecipients(emailToRecipientsInternetAddresses, userId, withProfile));
        email.setCc(EmailConnectorUtils.getEmailRecipients(emailCcRecipientsInternetAddresses, userId, withProfile));
        email.setBcc(EmailConnectorUtils.getEmailRecipients(emailBccRecipientsInternetAddresses, userId, withProfile));
        email.setReplyTo(EmailConnectorUtils.getEmailRecipients(emailReplyToRecipientsInternetAddresses, userId, false));
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
