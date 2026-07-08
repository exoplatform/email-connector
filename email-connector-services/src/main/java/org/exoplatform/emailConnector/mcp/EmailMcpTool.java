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
package org.exoplatform.emailConnector.mcp;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import org.apache.commons.lang3.StringUtils;
import org.jsoup.Jsoup;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import org.exoplatform.commons.exception.ObjectNotFoundException;
import org.exoplatform.emailConnector.mcp.model.EmailAccountModel;
import org.exoplatform.emailConnector.mcp.model.EmailModel;
import org.exoplatform.emailConnector.model.Email;
import org.exoplatform.emailConnector.model.EmailBox;
import org.exoplatform.emailConnector.model.EmailContent;
import org.exoplatform.emailConnector.model.EmailRecipient;
import org.exoplatform.emailConnector.model.EmailSender;
import org.exoplatform.emailConnector.model.SyncStatus;
import org.exoplatform.emailConnector.model.UserEmailSetting;
import org.exoplatform.emailConnector.service.EmailBoxService;
import org.exoplatform.emailConnector.service.UserEmailSettingService;

import io.meeds.mcp.server.plugin.McpToolPlugin;

/**
 * MCP tools exposing the Email Connector add-on to the AI agent (EVA). Every
 * method acts as the current user, so the caller only ever touches their own
 * email box. Read/triage tools run without approval; compose (send/reply) and
 * organize (archive/delete) tools are approval-gated: the assistant only DRAFTS
 * outward-facing actions and must never auto-send nor invent recipients.
 */
@Service
@Profile("mcp-server")
public class EmailMcpTool implements McpToolPlugin {

  private final EmailBoxService         emailBoxService;

  private final UserEmailSettingService userEmailSettingService;

  @Autowired
  public EmailMcpTool(EmailBoxService emailBoxService, UserEmailSettingService userEmailSettingService) {
    this.emailBoxService = emailBoxService;
    this.userEmailSettingService = userEmailSettingService;
  }

  // ---------------------------------------------------------------------------
  // Slice 0 / existing reads (keyed off the local database id)
  // ---------------------------------------------------------------------------

  // Retrieve one stored email by its local database id (plain-text body).
  public EmailModel getEmailById(long emailId) throws ObjectNotFoundException, IllegalAccessException {
    Email email = emailBoxService.getEmailById(emailId, getCurrentUserName());
    if (email == null) {
      throw new ObjectNotFoundException("Email with id %s not found");
    }
    return toEmailModel(email, true);
  }

  // Retrieve the current user's synced INBOX mirror as a list of emails.
  public List<EmailModel> listEmails() throws ObjectNotFoundException, IllegalAccessException {
    EmailBox emailBox = emailBoxService.getEmailBox(getCurrentUserName());
    return emailBox.getEmails().stream().map(email -> toEmailModel(email, false)).toList();
  }

  // ---------------------------------------------------------------------------
  // Slice 1 - read / triage (no approval)
  // ---------------------------------------------------------------------------

  // Return the current user's connected mailbox state (address, connector, sync
  // status, webmail url, connected flag). Never exposes the stored password.
  public EmailAccountModel getMyEmailAccount() {
    UserEmailSetting setting = userEmailSettingService.getUserEmailSetting(getCurrentUserName());
    if (setting == null || setting.getEmailConnectorId() == null) {
      throw new IllegalStateException("No email account is connected for the current user");
    }
    String syncStatus = setting.getEmailSyncStatus() != null ? setting.getEmailSyncStatus().name() : null;
    return new EmailAccountModel(setting.getEmailAddress(),
                                 setting.getEmailConnectorName(),
                                 setting.getEmailConnectorWebmailUrl(),
                                 syncStatus,
                                 setting.isConnected());
  }

  // Pull fresh messages from the IMAP INBOX before triage, then report the
  // resulting sync status and the number of emails now in the local mirror.
  public String syncNow() throws IllegalAccessException {
    String username = getCurrentUserName();
    emailBoxService.synchronize(username);
    EmailBox emailBox = emailBoxService.getEmailBox(username);
    SyncStatus status = emailBox.getEmailSyncStatus();
    return String.format("Synchronization finished with status %s. %d email(s) available in the inbox mirror.",
                         status != null ? status.name() : "UNKNOWN",
                         emailBox.getEmails() != null ? emailBox.getEmails().size() : 0);
  }

  // Filter the synced INBOX mirror IN MEMORY (there is no server-side search):
  // free-text query over subject/sender/body, an unread-only flag, and a sender
  // address/name filter. Only covers already-synced emails, not the whole server.
  public List<EmailModel> searchEmails(String query, Boolean unread, String from) throws IllegalAccessException {
    EmailBox emailBox = emailBoxService.getEmailBox(getCurrentUserName());
    String normalizedQuery = query == null ? null : query.toLowerCase().trim();
    String normalizedFrom = from == null ? null : from.toLowerCase().trim();
    return emailBox.getEmails().stream().filter(email -> {
      if (Boolean.TRUE.equals(unread) && email.isRead()) {
        return false;
      }
      if (StringUtils.isNotBlank(normalizedFrom) && !matchesSender(email.getSender(), normalizedFrom)) {
        return false;
      }
      if (StringUtils.isNotBlank(normalizedQuery) && !matchesQuery(email, normalizedQuery)) {
        return false;
      }
      return true;
    }).map(email -> toEmailModel(email, false)).toList();
  }

