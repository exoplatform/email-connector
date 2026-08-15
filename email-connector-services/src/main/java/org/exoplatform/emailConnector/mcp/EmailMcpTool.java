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

import static io.meeds.mcp.server.tool.util.McpToolPluginUtils.getInteger;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.apache.commons.lang3.StringUtils;
import org.jsoup.Jsoup;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import org.exoplatform.commons.exception.ObjectNotFoundException;
import org.exoplatform.emailConnector.mcp.model.EmailAccountModel;
import org.exoplatform.emailConnector.mcp.model.EmailAttachmentModel;
import org.exoplatform.emailConnector.mcp.model.EmailModel;
import org.exoplatform.emailConnector.mcp.model.EmailSearchHitModel;
import org.exoplatform.emailConnector.mcp.model.EmailSearchResultsModel;
import org.exoplatform.emailConnector.mcp.model.EmailThreadMessageModel;
import org.exoplatform.emailConnector.model.Email;
import org.exoplatform.emailConnector.model.EmailAttachment;
import org.exoplatform.emailConnector.model.EmailBox;
import org.exoplatform.emailConnector.model.EmailCategory;
import org.exoplatform.emailConnector.model.EmailContent;
import org.exoplatform.emailConnector.model.EmailRecipient;
import org.exoplatform.emailConnector.model.EmailSearchResultPage;
import org.exoplatform.emailConnector.model.EmailSender;
import org.exoplatform.emailConnector.model.MailFolder;
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

  /** Hits returned when the caller names no limit: a readable page, not a dump. */
  private static final int              DEFAULT_SEARCH_LIMIT = 20;

  /**
   * How many of a conversation's messages are returned — the most recent ones. A long
   * thread is mostly its latest turns, and the older ones are usually quoted inside
   * them; twenty-five is where a conversation stops being a conversation and starts
   * being an archive.
   */
  private static final int              THREAD_MAX_MESSAGES  = 25;

  /**
   * How much of each message's body is returned. Mail bodies are largely quoted
   * history and signatures — the same sentences repeated once per message, growing
   * with the thread — so the whole of twenty-five of them is mostly the same text
   * twenty-five times.
   */
  private static final int              THREAD_BODY_MAX_CHARS = 1500;

  /** What a cut body ends with, so nobody mistakes half a message for all of it. */
  private static final String           TRUNCATION_MARKER    = "… [truncated]";

  /**
   * The service reports a refused search with a message code, the right currency
   * for REST and useless to a model. These are the same refusals in words a model
   * can act on, since it is the one that has to correct the call.
   */
  private static final Map<String, String> SEARCH_MESSAGES   =
                                                            Map.of("emailConnector.search.criteriaRequired",
                                                                   "Give at least one of query, from, unread or sinceDays: an empty search would return the whole folder.",
                                                                   "emailConnector.folder.notBrowsable",
                                                                   "folder must be one of INBOX, SENT or ARCHIVE.");

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

  /**
   * Retrieve one stored email by its local database id (plain-text body).
   * <p>
   * Reads through {@link EmailBoxService#getOwnedEmailById}, not the plain lookup:
   * that one finds a row by its technical id alone and lets the username merely
   * decorate what comes back, which is right for a caller that has already
   * established who owns the row and wrong for anything reached from outside. This
   * is reached from outside -- an agent hands it an id -- and an id is guessable, so
   * it takes the same read {@code EmailBoxRest} takes. It refuses another user's
   * mail rather than returning it.
   *
   * @param emailId the cached email's local database id
   * @return the email, with its body flattened to plain text
   * @throws ObjectNotFoundException if no such email is cached
   * @throws IllegalAccessException if the email belongs to somebody else
   */
  public EmailModel getEmailById(long emailId) throws ObjectNotFoundException, IllegalAccessException {
    Email email = emailBoxService.getOwnedEmailById(emailId, getCurrentUserName());
    if (email == null) {
      throw new ObjectNotFoundException("Email with id %s not found");
    }
    return toEmailModel(email, true);
  }

  /**
   * Retrieve a page of the current user's synced INBOX mirror. Supports paging
   * (offset/limit, defaulting to the first 10) and an unread-only filter so the
   * agent triages incrementally instead of pulling the whole mirror at once.
   */
  public List<EmailModel> listEmails(Integer offset, Integer limit, Boolean unreadOnly) throws ObjectNotFoundException,
                                                                                        IllegalAccessException {
    EmailBox emailBox = emailBoxService.getEmailBox(getCurrentUserName());
    return emailBox.getEmails()
                   .stream()
                   .filter(email -> !Boolean.TRUE.equals(unreadOnly) || !email.isRead())
                   .skip(getInteger(offset, DEFAULT_OFFSET))
                   .limit(getInteger(limit, DEFAULT_LIMIT))
                   .map(email -> toEmailModel(email, false))
                   .toList();
  }

  /**
   * Report how many emails in the synced INBOX mirror are unread, out of the total
   * mirrored. Fast triage summary that never returns bodies.
   */
  public String getUnreadCount() throws IllegalAccessException {
    EmailBox emailBox = emailBoxService.getEmailBox(getCurrentUserName());
    List<Email> emails = emailBox.getEmails();
    long total = emails == null ? 0 : emails.size();
    long unread = emails == null ? 0 : emails.stream().filter(email -> !email.isRead()).count();
    return String.format("%d unread email(s) out of %d in the inbox mirror.", unread, total);
  }

  // ---------------------------------------------------------------------------
  // Slice 1 - read / triage (no approval)
  // ---------------------------------------------------------------------------

  /**
   * Return the current user's connected mailbox state (address, connector, sync
   * status, webmail url, connected flag). Never exposes the stored password.
   */
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

  /**
   * Pull fresh messages from the IMAP INBOX before triage, then report the
   * resulting sync status and the number of emails now in the local mirror.
   */
  public String syncNow() throws IllegalAccessException {
    String username = getCurrentUserName();
    emailBoxService.synchronize(username);
    EmailBox emailBox = emailBoxService.getEmailBox(username);
    SyncStatus status = emailBox.getEmailSyncStatus();
    return String.format("Synchronization finished with status %s. %d email(s) available in the inbox mirror.",
                         status != null ? status.name() : "UNKNOWN",
                         emailBox.getEmails() != null ? emailBox.getEmails().size() : 0);
  }

  /**
   * Search the mail server itself (IMAP SEARCH over the whole folder), not the
   * locally synced mirror, so a message from months ago is found even though the
   * add-on only caches a recent window.
   * <p>
   * Free text matches the subject or the sender, and the other filters narrow by
   * sender, unread state and age. The newest matches come back with the total the
   * server found, so the caller can tell how much of the answer it is holding.
   *
   * @param query free text matched against the subject or the sender, may be blank
   * @param from text matched against the sender only, may be blank
   * @param unread when {@code true}, only unread messages match
   * @param sinceDays only messages received in the last N days match, null for the
   *          whole history
   * @param folder INBOX (default), SENT or ARCHIVE
   * @param limit how many hits to return, newest first
   * @return the newest matching messages and the total number that matched
   * @throws IllegalAccessException if the user has no usable mailbox
   */
  public EmailSearchResultsModel searchEmails(String query,
                                              String from,
                                              Boolean unread,
                                              Integer sinceDays,
                                              String folder,
                                              Integer limit) throws IllegalAccessException {
    try {
      EmailSearchResultPage page = emailBoxService.searchEmails(getCurrentUserName(),
                                                                query,
                                                                from,
                                                                Boolean.TRUE.equals(unread),
                                                                sinceDays,
                                                                StringUtils.isBlank(folder) ? MailFolder.INBOX
                                                                                            : folder.trim().toUpperCase(),
                                                                limit == null ? DEFAULT_SEARCH_LIMIT : limit);
      List<EmailSearchHitModel> hits = page.getResults()
                                           .stream()
                                           .map(result -> new EmailSearchHitModel(result.getMailRemoteId(),
                                                                                  result.getFolder(),
                                                                                  result.getSubject(),
                                                                                  result.getSender(),
                                                                                  result.getReceivedDate(),
                                                                                  result.isRead(),
                                                                                  result.isStarred(),
                                                                                  result.isCached()))
                                           .toList();
      return new EmailSearchResultsModel(page.getTotalMatches(), hits);
    } catch (IllegalArgumentException e) {
      // The service answers with a message code, which is the right currency for
      // the REST layer and useless to a model. Say the same thing in words it can
      // act on -- it is the one who has to correct the call.
      throw new IllegalArgumentException(SEARCH_MESSAGES.getOrDefault(e.getMessage(), e.getMessage()));
    } catch (IllegalStateException e) {
      throw new IllegalStateException("The mailbox is synchronizing right now, so it cannot be searched. Try again in a moment.");
    }
  }

  /**
   * Fetch a single email in full by its IMAP mailRemoteId, including recipients,
   * content and attachment metadata (plain-text body).
   */
  public EmailModel getEmailFull(long mailRemoteId) throws ObjectNotFoundException, IllegalAccessException {
    Email email = emailBoxService.getEmailByMailRemoteIdAndUserId(mailRemoteId, getCurrentUserName(), MailFolder.INBOX, true, true, true, false);
    if (email == null) {
      throw new ObjectNotFoundException("Email with mail_remote_id %s not found");
    }
    return toEmailModel(email, true);
  }

  /**
   * Read a whole conversation at once, oldest message first, so it can be summarised
   * or answered with the history in hand rather than one message at a time.
   * <p>
   * Three things are deliberately left out, and each of them is the difference between
   * a usable answer and an unusable one:
   * <ul>
   * <li>DRAFTS. A conversation can hold a reply the user is still writing, and
   * describing somebody's half-finished sentence back to them is worse than not
   * mentioning it. It is also unstable: the row changes every time they type.</li>
   * <li>Everything but the most recent {@link #THREAD_MAX_MESSAGES} messages. A long
   * thread is mostly its recent turns; the older ones are usually quoted inside them
   * anyway.</li>
   * <li>Most of each body. HTML is flattened to text and cut at
   * {@link #THREAD_BODY_MAX_CHARS} characters, because mail bodies are largely quoted
   * history and signatures — the same sentences, once per message, growing with the
   * thread.</li>
   * </ul>
   * The truncation is marked in the text rather than silent: a reader that cannot see
   * where a message stopped will summarise the missing half with the same confidence
   * as the rest.
   *
   * @param threadId the conversation id, as carried by every listed or fetched email
   * @return the conversation's real messages, oldest first
   * @throws IllegalAccessException if the user has no usable mailbox
   */
  public List<EmailThreadMessageModel> getEmailThread(String threadId) throws IllegalAccessException {
    if (StringUtils.isBlank(threadId)) {
      throw new IllegalArgumentException("thread_id is required: it is carried by every email returned by the other tools.");
    }
    List<Email> thread = emailBoxService.getThread(threadId, getCurrentUserName());
    List<Email> messages = thread.stream().filter(email -> StringUtils.isBlank(email.getDraftLocalId())).toList();
    // The most recent ones, and still oldest-first once kept: a conversation read
    // backwards is a conversation nobody can follow.
    if (messages.size() > THREAD_MAX_MESSAGES) {
      messages = messages.subList(messages.size() - THREAD_MAX_MESSAGES, messages.size());
    }
    return messages.stream().map(this::toThreadMessageModel).toList();
  }

  /**
   * List the metadata of a given email's attachments (by IMAP mailRemoteId):
   * name, mime type, MIME part path and a ready authenticated download URL served
   * by EmailBoxRest. No attachment bytes ever pass through the tool - the user
   * opens the URL in their own authenticated browser. Empty list if none.
   */
  public List<EmailAttachmentModel> listAttachments(long mailRemoteId) throws IllegalAccessException {
    Email email = emailBoxService.getEmailByMailRemoteIdAndUserId(mailRemoteId, getCurrentUserName(), MailFolder.INBOX, true, false, false, false);
    if (email == null || email.getContent() == null || email.getContent().getAttachments() == null) {
      return List.of();
    }
    return email.getContent()
                .getAttachments()
                .stream()
                .map(attachment -> new EmailAttachmentModel(attachment.getName(),
                                                            attachment.getMimeType(),
                                                            attachment.getAttachmentRemoteId(),
                                                            buildAttachmentDownloadUrl(mailRemoteId,
                                                                                       attachment.getAttachmentRemoteId())))
                .toList();
  }

  /**
   * Mark one or more emails (by IMAP mailRemoteId) as read, locally and on the
   * IMAP server. Inbox messages only — see {@link #archiveEmail}. Reports the true
   * outcome: emails whose server flag could not be
   * written (message not found on server or IMAP write denied) are counted as
   * failed rather than reported as success.
   */
  public String markRead(List<Long> mailRemoteIds) throws IllegalAccessException {
    int total = mailRemoteIds == null ? 0 : mailRemoteIds.size();
    int failed = emailBoxService.updateEmailReadStatus(mailRemoteIds, getCurrentUserName(), MailFolder.INBOX, true, true);
    return buildReadStatusMessage(total, failed, "read");
  }

  /**
   * Mark one or more emails (by IMAP mailRemoteId) as unread, locally and on the
   * IMAP server. Inbox messages only — see {@link #archiveEmail}. Reports the true
   * outcome: emails whose server flag could not be
   * written (message not found on server or IMAP write denied) are counted as
   * failed rather than reported as success.
   */
  public String markUnread(List<Long> mailRemoteIds) throws IllegalAccessException {
    int total = mailRemoteIds == null ? 0 : mailRemoteIds.size();
    int failed = emailBoxService.updateEmailReadStatus(mailRemoteIds, getCurrentUserName(), MailFolder.INBOX, false, true);
    return buildReadStatusMessage(total, failed, "unread");
  }

  // ---------------------------------------------------------------------------
  // Slice 2 - compose (approval-gated, outward facing, irreversible)
  // ---------------------------------------------------------------------------

  /**
   * Send a brand new email over real SMTP (also copied to the Sent folder).
   * Body is HTML. Optional cc/bcc recipients. Attachments are NOT supported by the
   * backing service.
   */
  public String sendEmail(List<String> to,
                          String subject,
                          String bodyHtml,
                          List<String> cc,
                          List<String> bcc) throws IllegalAccessException {
    if (to == null || to.stream().filter(StringUtils::isNotBlank).findAny().isEmpty()) {
      throw new IllegalArgumentException("At least one recipient is required in 'to'");
    }
    Email email = new Email();
    email.setSubject(subject);
    email.setContent(buildHtmlContent(bodyHtml));
    email.setTo(toRecipients(to));
    email.setCc(toRecipients(cc));
    email.setBcc(toRecipients(bcc));
    emailBoxService.sendEmail(email, getCurrentUserName());
    return String.format("Email sent to %s with subject \"%s\".", String.join(", ", to), subject);
  }

  /**
   * Reply to the sender of an existing email (by IMAP mailRemoteId). Threads the
   * reply by copying the original Message-ID into In-Reply-To/References.
   */
  public String replyEmail(long mailRemoteId, String bodyHtml) throws IllegalAccessException {
    String username = getCurrentUserName();
    Email original = fetchOriginalOrFail(mailRemoteId, username);
    Email reply = buildReplyShell(original, bodyHtml);
    reply.setTo(senderAsRecipients(original));
    emailBoxService.sendEmail(reply, username);
    return String.format("Reply sent to %s.", senderAddress(original));
  }

  /**
   * Reply to everyone on an existing email (by IMAP mailRemoteId): To = original
   * sender, Cc = original To + Cc minus the current user's own address.
   */
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

  /**
   * Forward an existing email (by IMAP mailRemoteId) to brand new recipients. The
   * subject is prefixed with "Fwd:" and the original message is quoted below the
   * optional new note. Attachments are NOT carried over (the backing service cannot
   * attach files).
   */
  public String forwardEmail(long mailRemoteId,
                             List<String> to,
                             String bodyHtml,
                             List<String> cc) throws ObjectNotFoundException, IllegalAccessException {
    if (to == null || to.stream().filter(StringUtils::isNotBlank).findAny().isEmpty()) {
      throw new IllegalArgumentException("At least one recipient is required in 'to'");
    }
    String username = getCurrentUserName();
    Email original = emailBoxService.getEmailByMailRemoteIdAndUserId(mailRemoteId, username, MailFolder.INBOX, false, true, false, false);
    if (original == null) {
      throw new ObjectNotFoundException("Email with mail_remote_id %s not found");
    }
    Email forward = new Email();
    String subject = original.getSubject() == null ? "" : original.getSubject();
    forward.setSubject(StringUtils.startsWithIgnoreCase(subject, "Fwd:") ? subject : "Fwd: " + subject);
    forward.setContent(buildHtmlContent(buildForwardBody(original, bodyHtml)));
    forward.setTo(toRecipients(to));
    forward.setCc(toRecipients(cc));
    emailBoxService.sendEmail(forward, username);
    return String.format("Email forwarded to %s with subject \"%s\".", String.join(", ", to), forward.getSubject());
  }

  // ---------------------------------------------------------------------------
  // Slice 3 - organize (approval-gated)
  // ---------------------------------------------------------------------------

  /**
   * Move one or more emails (by IMAP mailRemoteId) to the Archive folder.
   * <p>
   * Inbox messages only: the ids this toolset hands out are INBOX UIDs, and a UID
   * numbers a message within one folder. Passing the folder explicitly is what keeps
   * that a stated limit rather than a silent assumption (EXO-89367).
   */
  public String archiveEmail(List<Long> mailRemoteIds) throws IllegalAccessException {
    int failed = emailBoxService.archiveEmail(mailRemoteIds, getCurrentUserName(), MailFolder.INBOX);
    int total = mailRemoteIds == null ? 0 : mailRemoteIds.size();
    return String.format("Archived %d of %d email(s)%s.", total - failed, total, failed > 0 ? " (" + failed + " failed)" : "");
  }

  /**
   * Delete one or more emails (by IMAP mailRemoteId): copies them to Trash then
   * expunges them from the INBOX. Destructive and irreversible.
   */
  public String deleteEmail(List<Long> mailRemoteIds) throws IllegalAccessException {
    int failed = emailBoxService.deleteEmail(mailRemoteIds, getCurrentUserName(), MailFolder.INBOX);
    int total = mailRemoteIds == null ? 0 : mailRemoteIds.size();
    return String.format("Deleted %d of %d email(s)%s.", total - failed, total, failed > 0 ? " (" + failed + " failed)" : "");
  }

  // ---------------------------------------------------------------------------
  // Slice 4 - categories / labels (approval-gated for writes)
  // ---------------------------------------------------------------------------

  /**
   * List the categories (labels) currently applied to the user's emails, each with
   * its id and display name. Use it to resolve a category name to the id needed by
   * add_email_category / remove_email_category. Categories not yet used on any email
   * are not listed - create them from the mailbox UI first.
   */
  public List<EmailCategory> listEmailCategories() throws IllegalAccessException {
    return emailBoxService.getEmailCategories(getCurrentUserName(), getCurrentUserLocale());
  }

  /**
   * Tag one or more emails (by IMAP mailRemoteId) with an existing category id
   * (from list_email_categories). Emails already in the category are skipped.
   */
  public String addEmailCategory(List<Long> mailRemoteIds, long categoryId) throws IllegalAccessException {
    int linked = emailBoxService.linkEmailsToCategory(mailRemoteIds, categoryId, getCurrentUserName());
    return String.format("Added category %d to %d email(s).", categoryId, linked);
  }

  /**
   * Remove a category id from one or more emails (by IMAP mailRemoteId). Emails not
   * currently in the category are skipped.
   */
  public String removeEmailCategory(List<Long> mailRemoteIds, long categoryId) throws IllegalAccessException {
    int unlinked = emailBoxService.unlinkEmailsFromCategory(mailRemoteIds, categoryId, getCurrentUserName());
    return String.format("Removed category %d from %d email(s).", categoryId, unlinked);
  }

  // ---------------------------------------------------------------------------
  // Helpers
  // ---------------------------------------------------------------------------

  /**
   * Map an Email domain object to the MCP EmailModel, flattening the HTML body to
   * plain text and surfacing the mailRemoteId needed to chain write tools.
   */
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

  /**
   * Map one message of a conversation to what a reader of the whole conversation
   * needs: who wrote it, when, about what, what it said, and what came with it.
   *
   * @param email the cached message
   * @return its conversation-reading shape
   */
  private EmailThreadMessageModel toThreadMessageModel(Email email) {
    EmailSender sender = email.getSender();
    return new EmailThreadMessageModel(sender == null ? null : sender.getName(),
                                       sender == null ? null : sender.getAddress(),
                                       email.getReceivedDate(),
                                       email.getSubject(),
                                       plainTextBody(email),
                                       attachmentNames(email));
  }

  /**
   * A message's body as readable text, cut to length.
   * <p>
   * Flattened out of HTML rather than sent as it is stored, for the same reason
   * {@link #toEmailModel} does it: markup is most of a mail body's size and none of
   * its meaning. Cutting comes after flattening, so the limit counts words rather
   * than tags — a 1500-character budget spent on a style attribute would return a
   * message that says nothing.
   *
   * @param email the cached message
   * @return its body as plain text, truncated and marked as such when it was cut
   */
  private String plainTextBody(Email email) {
    EmailContent content = email.getContent();
    if (content == null || content.getBody() == null) {
      return null;
    }
    String text = Jsoup.parse(content.getBody()).text().trim();
    if (text.length() <= THREAD_BODY_MAX_CHARS) {
      return text;
    }
    return text.substring(0, THREAD_BODY_MAX_CHARS) + TRUNCATION_MARKER;
  }

  /**
   * What was attached to a message, by name.
   *
   * @param email the cached message
   * @return the attachment names, empty when there were none
   */
  private List<String> attachmentNames(Email email) {
    EmailContent content = email.getContent();
    if (content == null || content.getAttachments() == null) {
      return List.of();
    }
    return content.getAttachments().stream().map(EmailAttachment::getName).filter(StringUtils::isNotBlank).toList();
  }

  /**
   * Build a truthful mark-read/unread outcome message from the total requested and
   * the number that failed. Never claims success when everything failed: when all
   * emails failed the message is phrased as a clear failure, and when some failed it
   * surfaces the count and the likely cause.
   */
  private String buildReadStatusMessage(int total, int failed, String state) {
    int succeeded = total - failed;
    if (total == 0) {
      return String.format("No email to mark as %s.", state);
    }
    if (failed == 0) {
      return String.format("Marked %d email(s) as %s.", succeeded, state);
    }
    if (failed == total) {
      return String.format("Failed to mark %d email(s) as %s (message not found on server or IMAP write denied).",
                           total,
                           state);
    }
    return String.format("Marked %d of %d email(s) as %s; %d failed (message not found on server or IMAP write denied).",
                         succeeded,
                         total,
                         state,
                         failed);
  }

  /**
   * Build the authenticated download URL for an attachment served by the existing
   * EmailBoxRest endpoint (GET /email-box/attachments/{mailRemoteId}/{attachmentId}).
   */
  private String buildAttachmentDownloadUrl(long mailRemoteId, String attachmentId) {
    return String.format("/portal/rest/email-box/attachments/%d/%s", mailRemoteId, attachmentId);
  }

  /**
   * Build an HTML EmailContent body for an outgoing message.
   */
  private EmailContent buildHtmlContent(String bodyHtml) {
    EmailContent content = new EmailContent();
    content.setBody(bodyHtml == null ? "" : bodyHtml);
    content.setHtml(true);
    return content;
  }

  /**
   * Turn a list of raw email addresses into EmailRecipient objects (address only).
   */
  private List<EmailRecipient> toRecipients(List<String> addresses) {
    if (addresses == null) {
      return List.of();
    }
    return addresses.stream()
                    .filter(StringUtils::isNotBlank)
                    .map(address -> new EmailRecipient(null, address.trim(), null, false))
                    .toList();
  }

  /**
   * Fetch the original email (with recipients) or fail if it cannot be found.
   */
  private Email fetchOriginalOrFail(long mailRemoteId, String username) throws IllegalAccessException {
    Email original = emailBoxService.getEmailByMailRemoteIdAndUserId(mailRemoteId, username, MailFolder.INBOX, false, true, false, false);
    if (original == null) {
      throw new IllegalArgumentException("Original email with mail_remote_id " + mailRemoteId + " not found");
    }
    return original;
  }

  /**
   * Build the shared shell of a reply: HTML body, "Re:" subject, and the original
   * Message-ID copied into mailHeaderId so the service sets In-Reply-To/References.
   */
  private Email buildReplyShell(Email original, String bodyHtml) {
    Email reply = new Email();
    reply.setContent(buildHtmlContent(bodyHtml));
    reply.setMailHeaderId(original.getMailHeaderId());
    String subject = original.getSubject() == null ? "" : original.getSubject();
    reply.setSubject(StringUtils.startsWithIgnoreCase(subject, "Re:") ? subject : "Re: " + subject);
    return reply;
  }

  /**
   * Build the HTML body of a forwarded email: the optional new note on top, then a
   * quoted block with the original sender/subject header and the original HTML body.
   */
  private String buildForwardBody(Email original, String bodyHtml) {
    StringBuilder body = new StringBuilder();
    if (StringUtils.isNotBlank(bodyHtml)) {
      body.append(bodyHtml);
    }
    body.append("<br/><hr/><div>---------- Forwarded message ----------</div>");
    if (original.getSender() != null) {
      body.append("<div>From: ")
          .append(StringUtils.defaultString(original.getSender().getName()))
          .append(" &lt;")
          .append(StringUtils.defaultString(original.getSender().getAddress()))
          .append("&gt;</div>");
    }
    body.append("<div>Subject: ").append(StringUtils.defaultString(original.getSubject())).append("</div><br/>");
    if (original.getContent() != null && original.getContent().getBody() != null) {
      body.append(original.getContent().getBody());
    }
    return body.toString();
  }

  /**
   * Wrap the original sender as the single recipient of a reply.
   */
  private List<EmailRecipient> senderAsRecipients(Email original) {
    String address = senderAddress(original);
    if (StringUtils.isBlank(address)) {
      throw new IllegalStateException("Original email has no sender address to reply to");
    }
    EmailSender sender = original.getSender();
    return List.of(new EmailRecipient(sender != null ? sender.getName() : null, address, null, false));
  }

  /**
   * Extract the sender email address from the original email.
   */
  private String senderAddress(Email original) {
    return original.getSender() != null ? original.getSender().getAddress() : null;
  }

  /**
   * Append recipients to the target list, skipping blanks and the excluded (self)
   * address so the current user is never CC'd back on their own reply-all.
   */
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
