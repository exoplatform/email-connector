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

import org.apache.commons.lang3.StringUtils;
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
import org.exoplatform.emailConnector.model.EmailSender;
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

  // Largest IN list issued in one statement. Oracle refuses past 1000 literals (ORA-01795);
  // the margin leaves room for the other bound parameters of the same query.
  private static final int    IN_CLAUSE_MAX_SIZE = 900;

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

  /**
   * Clears the recent flag of all the given messages in one statement — the bulk
   * companion of {@link #markEmailAsNotRecent}, introduced when the sync stopped
   * issuing one UPDATE per already-known message. No-op on an empty list, so a
   * steady-state sync touches nothing.
   * <p>
   * Issued in slices: this list is bounded by the mailbox cache size, whose default is now
   * 1000 and which administrators may raise to 5000, and Oracle rejects an {@code IN} list of
   * more than 1000 literals (ORA-01795). A first sync of a full cache would otherwise fail the
   * whole run on the very statement added to make bulk syncs cheaper.
   *
   * @param mailRemoteIds the IMAP UIDs whose recent flag must be cleared
   * @param userId the mailbox owner
   * @param folder the folder discriminator scoping the UIDs
   */
  public void markEmailsAsNotRecent(List<Long> mailRemoteIds, String userId, String folder) {
    if (mailRemoteIds == null || mailRemoteIds.isEmpty()) {
      return;
    }
    for (int start = 0; start < mailRemoteIds.size(); start += IN_CLAUSE_MAX_SIZE) {
      int end = Math.min(start + IN_CLAUSE_MAX_SIZE, mailRemoteIds.size());
      emailBoxDao.markEmailsAsNotRecent(mailRemoteIds.subList(start, end), userId, folder);
    }
  }

  /**
   * The light view of a folder the sync reconcile runs on: each cached row's id,
   * IMAP UID, threading state and flags — no body, no attachments join, no
   * category-link lookup. The full {@link #getEmails(String, String)} load was one
   * of the two dominant costs of a sync that found nothing new: at 5000 cached
   * messages it pulled every BODY CLOB through the persistence layer and ran one
   * category-link query per row, none of which the sync ever read. Category ids
   * are resolved lazily, only for the rows actually being deleted (see the
   * service's delete path). Ordered newest-first, which cleanupObsoleteEmails
   * relies on to trim the cache overflow off the end.
   *
   * @param userId the mailbox owner
   * @param folder the folder discriminator
   * @return light {@link Email} DTOs (body, recipients and category ids left null),
   *         newest first
   */
  public List<Email> getSyncEmails(String userId, String folder) {
    return emailBoxDao.findSyncViewByUserIdAndFolder(userId, folder).stream().map(row -> {
      Email email = new Email();
      email.setId((Long) row[0]);
      email.setMailRemoteId((Long) row[1]);
      email.setThreadId((String) row[2]);
      email.setThreadIndexRoot((String) row[3]);
      email.setRead(Boolean.TRUE.equals(row[4]));
      email.setRecent(Boolean.TRUE.equals(row[5]));
      email.setUserId(userId);
      email.setFolder(folder);
      return email;
    }).toList();
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
   * The distinct thread ids of already-cached messages that point back AT the given
   * message — its Message-ID appears in their {@code References} / {@code In-Reply-To}
   * — the reverse of {@link #getSiblingThreadIds}. Without this direction a reply
   * cached before its parent is invisible to the parent, and the conversation
   * silently splits in two; with both directions, thread grouping no longer depends
   * on the order messages are cached in.
   * <p>
   * The matching runs HERE, in Java, over the chains the DAO returns — not as a SQL
   * substring function. The References column is CLOB on some dialects (HSQLDB),
   * where {@code LOCATE} throws {@code SQLFeatureNotSupportedException}; the first
   * live reset with a SQL-side match aborted the sync and cached nothing. Matching in
   * Java is dialect-proof, and cheap: the candidate set is bounded by the per-user
   * cache cap, hundreds of short strings against a sync budget that is pure IMAP
   * latency. The id is normalized to its angle-bracketed RFC 5322 form before
   * matching, because the brackets are what makes the containment check token-exact:
   * {@code <a@host>} matches neither {@code <xa@host>} nor {@code <a@host.com>},
   * while a bare {@code a@host} would match both.
   *
   * @param userId the mailbox owner
   * @param messageId the message's own Message-ID, with or without angle brackets
   * @return the distinct thread ids of the cached messages referencing it, never null
   */
  public List<String> getThreadIdsReferencingMessageId(String userId, String messageId) {
    if (StringUtils.isBlank(messageId)) {
      return List.of();
    }
    String bracketedId = messageId.startsWith("<") && messageId.endsWith(">") ? messageId : "<" + messageId + ">";
    return emailBoxDao.findThreadReferenceChainsByUserId(userId)
                      .stream()
                      .filter(chain -> chainContains((String) chain[1], bracketedId)
                          || chainContains((String) chain[2], bracketedId))
                      .map(chain -> (String) chain[0])
                      .distinct()
                      .toList();
  }

  /**
   * Whether a raw {@code References} / {@code In-Reply-To} header value contains the
   * given angle-bracketed Message-ID. A plain containment check is exact here: ids
   * cannot contain {@code <} or {@code >} internally, so the brackets delimit the
   * token on both sides.
   *
   * @param chain the raw header value, may be null
   * @param bracketedId the angle-bracketed Message-ID to look for
   * @return true when the chain references the id
   */
  private boolean chainContains(String chain, String bracketedId) {
    return chain != null && chain.contains(bracketedId);
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

  /**
   * Of the given IMAP UIDs, the ones already cached in a folder — the bulk
   * lookup behind the search results' {@code cached} flag: one IN query for the
   * whole hit list, never a per-hit statement. No-op on an empty list.
   *
   * @param userId the mailbox owner
   * @param folder the folder discriminator scoping the UIDs
   * @param mailRemoteIds the candidate IMAP UIDs
   * @return the subset of {@code mailRemoteIds} present in the local cache,
   *         never null
   */
  public List<Long> getCachedMailRemoteIds(String userId, String folder, List<Long> mailRemoteIds) {
    if (mailRemoteIds == null || mailRemoteIds.isEmpty()) {
      return List.of();
    }
    return emailBoxDao.findCachedMailRemoteIds(userId, folder, mailRemoteIds);
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

  public long countUnreadEmails(String userId) {
    // The inbox is the only folder the eXo client can mark read, so it is the
    // only one whose unread count a user can ever bring back to zero
    return emailBoxDao.countUnreadByUserIdAndFolder(userId, MailFolder.INBOX);
  }

  public void deleteEmailsByIds(List<Long> emailsIds) {
    emailBoxDao.deleteEmailsByIds(emailsIds);
  }

  /**
   * The light view contact collection reads: for each cached message of a
   * folder, its sender, To/Cc recipients, the distribution headers and the
   * received date — mapped into partial {@link Email} DTOs (body, attachments
   * and categories left null) so the collection rules run on the same shapes
   * {@link EmailConnectorUtils#getMailType} judges. No profile resolution: the
   * store joins the directory at read time, never at collection time.
   *
   * @param userId the mailbox owner
   * @param folder the folder discriminator
   * @param mailRemoteIds the IMAP UIDs to restrict to, or null for the whole
   *          folder (the backfill pass)
   * @return light {@link Email} DTOs, never null
   */
  public List<Email> getContactSourceEmails(String userId, String folder, List<Long> mailRemoteIds) {
    List<Object[]> rows = mailRemoteIds == null ? emailBoxDao.findContactSourceRowsByUserIdAndFolder(userId, folder)
                                                : mailRemoteIds.isEmpty() ? List.of()
                                                                          : emailBoxDao.findContactSourceRowsByUserIdAndFolderAndUids(userId,
                                                                                                                                      folder,
                                                                                                                                      mailRemoteIds);
    return rows.stream().map(row -> {
      Email email = new Email();
      email.setUserId(userId);
      email.setFolder(folder);
      email.setSender(toLightSender((String) row[0]));
      email.setTo(EmailConnectorUtils.getEmailRecipients(toRecipientsInternetAddresses((String) row[1]), userId, false));
      email.setCc(EmailConnectorUtils.getEmailRecipients(toRecipientsInternetAddresses((String) row[2]), userId, false));
      email.setAutoSubmitted(Boolean.TRUE.equals(row[3]));
      email.setHasListId(Boolean.TRUE.equals(row[4]));
      email.setHasListPost(Boolean.TRUE.equals(row[5]));
      email.setHasListUnsubscribe(Boolean.TRUE.equals(row[6]));
      email.setOriginalSender((String) row[7]);
      email.setReceivedDate((java.util.Date) row[8]);
      return email;
    }).toList();
  }

  /**
   * Decodes the stored {@code name,address} sender string without touching the
   * directory. Split on the LAST comma: the address cannot contain one, while a
   * display name legitimately can ("Doe, Jane") — the first-comma split the full
   * mapper inherited would hand the rules a truncated address.
   *
   * @param stored the stored sender string
   * @return the sender, or null for a blank value
   */
  private EmailSender toLightSender(String stored) {
    if (StringUtils.isBlank(stored)) {
      return null;
    }
    int lastComma = stored.lastIndexOf(',');
    if (lastComma < 0) {
      return new EmailSender(stored, stored, null, null);
    }
    String name = stored.substring(0, lastComma);
    String address = stored.substring(lastComma + 1);
    return new EmailSender(StringUtils.isBlank(name) ? address : name, address, null, null);
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