  // Fetch a single email in full by its IMAP mailRemoteId, including recipients,
  // content and attachment metadata (plain-text body).
  public EmailModel getEmailFull(long mailRemoteId) throws ObjectNotFoundException, IllegalAccessException {
    Email email = emailBoxService.getEmailByMailRemoteIdAndUserId(mailRemoteId, getCurrentUserName(), true, true, true, false);
    if (email == null) {
      throw new ObjectNotFoundException("Email with mail_remote_id %s not found");
    }
    return toEmailModel(email, true);
  }

  // Mark one or more emails (by IMAP mailRemoteId) as read, locally and on the
  // IMAP server.
  public String markRead(List<Long> mailRemoteIds) throws IllegalAccessException {
    emailBoxService.updateEmailReadStatus(mailRemoteIds, getCurrentUserName(), true, true);
    return String.format("Marked %d email(s) as read.", mailRemoteIds == null ? 0 : mailRemoteIds.size());
  }

  // Mark one or more emails (by IMAP mailRemoteId) as unread, locally and on the
  // IMAP server.
  public String markUnread(List<Long> mailRemoteIds) throws IllegalAccessException {
    emailBoxService.updateEmailReadStatus(mailRemoteIds, getCurrentUserName(), false, true);
    return String.format("Marked %d email(s) as unread.", mailRemoteIds == null ? 0 : mailRemoteIds.size());
  }

  // ---------------------------------------------------------------------------
  // Slice 2 - compose (approval-gated, outward facing, irreversible)
  // ---------------------------------------------------------------------------

  // Send a brand new email over real SMTP (also copied to the Sent folder).
  // Body is HTML. Attachments are NOT supported by the backing service.
  public String sendEmail(List<String> to, String subject, String bodyHtml, List<String> cc) throws IllegalAccessException {
    if (to == null || to.stream().filter(StringUtils::isNotBlank).findAny().isEmpty()) {
      throw new IllegalArgumentException("At least one recipient is required in 'to'");
    }
    Email email = new Email();
    email.setSubject(subject);
    email.setContent(buildHtmlContent(bodyHtml));
    email.setTo(toRecipients(to));
    email.setCc(toRecipients(cc));
    emailBoxService.sendEmail(email, getCurrentUserName());
    return String.format("Email sent to %s with subject \"%s\".", String.join(", ", to), subject);
  }

  // Reply to the sender of an existing email (by IMAP mailRemoteId). Threads the
  // reply by copying the original Message-ID into In-Reply-To/References.
  public String replyEmail(long mailRemoteId, String bodyHtml) throws IllegalAccessException {
    String username = getCurrentUserName();
    Email original = fetchOriginalOrFail(mailRemoteId, username);
    Email reply = buildReplyShell(original, bodyHtml);
    reply.setTo(senderAsRecipients(original));
    emailBoxService.sendEmail(reply, username);
    return String.format("Reply sent to %s.", senderAddress(original));
  }

  // Reply to everyone on an existing email (by IMAP mailRemoteId): To = original
  // sender, Cc = original To + Cc minus the current user's own address.
  public String replyAll(long mailRemoteId, String bodyHtml) throws IllegalAccessException {
    String username = getCurrentUserName();
    Email original = fetchOriginalOrFail(mailRemoteId, username);
    Email reply = buildReplyShell(original, bodyHtml);
    reply.setTo(senderAsRecipients(original));
    String selfAddress = userEmailSettingService.getUserEmailSetting(username).getEmailAddress();
    List<EmailRecipient> ccRecipients = new ArrayList<>();
    addRecipientsExcluding(ccRecipients, original.getTo(), selfAddress);
    addRecipientsExcluding(ccRecipients, original.getCc(), selfAddress);
    reply.setCc(ccRecipients);
    emailBoxService.sendEmail(reply, username);
    return String.format("Reply-all sent to %s.", senderAddress(original));
  }

  // ---------------------------------------------------------------------------
  // Slice 3 - organize (approval-gated)
  // ---------------------------------------------------------------------------

  // Move one or more emails (by IMAP mailRemoteId) to the Archive folder.
  public String archiveEmail(List<Long> mailRemoteIds) throws IllegalAccessException {
    int failed = emailBoxService.archiveEmail(mailRemoteIds, getCurrentUserName());
    int total = mailRemoteIds == null ? 0 : mailRemoteIds.size();
    return String.format("Archived %d of %d email(s)%s.", total - failed, total, failed > 0 ? " (" + failed + " failed)" : "");
  }

