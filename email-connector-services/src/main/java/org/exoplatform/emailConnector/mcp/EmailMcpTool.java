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

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
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
import org.exoplatform.emailConnector.mcp.model.SharedMailboxModel;
import org.exoplatform.emailConnector.model.Email;
import org.exoplatform.emailConnector.model.EmailAttachment;
import org.exoplatform.emailConnector.model.EmailBox;
import org.exoplatform.emailConnector.model.EmailCategory;
import org.exoplatform.emailConnector.model.EmailContent;
import org.exoplatform.emailConnector.model.EmailRecipient;
import org.exoplatform.emailConnector.model.EmailSearchResultPage;
import org.exoplatform.emailConnector.model.EmailSender;
import org.exoplatform.emailConnector.model.FolderRole;
import org.exoplatform.emailConnector.model.MailFolder;
import org.exoplatform.emailConnector.model.SharedMailboxEntry;
import org.exoplatform.emailConnector.model.SyncStatus;
import org.exoplatform.emailConnector.model.UserEmailSetting;
import org.exoplatform.emailConnector.exception.DelegationRevokedException;
import org.exoplatform.emailConnector.exception.MailboxRightMissingException;
import org.exoplatform.emailConnector.service.EmailBoxService;
import org.exoplatform.emailConnector.service.EmailDelegationService;
import org.exoplatform.emailConnector.service.UserEmailSettingService;

import org.exoplatform.services.log.ExoLogger;
import org.exoplatform.services.log.Log;

import io.meeds.mcp.server.plugin.McpToolPlugin;

/**
 * MCP tools exposing the Email Connector add-on to the AI agent (EVA). Every
 * method acts as the current user, so the caller only ever touches their own
 * email box -- or, when a tool is given a {@code mailbox}, a mailbox somebody shared
 * with them (EXO-90555), resolved among their own accepted shares and nowhere else. Read/triage tools run without approval; compose (send/reply) and
 * organize (archive/delete) tools are approval-gated: the assistant only DRAFTS
 * outward-facing actions and must never auto-send nor invent recipients.
 */
@Service
@Profile("mcp-server")
public class EmailMcpTool implements McpToolPlugin {

  private static final Log              LOG                  = ExoLogger.getLogger(EmailMcpTool.class);

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

  private final EmailDelegationService  emailDelegationService;

  /**
   * The tools' services.
   *
   * @param emailBoxService the mailbox service
   * @param userEmailSettingService the account binding service
   * @param emailDelegationService the shared mailboxes' service, which resolves a
   *          {@code mailbox} argument (EXO-90555)
   */
  @Autowired
  public EmailMcpTool(EmailBoxService emailBoxService,
                      UserEmailSettingService userEmailSettingService,
                      EmailDelegationService emailDelegationService) {
    this.emailBoxService = emailBoxService;
    this.userEmailSettingService = userEmailSettingService;
    this.emailDelegationService = emailDelegationService;
  }

  // ---------------------------------------------------------------------------
  // Shared mailboxes (EXO-90555)
  // ---------------------------------------------------------------------------

  /**
   * List the mailboxes other people shared with the user: whose, the name to pass as
   * {@code mailbox} to the email tools, the access granted, the unread count of the
   * shared inbox and which folders are available to read. Read from the user's mirror
   * alone, with no connection to any mail server; none while the user's own mail access
   * is switched off, as every other tool is then.
   *
   * @return the shared mailboxes, most recently changed first; empty when none
   */
  public List<SharedMailboxModel> listSharedMailboxes() {
    String username = getCurrentUserName();
    return emailDelegationService.getUsableSharedMailboxes(username)
                                 .stream()
                                 .map(share -> new SharedMailboxModel(share.ownerFullName(),
                                                                      share.ownerMailbox(),
                                                                      share.ownerId(),
                                                                      share.preset() == null ? "CUSTOM" : share.preset().name(),
                                                                      share.unreadCount(),
                                                                      emailDelegationService.getMirroredFolders(username, share),
                                                                      share.sentCopy()))
                                 .toList();
  }

  // ---------------------------------------------------------------------------
  // Slice 0 / existing reads (keyed off the local database id)
  // ---------------------------------------------------------------------------

  /**
   * Retrieve one stored email by its local database id (plain-text body).
   * <p>
   * Reads through {@link EmailBoxService#getOwnedEmailById}, not the plain lookup:
   * that one finds a row by its technical id alone and lets the username merely
   * decorate what comes back, which is right for a caller that has already established
   * who owns the row and wrong for anything reached from outside. This is reached from
   * outside — an agent hands it an id — and an id is guessable, so it is the same read
   * {@code EmailBoxRest} does. It refuses another user's mail rather than returning it.
   *
   * With a {@code mailbox}, the row must sit in that shared mailbox (EXO-90555); without
   * one, in the user's own. An id of the other kind is not found, never another message.
   *
   * @param emailId the cached email's local database id, as carried by every email this
   *          toolset returns
   * @param mailbox the shared mailbox the email is in, blank for the user's own
   * @return the email, with its body flattened to plain text
   * @throws ObjectNotFoundException if no such email is cached in that mailbox, or no
   *           such mailbox is shared with the user
   * @throws IllegalAccessException if the email belongs to somebody else
   */
  public EmailModel getEmailById(long emailId, String mailbox) throws ObjectNotFoundException, IllegalAccessException {
    // Own mailbox (EXO-90557) unless one is named: a row of a mailbox somebody shared
    // with the user is its owner's mail, handed out only to a caller who named it.
    SharedMailboxEntry share = sharedMailbox(mailbox);
    String username = getCurrentUserName();
    Email email = share == null ? emailBoxService.getOwnMailboxEmailById(emailId, username)
                                : emailBoxService.getSharedMailboxEmailById(emailId, username, share.delegationId());
    if (email == null) {
      throw new ObjectNotFoundException("Email with id %s not found");
    }
    return toEmailModel(email, true);
  }