  // Delete one or more emails (by IMAP mailRemoteId): copies them to Trash then
  // expunges them from the INBOX. Destructive and irreversible.
  public String deleteEmail(List<Long> mailRemoteIds) throws IllegalAccessException {
    int failed = emailBoxService.deleteEmail(mailRemoteIds, getCurrentUserName());
    int total = mailRemoteIds == null ? 0 : mailRemoteIds.size();
    return String.format("Deleted %d of %d email(s)%s.", total - failed, total, failed > 0 ? " (" + failed + " failed)" : "");
  }

  // ---------------------------------------------------------------------------
  // Helpers
  // ---------------------------------------------------------------------------

  // Map an Email domain object to the MCP EmailModel, flattening the HTML body to
  // plain text and surfacing the mailRemoteId needed to chain write tools.
  private EmailModel toEmailModel(Email email, boolean includeUserEmail) {
    EmailContent content = email.getContent();
    if (content != null && content.getBody() != null) {
      content.setBody(Jsoup.parse(content.getBody()).text().trim());
    }
    EmailModel model = new EmailModel();
    model.setId(email.getId());
    model.setMailRemoteId(email.getMailRemoteId());
    model.setUserId(email.getUserId());
    model.setUserEmail(includeUserEmail ? email.getUserEmail() : null);
    model.setSubject(email.getSubject());
    model.setContent(content);
    model.setReceivedDate(email.getReceivedDate());
    model.setSender(email.getSender());
    model.setRead(email.isRead());
    model.setRecent(email.isRecent());
    model.setTo(email.getTo());
    model.setCc(email.getCc());
    model.setBcc(email.getBcc());
    return model;
  }

  // Case-insensitive match of a free-text term against subject, sender and body.
  private boolean matchesQuery(Email email, String query) {
    if (StringUtils.containsIgnoreCase(email.getSubject(), query)) {
      return true;
    }
    if (matchesSender(email.getSender(), query)) {
      return true;
    }
    return email.getContent() != null && StringUtils.containsIgnoreCase(email.getContent().getBody(), query);
  }

  // Case-insensitive match of a term against a sender's name and address.
  private boolean matchesSender(EmailSender sender, String term) {
    return sender != null
        && (StringUtils.containsIgnoreCase(sender.getAddress(), term)
            || StringUtils.containsIgnoreCase(sender.getName(), term));
  }

  // Build an HTML EmailContent body for an outgoing message.
  private EmailContent buildHtmlContent(String bodyHtml) {
    EmailContent content = new EmailContent();
    content.setBody(bodyHtml == null ? "" : bodyHtml);
    content.setHtml(true);
    return content;
  }

  // Turn a list of raw email addresses into EmailRecipient objects (address only).
  private List<EmailRecipient> toRecipients(List<String> addresses) {
    if (addresses == null) {
      return List.of();
    }
    return addresses.stream()
                    .filter(StringUtils::isNotBlank)
                    .map(address -> new EmailRecipient(null, address.trim(), null, false))
                    .toList();
  }

  // Fetch the original email (with recipients) or fail if it cannot be found.
  private Email fetchOriginalOrFail(long mailRemoteId, String username) throws IllegalAccessException {
    Email original = emailBoxService.getEmailByMailRemoteIdAndUserId(mailRemoteId, username, false, true, false, false);
    if (original == null) {
      throw new IllegalArgumentException("Original email with mail_remote_id " + mailRemoteId + " not found");
    }
    return original;
  }

  // Build the shared shell of a reply: HTML body, "Re:" subject, and the original
  // Message-ID copied into mailHeaderId so the service sets In-Reply-To/References.
  private Email buildReplyShell(Email original, String bodyHtml) {
    Email reply = new Email();
    reply.setContent(buildHtmlContent(bodyHtml));
    reply.setMailHeaderId(original.getMailHeaderId());
    String subject = original.getSubject() == null ? "" : original.getSubject();
    reply.setSubject(StringUtils.startsWithIgnoreCase(subject, "Re:") ? subject : "Re: " + subject);
    return reply;
  }

  // Wrap the original sender as the single recipient of a reply.
  private List<EmailRecipient> senderAsRecipients(Email original) {
    String address = senderAddress(original);
    if (StringUtils.isBlank(address)) {
      throw new IllegalStateException("Original email has no sender address to reply to");
    }
    EmailSender sender = original.getSender();
    return List.of(new EmailRecipient(sender != null ? sender.getName() : null, address, null, false));
  }

  // Extract the sender email address from the original email.
  private String senderAddress(Email original) {
    return original.getSender() != null ? original.getSender().getAddress() : null;
  }

  // Append recipients to the target list, skipping blanks and the excluded (self)
  // address so the current user is never CC'd back on their own reply-all.
  private void addRecipientsExcluding(List<EmailRecipient> target, List<EmailRecipient> source, String excludedAddress) {
    if (source == null) {
      return;
    }
    source.stream()
          .filter(Objects::nonNull)
          .filter(recipient -> StringUtils.isNotBlank(recipient.getAddress()))
          .filter(recipient -> !recipient.getAddress().equalsIgnoreCase(excludedAddress))
          .forEach(target::add);
  }
}