  /**
   * Retrieve a page of the current user's synced mirror of a folder -- INBOX by
   * default, SENT or ARCHIVE -- of their own mailbox, or of a mailbox shared with them
   * when {@code mailbox} names one (EXO-90555). Supports paging (offset/limit,
   * defaulting to the first 10) and an unread-only filter so the agent triages
   * incrementally instead of pulling the whole mirror at once.
   * <p>
   * A shared mailbox's folder is listed only when it is in the user's mirror; one that
   * is not is said, never answered with an empty page that would read as "no mail".
   *
   * @param offset how many messages to skip
   * @param limit how many messages to return
   * @param unreadOnly only unread messages
   * @param folder INBOX (default), SENT or ARCHIVE
   * @param mailbox the shared mailbox to list, blank for the user's own
   * @return the page, newest first
   * @throws ObjectNotFoundException when no such mailbox is shared with the user
   * @throws IllegalAccessException if the user has no usable mailbox
   */
  public List<EmailModel> listEmails(Integer offset,
                                     Integer limit,
                                     Boolean unreadOnly,
                                     String folder,
                                     String mailbox) throws ObjectNotFoundException, IllegalAccessException {
    SharedMailboxEntry share = sharedMailbox(mailbox);
    String folderName = browsableFolder(folder);
    String folderKey = share == null ? folderName : mirroredFolderOrFail(share, folderName);
    EmailBox emailBox = emailBoxService.getEmailBox(getCurrentUserName(), folderKey);
    return emailBox.getEmails()
                   .stream()
                   .filter(email -> !Boolean.TRUE.equals(unreadOnly) || !email.isRead())
                   .skip(getInteger(offset, DEFAULT_OFFSET))
                   .limit(getInteger(limit, DEFAULT_LIMIT))
                   .map(email -> withBody(email, share))
                   .map(email -> toEmailModel(email, false))
                   .toList();
  }

  /**
   * Re-reads one listed row whole, so this tool keeps answering with the message.
   * <p>
   * A list read no longer carries bodies: the mailbox drawer renders one truncated line
   * of the excerpt and never reads them, and carrying every cached message's HTML for it
   * was one of the costs of opening the drawer. This tool did read them, so it fetches
   * the few it is about to return — the page is ten rows by default, and this runs on an
   * agent's triage call rather than on the drawer's hot path, which is the whole point
   * of putting the cost here.
   * <p>
   * A row that cannot be re-read is answered as it was listed rather than failing the
   * call: the agent then sees a message with no body, which is what it would have seen
   * had the row lost its body for any other reason, and the rest of the page still
   * arrives.
   *
   * @param email the listed row
   * @param share the shared mailbox listed, null for the user's own
   * @return the same message read whole, or the listed row if it cannot be re-read
   */
  private Email withBody(Email email, SharedMailboxEntry share) {
    if (email.getId() == null || email.getContent() != null && email.getContent().getBody() != null) {
      return email;
    }
    try {
      String username = getCurrentUserName();
      Email whole = share == null ? emailBoxService.getOwnMailboxEmailById(email.getId(), username)
                                  : emailBoxService.getSharedMailboxEmailById(email.getId(), username, share.delegationId());
      return whole == null ? email : whole;
    } catch (IllegalAccessException e) {
      LOG.debug("Could not re-read email {} whole for the agent listing; answering it as listed", email.getId(), e);
      return email;
    }
  }

  /**
   * Report how many emails in the synced INBOX mirror are unread, out of the total
   * mirrored. Fast triage summary that never returns bodies. For a mailbox shared with
   * the user (EXO-90555), the unread count of its inbox as the user's mirror holds it --
   * the count the mailbox switcher shows, read without reaching any mail server.
   *
   * @param mailbox the shared mailbox to count, blank for the user's own
   * @return the summary
   * @throws ObjectNotFoundException when no such mailbox is shared with the user
   * @throws IllegalAccessException if the user has no usable mailbox
   */
  public String getUnreadCount(String mailbox) throws ObjectNotFoundException, IllegalAccessException {
    SharedMailboxEntry share = sharedMailbox(mailbox);
    if (share != null) {
      // An inbox not in the mirror yet has no count to give: "0 unread" would be the
      // same "no mail" pretence the listing refuses.
      mirroredFolderOrFail(share, MailFolder.INBOX);
      return String.format("%d unread email(s) in the inbox of %s, shared with you.", share.unreadCount(), ownerOf(share));
    }
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
   * resulting sync status and the number of emails now in the local mirror. The user's
   * own mailbox only: a mailbox shared with them is kept in sync by the platform, and a
   * {@code mailbox} argument is refused rather than ignored (EXO-90555).
   *
   * @param mailbox must be blank
   * @return the sync outcome
   * @throws IllegalAccessException if the user has no usable mailbox
   */
  public String syncNow(String mailbox) throws IllegalAccessException {
    if (StringUtils.isNotBlank(mailbox)) {
      throw new IllegalArgumentException("sync_now works on your own mailbox only: a mailbox shared with you is kept in sync by the platform. "
          + "Call it without mailbox, or read the shared mailbox directly with list_emails.");
    }
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
   * <p>
   * A hit is only chainable into the id-based tools ({@link #getEmailFull},
   * {@code mark_read}, {@code reply_email}) when it is an INBOX hit that is already
   * cached. Those tools take the folder-less overloads, which resolve against the
   * INBOX mirror, and an IMAP UID is unique only within its own folder — so a SENT
   * or ARCHIVE hit would silently address the unrelated inbox message holding the
   * same UID, and an uncached hit is not in the mirror at all. The tool description
   * states this so the model does not build the broken chain; widening it means
   * backporting the folder-aware fetch path (EXO-88990 and later).
   *
   * @param query free text matched against the subject or the sender, may be blank
   * @param from text matched against the sender only, may be blank
   * @param unread when {@code true}, only unread messages match
   * @param sinceDays only messages received in the last N days match, null for the
   *          whole history
   * <p>
   * With a {@code mailbox} (EXO-90555), the search reads the user's mirror of that
   * shared mailbox's folder -- the messages its sync brought in, subject or sender --
   * and a folder that is not in the mirror is said rather than answered as empty.
   *
   * @param folder INBOX (default), SENT or ARCHIVE
   * @param limit how many hits to return, newest first
   * @param mailbox the shared mailbox to search, blank for the user's own
   * @return the newest matching messages and the total number that matched
   * @throws ObjectNotFoundException when no such mailbox is shared with the user
   * @throws IllegalAccessException if the user has no usable mailbox
   */
  public EmailSearchResultsModel searchEmails(String query,
                                              String from,
                                              Boolean unread,
                                              Integer sinceDays,
                                              String folder,
                                              Integer limit,
                                              String mailbox) throws ObjectNotFoundException, IllegalAccessException {
    // Resolved before the translation block below: its refusals are already in words.
    SharedMailboxEntry share = sharedMailbox(mailbox);
    String sharedFolderKey = null;
    if (share != null && !MailFolder.isCustom(folder)) {
      sharedFolderKey = mirroredFolderOrFail(share, browsableFolder(folder));
    }
    try {
      // Built-in folder names only: a CUSTOM:<id> key would reach the user's registered
      // folders, which from EXO-90457 include the INBOX of a mailbox somebody else
      // shared with them -- a shared mailbox is named by mailbox, never by a key.
      if (MailFolder.isCustom(folder)) {
        throw new IllegalArgumentException("emailConnector.folder.notBrowsable");
      }
      String folderName = StringUtils.isBlank(folder) ? MailFolder.INBOX : folder.trim().toUpperCase(Locale.ROOT);
      int hitLimit = limit == null ? DEFAULT_SEARCH_LIMIT : limit;
      EmailSearchResultPage page = share == null ? emailBoxService.searchEmails(getCurrentUserName(),
                                                                                query,
                                                                                from,
                                                                                Boolean.TRUE.equals(unread),
                                                                                sinceDays,
                                                                                folderName,
                                                                                hitLimit)
                                                 : emailBoxService.searchSharedMailboxMirror(getCurrentUserName(),
                                                                                             sharedFolderKey,
                                                                                             query,
                                                                                             from,
                                                                                             Boolean.TRUE.equals(unread),
                                                                                             sinceDays,
                                                                                             hitLimit);
      List<EmailSearchHitModel> hits = page.getResults()
                                           .stream()
                                           .map(result -> new EmailSearchHitModel(result.getMailRemoteId(),
                                                                                  // The folder's name, never
                                                                                  // a shared folder's key.
                                                                                  share == null ? result.getFolder() : folderName,
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
      //
      // Only a KNOWN code is translated. Map.of rejects a null key, so a message-less
      // IllegalArgumentException would make getOrDefault throw NPE inside this catch
      // and bury the real failure; and an unmapped one is not necessarily the caller's
      // fault -- Long.parseLong on the stored connector id raises NumberFormatException,
      // itself an IllegalArgumentException, whose "For input string: ..." is exactly the
      // internal noise this block exists to keep away from the model.
      String message = SEARCH_MESSAGES.get(e.getMessage());
      if (message == null) {
        throw new IllegalArgumentException("The search could not be run with these arguments. Check query, from, unread, sinceDays, folder and limit.",
                                           e);
      }
      throw new IllegalArgumentException(message, e);
    } catch (IllegalStateException e) {
      // NOT the sync-in-progress case: that code (emailConnector.search.syncInProgress)
      // is raised by fetchSearchedEmail, which this tool never calls. Everything
      // searchEmails can raise here is a connect or SEARCH failure -- bad credentials,
      // unreachable server, TLS. Telling the model to retry shortly would have it loop
      // on a call that keeps failing, and report that wrong diagnosis to the user.
      throw new IllegalStateException("The mail server could not be reached or searched. The mailbox connection may be broken; check the mail account settings.",
                                      e);
    }
  }

  /**
   * Fetch a single email in full by its IMAP mailRemoteId, including recipients,
   * content and attachment metadata (plain-text body). The UID is read in the inbox of
   * the mailbox named -- the user's own, or a shared one (EXO-90555) -- and nowhere
   * else: a UID numbers a message within one folder only.
   *
   * @param mailRemoteId the message's UID in that inbox
   * @param mailbox the shared mailbox, blank for the user's own
   * @return the email
   * @throws ObjectNotFoundException if no such email is cached there, or no such
   *           mailbox is shared with the user
   * @throws IllegalAccessException if the user has no usable mailbox
   */
  public EmailModel getEmailFull(long mailRemoteId, String mailbox) throws ObjectNotFoundException, IllegalAccessException {
    Email email = emailBoxService.getEmailByMailRemoteIdAndUserId(mailRemoteId,
                                                                  getCurrentUserName(),
                                                                  inboxOf(sharedMailbox(mailbox)),
                                                                  true,
                                                                  true,
                                                                  true,
                                                                  false);
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
   * <p>
   * What each message IS given is its identity: its {@code email_id}, its
   * {@code mail_remote_id} and the folder that UID belongs to (see
   * {@link #toThreadMessageModel}). Both cuts above are then recoverable — a body cut
   * at {@link #THREAD_BODY_MAX_CHARS} is one {@code get_email_by_id} away from being
   * read whole — and a reply can be threaded onto the message it answers rather than
   * onto whatever a subject-and-sender search happens to surface first.
   * <p>
   * Where a thread_id comes from: {@code list_emails}, {@code get_email_by_id} and
   * {@code get_email_full}, which read the local cache and carry the conversation id on
   * every message. NOT {@code search_emails}: a search hit is an envelope the mail
   * server answered with, and a message outside the local sync window has no cached row
   * and therefore no computed conversation id at all. From a hit, chain its
   * {@code mail_remote_id} through {@code get_email_full} first.
   *
   * <p>
   * With a {@code mailbox} (EXO-90555), the conversation as that shared mailbox holds
   * it, and none of the user's own rows; without one, the user's own rows only.
   *
   * @param threadId the conversation id, as carried by every cached email read
   * @param mailbox the shared mailbox, blank for the user's own
   * @return the conversation's real messages, oldest first
   * @throws ObjectNotFoundException when no such mailbox is shared with the user
   * @throws IllegalAccessException if the user has no usable mailbox
   */
  public List<EmailThreadMessageModel> getEmailThread(String threadId, String mailbox) throws ObjectNotFoundException,
                                                                                       IllegalAccessException {
    if (StringUtils.isBlank(threadId)) {
      throw new IllegalArgumentException("thread_id is required: it is carried by every email returned by list_emails, "
          + "get_email_by_id and get_email_full. A search hit does not carry one — fetch it with get_email_full first.");
    }
    SharedMailboxEntry share = sharedMailbox(mailbox);
    List<Email> thread = share == null ? emailBoxService.getThread(threadId, getCurrentUserName())
                                       : emailBoxService.getThread(threadId, getCurrentUserName(), share.folderKey());
    List<Email> messages = thread.stream().filter(email -> StringUtils.isBlank(email.getDraftLocalId())).toList();
    // The most recent ones, and still oldest-first once kept: a conversation read
    // backwards is a conversation nobody can follow.
    if (messages.size() > THREAD_MAX_MESSAGES) {
      messages = messages.subList(messages.size() - THREAD_MAX_MESSAGES, messages.size());
    }
    return messages.stream().map(email -> toThreadMessageModel(email, share)).toList();
  }

  /**
   * List the metadata of a given email's attachments (by IMAP mailRemoteId):
   * name, mime type, MIME part path and a ready authenticated download URL served
   * by EmailBoxRest. No attachment bytes ever pass through the tool - the user
   * opens the URL in their own authenticated browser. Empty list if none. The UID is
   * read in the inbox of the mailbox named, the user's own or a shared one (EXO-90555).
   *
   * @param mailRemoteId the message's UID in that inbox
   * @param mailbox the shared mailbox, blank for the user's own
   * @return the attachments, empty when none
   * @throws ObjectNotFoundException when no such mailbox is shared with the user
   * @throws IllegalAccessException if the user has no usable mailbox
   */
  public List<EmailAttachmentModel> listAttachments(long mailRemoteId, String mailbox) throws ObjectNotFoundException,
                                                                                       IllegalAccessException {
    SharedMailboxEntry share = sharedMailbox(mailbox);
    String folderKey = inboxOf(share);
    Email email = emailBoxService.getEmailByMailRemoteIdAndUserId(mailRemoteId, getCurrentUserName(), folderKey, true, false, false, false);
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
                                                                                       attachment.getAttachmentRemoteId(),
                                                                                       share == null ? null : folderKey)))
                .toList();
  }

  /**
   * Mark one or more emails (by IMAP mailRemoteId) as read, locally and on the
   * IMAP server. Inbox messages only — see {@link #archiveEmail}. Reports the true
   * outcome: emails whose server flag could not be
   * written (message not found on server or IMAP write denied) are counted as
   * failed rather than reported as success.
   *
   * @param mailRemoteIds the UIDs in the inbox of the mailbox named
   * @param mailbox the shared mailbox, blank for the user's own (EXO-90555)
   * @return the outcome, in words
   * @throws ObjectNotFoundException when no such mailbox is shared with the user
   * @throws IllegalAccessException if the user may not
   */
  public String markRead(List<Long> mailRemoteIds, String mailbox) throws ObjectNotFoundException, IllegalAccessException {
    return markReadStatus(mailRemoteIds, mailbox, true);
  }

  /**
   * Mark one or more emails (by IMAP mailRemoteId) as unread, locally and on the
   * IMAP server. Inbox messages only — see {@link #archiveEmail}. Reports the true
   * outcome: emails whose server flag could not be
   * written (message not found on server or IMAP write denied) are counted as
   * failed rather than reported as success.
   *
   * @param mailRemoteIds the UIDs in the inbox of the mailbox named
   * @param mailbox the shared mailbox, blank for the user's own (EXO-90555)
   * @return the outcome, in words
   * @throws ObjectNotFoundException when no such mailbox is shared with the user
   * @throws IllegalAccessException if the user may not
   */
  public String markUnread(List<Long> mailRemoteIds, String mailbox) throws ObjectNotFoundException, IllegalAccessException {
    return markReadStatus(mailRemoteIds, mailbox, false);
  }

  /**
   * Marks messages of the inbox of the mailbox named read or unread -- the user's own,
   * or a shared one (EXO-90555), where the service checks the user may keep read state
   * there before touching anything.
   *
   * @param mailRemoteIds the UIDs in that inbox
   * @param mailbox the shared mailbox, blank for the user's own
   * @param read the state to set
   * @return the outcome, in words
   * @throws ObjectNotFoundException when no such mailbox is shared with the user
   * @throws IllegalAccessException if the user may not
   */
  private String markReadStatus(List<Long> mailRemoteIds, String mailbox, boolean read) throws ObjectNotFoundException,
                                                                                         IllegalAccessException {
    SharedMailboxEntry share = sharedMailbox(mailbox);
    int total = mailRemoteIds == null ? 0 : mailRemoteIds.size();
    int failed;
    try {
      failed = emailBoxService.updateEmailReadStatus(mailRemoteIds, getCurrentUserName(), inboxOf(share), read, true);
    } catch (MailboxRightMissingException | DelegationRevokedException e) {
      throw refusedIn(share, "change the read state of its mail", e);
    }
    return buildReadStatusMessage(total, failed, read ? "read" : "unread") + inMailbox(share);
  }

  // ---------------------------------------------------------------------------
  // Slice 2 - compose (approval-gated, outward facing, irreversible)
  // ---------------------------------------------------------------------------

  /**
   * Send a brand new email over real SMTP (also copied to the Sent folder).
   * Body is HTML. Optional cc/bcc recipients. Attachments are NOT supported by the
   * backing service. From a mailbox shared with the user (EXO-90555), the mail still
   * goes out from the user's own address, and a copy is filed in the owner's Sent
   * where the share allows it -- the answer names the owner and says which.
   *
   * @param to the recipients
   * @param subject the subject
   * @param bodyHtml the HTML body
   * @param cc the copied recipients
   * @param bcc the blind-copied recipients
   * @param mailbox the shared mailbox the mail is sent from, blank for the user's own
   * @return the outcome, in words
   * @throws ObjectNotFoundException when no such mailbox is shared with the user
   * @throws IllegalAccessException if the user may not send
   */
  public String sendEmail(List<String> to,
                          String subject,
                          String bodyHtml,
                          List<String> cc,
                          List<String> bcc,
                          String mailbox) throws ObjectNotFoundException, IllegalAccessException {
    if (to == null || to.stream().filter(StringUtils::isNotBlank).findAny().isEmpty()) {
      throw new IllegalArgumentException("At least one recipient is required in 'to'");
    }
    Email email = new Email();
    email.setSubject(subject);
    email.setContent(buildHtmlContent(bodyHtml));
    email.setTo(toRecipients(to));
    email.setCc(toRecipients(cc));
    email.setBcc(toRecipients(bcc));
    SharedMailboxEntry share = sharedMailbox(mailbox);
    String copy = send(email, getCurrentUserName(), share);
    return String.format("Email sent to %s with subject \"%s\"%s.%s", String.join(", ", to), subject, fromMailbox(share), copy);
  }

  /**
   * Reply to the sender of an existing email (by IMAP mailRemoteId). Threads the
   * reply by copying the original Message-ID into In-Reply-To/References. With a
   * {@code mailbox} (EXO-90555), the original is read in that shared mailbox's inbox
   * and the reply is sent from it, as {@link #sendEmail} does.
   *
   * @param mailRemoteId the original's UID in the inbox of the mailbox named
   * @param bodyHtml the HTML body
   * @param mailbox the shared mailbox, blank for the user's own
   * @return the outcome, in words
   * @throws ObjectNotFoundException when no such mailbox is shared with the user
   * @throws IllegalAccessException if the user may not send
   */
  public String replyEmail(long mailRemoteId, String bodyHtml, String mailbox) throws ObjectNotFoundException,
                                                                               IllegalAccessException {
    String username = getCurrentUserName();
    SharedMailboxEntry share = sharedMailbox(mailbox);
    Email original = fetchOriginalOrFail(mailRemoteId, username, inboxOf(share));
    Email reply = buildReplyShell(original, bodyHtml);
    reply.setTo(senderAsRecipients(original));
    String copy = send(reply, username, share);
    return String.format("Reply sent to %s%s.%s", senderAddress(original), fromMailbox(share), copy);
  }

  /**
   * Reply to everyone on an existing email (by IMAP mailRemoteId): To = original
   * sender, Cc = original To + Cc minus the current user's own address. With a
   * {@code mailbox} (EXO-90555), the original is read in that shared mailbox's inbox,
   * the reply is sent from it, and the owner's address is left out of the Cc too: the
   * owner is the mailbox the reply is sent from, and gets their copy in their Sent.
   *
   * @param mailRemoteId the original's UID in the inbox of the mailbox named
   * @param bodyHtml the HTML body
   * @param mailbox the shared mailbox, blank for the user's own
   * @return the outcome, in words
   * @throws ObjectNotFoundException when no such mailbox is shared with the user
   * @throws IllegalAccessException if the user may not send
   */
  public String replyAll(long mailRemoteId, String bodyHtml, String mailbox) throws ObjectNotFoundException,
                                                                             IllegalAccessException {
    String username = getCurrentUserName();
    SharedMailboxEntry share = sharedMailbox(mailbox);
    Email original = fetchOriginalOrFail(mailRemoteId, username, inboxOf(share));
    Email reply = buildReplyShell(original, bodyHtml);
    reply.setTo(senderAsRecipients(original));
    String selfAddress = userEmailSettingService.getUserEmailSetting(username).getEmailAddress();
    List<EmailRecipient> ccRecipients = new ArrayList<>();
    addRecipientsExcluding(ccRecipients, original.getTo(), selfAddress);
    addRecipientsExcluding(ccRecipients, original.getCc(), selfAddress);
    if (share != null) {
      ccRecipients.removeIf(recipient -> recipient.getAddress().equalsIgnoreCase(share.ownerMailbox()));
    }
    reply.setCc(ccRecipients);
    String copy = send(reply, username, share);
    return String.format("Reply-all sent to %s%s.%s", senderAddress(original), fromMailbox(share), copy);
  }

  /**
   * Forward an existing email (by IMAP mailRemoteId) to brand new recipients. The
   * subject is prefixed with "Fwd:" and the original message is quoted below the
   * optional new note. Attachments are NOT carried over (the backing service cannot
   * attach files). With a {@code mailbox} (EXO-90555), the original is read in that
   * shared mailbox's inbox and the forward is sent from it, as {@link #sendEmail} does.
   *
   * @param mailRemoteId the original's UID in the inbox of the mailbox named
   * @param to the recipients
   * @param bodyHtml the note above the forwarded message
   * @param cc the copied recipients
   * @param mailbox the shared mailbox, blank for the user's own
   * @return the outcome, in words
   * @throws ObjectNotFoundException when the original is not there, or no such mailbox
   *           is shared with the user
   * @throws IllegalAccessException if the user may not send
   */
  public String forwardEmail(long mailRemoteId,
                             List<String> to,
                             String bodyHtml,
                             List<String> cc,
                             String mailbox) throws ObjectNotFoundException, IllegalAccessException {
    if (to == null || to.stream().filter(StringUtils::isNotBlank).findAny().isEmpty()) {
      throw new IllegalArgumentException("At least one recipient is required in 'to'");
    }
    String username = getCurrentUserName();
    SharedMailboxEntry share = sharedMailbox(mailbox);
    Email original = emailBoxService.getEmailByMailRemoteIdAndUserId(mailRemoteId, username, inboxOf(share), false, true, false, false);
    if (original == null) {
      throw new ObjectNotFoundException("Email with mail_remote_id %s not found");
    }
    Email forward = new Email();
    String subject = original.getSubject() == null ? "" : original.getSubject();
    forward.setSubject(StringUtils.startsWithIgnoreCase(subject, "Fwd:") ? subject : "Fwd: " + subject);
    forward.setContent(buildHtmlContent(buildForwardBody(original, bodyHtml)));
    forward.setTo(toRecipients(to));
    forward.setCc(toRecipients(cc));
    String copy = send(forward, username, share);
    return String.format("Email forwarded to %s with subject \"%s\"%s.%s",
                         String.join(", ", to),
                         forward.getSubject(),
                         fromMailbox(share),
                         copy);
  }

  // ---------------------------------------------------------------------------
  // Slice 3 - organize (approval-gated)
  // ---------------------------------------------------------------------------

  /**
   * Move one or more emails (by IMAP mailRemoteId) to the Archive folder.
   * <p>
   * Inbox messages only: the ids this toolset hands out are INBOX UIDs, and a UID
   * numbers a message within one folder. Passing the folder explicitly is what keeps
   * that a stated limit rather than a silent assumption (EXO-89367). With a
   * {@code mailbox} (EXO-90555), that shared mailbox's inbox, filed into its own
   * Archive, where the user's rights there allow it.
   *
   * @param mailRemoteIds the UIDs in the inbox of the mailbox named
   * @param mailbox the shared mailbox, blank for the user's own
   * @return the outcome, in words
   * @throws ObjectNotFoundException when no such mailbox is shared with the user
   * @throws IllegalAccessException if the user may not
   */
  public String archiveEmail(List<Long> mailRemoteIds, String mailbox) throws ObjectNotFoundException, IllegalAccessException {
    SharedMailboxEntry share = sharedMailbox(mailbox);
    int failed;
    try {
      failed = emailBoxService.archiveEmail(mailRemoteIds, getCurrentUserName(), inboxOf(share));
    } catch (MailboxRightMissingException | DelegationRevokedException e) {
      throw refusedIn(share, "archive its mail", e);
    }
    int total = mailRemoteIds == null ? 0 : mailRemoteIds.size();
    return String.format("Archived %d of %d email(s)%s%s.",
                         total - failed,
                         total,
                         failed > 0 ? " (" + failed + " failed)" : "",
                         inMailboxPhrase(share));
  }

  /**
   * Delete one or more emails (by IMAP mailRemoteId): copies them to Trash then
   * expunges them from the INBOX. Destructive and irreversible. With a {@code mailbox}
   * (EXO-90555), that shared mailbox's inbox, into its own Trash, where the user's
   * rights there allow it.
   *
   * @param mailRemoteIds the UIDs in the inbox of the mailbox named
   * @param mailbox the shared mailbox, blank for the user's own
   * @return the outcome, in words
   * @throws ObjectNotFoundException when no such mailbox is shared with the user
   * @throws IllegalAccessException if the user may not
   */
  public String deleteEmail(List<Long> mailRemoteIds, String mailbox) throws ObjectNotFoundException, IllegalAccessException {
    SharedMailboxEntry share = sharedMailbox(mailbox);
    int failed;
    try {
      failed = emailBoxService.deleteEmail(mailRemoteIds, getCurrentUserName(), inboxOf(share));
    } catch (MailboxRightMissingException | DelegationRevokedException e) {
      throw refusedIn(share, "delete its mail", e);
    }
    int total = mailRemoteIds == null ? 0 : mailRemoteIds.size();
    return String.format("Deleted %d of %d email(s)%s%s.",
                         total - failed,
                         total,
                         failed > 0 ? " (" + failed + " failed)" : "",
                         inMailboxPhrase(share));
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
   * plain text and surfacing the two ids needed to chain the other tools: the
   * mailRemoteId the write tools key on, and the threadId {@code get_email_thread}
   * takes. The thread id is on the domain object for every cached read, and was simply
   * being dropped here — while the conversation tool's description told callers to
   * expect it (EXO-89372).
   */
  private EmailModel toEmailModel(Email email, boolean includeUserEmail) {
    EmailContent content = email.getContent();
    if (content != null && content.getBody() != null) {
      content.setBody(Jsoup.parse(content.getBody()).text().trim());
    }
    EmailModel model = new EmailModel();
    model.setId(email.getId());
    model.setMailRemoteId(email.getMailRemoteId());
    model.setThreadId(email.getThreadId());
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
   * needs: who wrote it, when, about what, what it said, what came with it — and how
   * to act on it afterwards.
   * <p>
   * That last part is the whole of {@code EXO-89372}. A message read in a conversation
   * used to arrive with no identifier at all, so the one thing a reader most often
   * wants next — reply to THIS message, read the body that was cut, list what was
   * attached — could only be attempted by searching the mailbox again for a subject and
   * a sender and hoping the newest hit was the same message. Both ids the toolset keys
   * on are therefore carried here, and the folder with them: a UID names a message
   * within one folder only, and a conversation reliably spans INBOX and SENT.
   * <p>
   * The folder is passed through exactly as stored rather than defaulted to INBOX when
   * it is missing. Defaulting it would be the same silent assumption EXO-89367 was
   * about; a message that cannot say where it lives should say nothing, and the tool's
   * description tells the caller not to act on the UID of one that does not.
   *
   * <p>
   * In a shared mailbox (EXO-90555) the folder is given by name -- INBOX, SENT or
   * ARCHIVE, through the share's own folder roles -- never as a {@code CUSTOM:<id>} key,
   * so the same rule applies there: a UID is chainable only from a message whose folder
   * is INBOX. A folder of the share with no such role says nothing.
   *
   * @param email the cached message
   * @param share the shared mailbox read, null for the user's own
   * @return its conversation-reading shape
   */
  private EmailThreadMessageModel toThreadMessageModel(Email email, SharedMailboxEntry share) {
    EmailSender sender = email.getSender();
    return new EmailThreadMessageModel(email.getId(),
                                       email.getMailRemoteId(),
                                       share == null ? email.getFolder() : folderNameIn(share, email.getFolder()),
                                       sender == null ? null : sender.getName(),
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
   * EmailBoxRest endpoint (GET /email-box/attachments/{mailRemoteId}/{attachmentId}),
   * under the add-on's own REST context -- the address the mailbox itself downloads
   * from -- with the folder the UID belongs to when it is not the user's INBOX.
   *
   * @param mailRemoteId the message's UID
   * @param attachmentId the attachment's part id
   * @param folderKey the folder the UID belongs to, null for the user's INBOX
   * @return the URL
   */
  private String buildAttachmentDownloadUrl(long mailRemoteId, String attachmentId, String folderKey) {
    String url = String.format("/email-connector/rest/email-box/attachments/%d/%s", mailRemoteId, attachmentId);
    return folderKey == null ? url : url + "?folder=" + URLEncoder.encode(folderKey, StandardCharsets.UTF_8);
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
   * Fetch the original email (with recipients) in one folder -- the inbox of the
   * mailbox the call named -- or fail if it cannot be found there.
   *
   * @param mailRemoteId the original's UID in that folder
   * @param username the user
   * @param folderKey the folder
   * @return the original
   * @throws IllegalAccessException if the user has no usable mailbox
   */
  private Email fetchOriginalOrFail(long mailRemoteId, String username, String folderKey) throws IllegalAccessException {
    Email original = emailBoxService.getEmailByMailRemoteIdAndUserId(mailRemoteId, username, folderKey, false, true, false, false);
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

  // ---------------------------------------------------------------------------
  // Shared-mailbox helpers (EXO-90555)
  // ---------------------------------------------------------------------------

  /**
   * The shared mailbox a {@code mailbox} argument names, or null for a blank one --
   * which alone means the user's own. A name that resolves to nothing is not found, and
   * never falls back to the user's own mailbox.
   *
   * @param mailbox the argument
   * @return the shared mailbox, null for the user's own
   * @throws ObjectNotFoundException when no accepted share of the user's has that name
   */
  private SharedMailboxEntry sharedMailbox(String mailbox) throws ObjectNotFoundException {
    if (StringUtils.isBlank(mailbox)) {
      return null;
    }
    try {
      return emailDelegationService.getSharedMailbox(getCurrentUserName(), mailbox);
    } catch (ObjectNotFoundException e) {
      throw new ObjectNotFoundException(String.format("No mailbox \"%s\" is shared with you. list_shared_mailboxes lists the ones that are; "
          + "leave mailbox empty for your own.", mailbox.trim()));
    }
  }

  /**
   * The inbox a UID-keyed tool reads and writes in: the user's own INBOX, or the shared
   * mailbox's registered inbox.
   *
   * @param share the shared mailbox, null for the user's own
   * @return the folder key
   */
  private static String inboxOf(SharedMailboxEntry share) {
    return share == null ? MailFolder.INBOX : share.folderKey();
  }

  /**
   * A folder argument as one of the listable folder names.
   *
   * @param folder the argument
   * @return INBOX (the default), SENT or ARCHIVE
   * @throws IllegalArgumentException for any other folder
   */
  private static String browsableFolder(String folder) {
    String name = StringUtils.isBlank(folder) ? MailFolder.INBOX : folder.trim().toUpperCase(Locale.ROOT);
    if (!MailFolder.INBOX.equals(name) && !MailFolder.SENT.equals(name) && !MailFolder.ARCHIVE.equals(name)) {
      throw new IllegalArgumentException("folder must be one of INBOX, SENT or ARCHIVE.");
    }
    return name;
  }

  /**
   * The key of a shared mailbox's folder that is in the user's mirror, or a refusal that
   * says so: a folder not shared, or not synced yet, is never answered as an empty one.
   *
   * @param share the shared mailbox
   * @param folderName INBOX, SENT or ARCHIVE
   * @return the folder key
   * @throws IllegalArgumentException when that folder is not available
   */
  private String mirroredFolderOrFail(SharedMailboxEntry share, String folderName) {
    String key = emailDelegationService.getMirroredFolderKey(getCurrentUserName(), share, folderName);
    if (key == null) {
      throw new IllegalArgumentException(String.format("The %s folder of %s is not available: it is not shared with you, or has not been synced yet. "
          + "This is not an empty folder; list_shared_mailboxes says which folders are available.", folderName, ownerOf(share)));
    }
    return key;
  }

  /**
   * The name of a shared mailbox's folder -- INBOX, SENT or ARCHIVE -- from its key.
   *
   * @param share the shared mailbox
   * @param folderKey the folder's key
   * @return the name, null for a folder with none of those roles
   */
  private static String folderNameIn(SharedMailboxEntry share, String folderKey) {
    if (folderKey == null) {
      return null;
    }
    if (folderKey.equals(share.folderKey())) {
      return MailFolder.INBOX;
    }
    return share.folders()
                .stream()
                .filter(folder -> folderKey.equals(folder.key()))
                .map(folder -> folder.role() == FolderRole.SENT ? MailFolder.SENT
                                                                : folder.role() == FolderRole.ARCHIVE ? MailFolder.ARCHIVE : null)
                .filter(Objects::nonNull)
                .findFirst()
                .orElse(null);
  }

  /**
   * Sends through the service, from the shared mailbox when one is named, and says what
   * became of the owner's copy.
   *
   * @param email the mail
   * @param username the sender
   * @param share the shared mailbox, null for the user's own
   * @return a sentence about the owner's copy, empty for the user's own mailbox
   * @throws ObjectNotFoundException when the share is not the sender's
   * @throws IllegalAccessException if the user may not send
   */
  private String send(Email email, String username, SharedMailboxEntry share) throws ObjectNotFoundException,
                                                                             IllegalAccessException {
    if (share == null) {
      emailBoxService.sendEmail(email, username);
      return "";
    }
    EmailBoxService.OwnerCopy copy;
    try {
      copy = emailBoxService.sendEmail(email, username, share.delegationId());
    } catch (DelegationRevokedException e) {
      throw refusedIn(share, "send from it", e);
    }
    if (copy == EmailBoxService.OwnerCopy.FILED) {
      return String.format(" A copy was filed in the Sent folder of %s.", ownerOf(share));
    } else if (copy == EmailBoxService.OwnerCopy.FAILED) {
      return String.format(" The copy in the Sent folder of %s could not be filed; the mail itself was sent.", ownerOf(share));
    }
    return String.format(" No copy was filed in the Sent folder of %s: filing there is not shared with you, or is switched off.",
                         ownerOf(share));
  }

  /**
   * A refusal in a shared mailbox, in words that name it.
   *
   * @param share the shared mailbox, null for the user's own
   * @param action what was refused, as "archive its mail"
   * @param cause the service's refusal
   * @return the exception to throw
   */
  private static IllegalAccessException refusedIn(SharedMailboxEntry share, String action, Exception cause) {
    String message;
    if (share == null) {
      message = cause.getMessage();
    } else if (cause instanceof DelegationRevokedException) {
      message = String.format("The mailbox of %s is no longer shared with you: nothing was done.", ownerOf(share));
    } else {
      message = String.format("Your access to the mailbox of %s does not allow you to %s: nothing was done.", ownerOf(share), action);
    }
    IllegalAccessException refusal = new IllegalAccessException(message);
    refusal.initCause(cause);
    return refusal;
  }

  /**
   * The owner of a shared mailbox, as the answers name them: their name and address.
   *
   * @param share the shared mailbox
   * @return the name
   */
  private static String ownerOf(SharedMailboxEntry share) {
    String name = StringUtils.defaultIfBlank(share.ownerFullName(), share.ownerMailbox());
    return name == null || name.equalsIgnoreCase(share.ownerMailbox()) ? name : name + " (" + share.ownerMailbox() + ")";
  }

  /**
   * " from the mailbox of X" for a shared mailbox, nothing for the user's own.
   *
   * @param share the shared mailbox, null for the user's own
   * @return the phrase
   */
  private static String fromMailbox(SharedMailboxEntry share) {
    return share == null ? "" : " from the mailbox of " + ownerOf(share) + ", sent from your own address";
  }

  /**
   * " in the mailbox of X" for a shared mailbox, nothing for the user's own.
   *
   * @param share the shared mailbox, null for the user's own
   * @return the phrase
   */
  private static String inMailboxPhrase(SharedMailboxEntry share) {
    return share == null ? "" : " in the mailbox of " + ownerOf(share);
  }

  /**
   * " (in the mailbox of X)" appended to an outcome sentence, nothing for the user's own.
   *
   * @param share the shared mailbox, null for the user's own
   * @return the suffix
   */
  private static String inMailbox(SharedMailboxEntry share) {
    return share == null ? "" : " (in the mailbox of " + ownerOf(share) + ")";
  }
}
