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

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Stream;

import javax.activation.DataHandler;
import javax.mail.Message;
import javax.mail.MessagingException;
import javax.mail.Session;
import javax.mail.internet.AddressException;
import javax.mail.internet.ContentType;
import javax.mail.internet.InternetAddress;
import javax.mail.internet.MimeBodyPart;
import javax.mail.internet.MimeMessage;
import javax.mail.internet.MimeMultipart;
import javax.mail.internet.ParseException;
import javax.mail.util.ByteArrayDataSource;

import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import org.exoplatform.commons.api.settings.SettingService;
import org.exoplatform.commons.api.settings.SettingValue;
import org.exoplatform.commons.api.settings.data.Context;
import org.exoplatform.commons.exception.ObjectNotFoundException;
import org.exoplatform.emailConnector.exception.ReadReceiptConflictException;
import org.exoplatform.emailConnector.model.Email;
import org.exoplatform.emailConnector.model.EmailRecipient;
import org.exoplatform.emailConnector.model.MailFolder;
import org.exoplatform.emailConnector.model.ReadReceiptAction;
import org.exoplatform.emailConnector.model.ReadReceiptAnswerOrigin;
import org.exoplatform.emailConnector.model.ReadReceiptPolicy;
import org.exoplatform.emailConnector.model.ReadReceiptPrompt;
import org.exoplatform.emailConnector.model.ReadReceiptSettings;
import org.exoplatform.emailConnector.model.ReadReceiptState;
import org.exoplatform.emailConnector.model.UserEmailSetting;
import org.exoplatform.emailConnector.storage.EmailBoxStorage;
import org.exoplatform.emailConnector.storage.EmailReadReceiptAnswerStorage;
import org.exoplatform.emailConnector.utils.EmailThreadingUtils;
import org.exoplatform.services.log.ExoLogger;
import org.exoplatform.services.log.Log;

import io.meeds.social.util.JsonUtils;

/**
 * Read receipts (RFC 8098 Message Disposition Notifications), phase 1 of EXO-90435:
 * the user's preferences, what the reader is told to do about a message that asks for
 * a receipt, and the answer itself.
 * <p>
 * <b>A receipt only ever leaves on an explicit call to {@link #respond}</b>, which the
 * reader makes when a human has the message on screen -- the banner's "Send receipt",
 * or, under the ALWAYS policy, the display of the message itself. Nothing else in the
 * add-on calls it: not the sync, not the list, not search, not the AI tools reading a
 * mail on the user's behalf. Opening a message through any of those is not a human
 * reading it, and a receipt that says otherwise is a lie told to the sender.
 * <p>
 * The rules are re-checked on the server at every answer, whatever the reader was
 * told: the prompt is advice to the reader, never an authorisation.
 * <p>
 * <b>An answer is final, and outlives the cache.</b> It is recorded in the answer
 * store ({@code EMAIL_READ_RECEIPT_ANSWER}, keyed by user and Message-ID), then on the
 * cached rows of the message and, where the mailbox stores keywords, as
 * {@code $MDNSent} on the server. The store is what makes it final, on every mailbox:
 * the keyword says that the request was answered, never which answer -- an IGNORE sets
 * it too -- so only the store says SENT or IGNORED. A row the sync deletes and
 * re-creates -- a move, an archive, a reset, a message leaving and re-entering the sync
 * window -- comes back with the answer the store holds on every mailbox alike, keyword
 * or none, because the sync aligns it from the store before writing it
 * ({@code alignReadReceiptAnswer}). The prompt reads the store too, as the second line
 * for a row align could not fill: one cached before that alignment existed, or one it
 * left pending because the store was unreachable. Its unique index is the at-most-once
 * decision. The one exception is a message that came with no Message-ID, which has
 * nothing to be recognised by once its row is gone: its answer lives on its rows only,
 * as it did before the store (PO decision of 2026-09-19, EXO-90435).
 */
@Service
public class ReadReceiptService {

  private static final Log     LOG                   = ExoLogger.getLogger(ReadReceiptService.class);

  /**
   * The user's preferences, a document of their own in the setting service -- apart
   * from the mailbox setting, which the sync rewrites whole on every run.
   */
  public static final String   SETTINGS_KEY          = "emailReadReceiptSettings";

  /**
   * The platform switch for the ALWAYS policy, true by default: an administrator who
   * wants every receipt to be a human decision sets it to false, and every stored
   * ALWAYS then behaves as ASK. Read at every use, so no restart is needed.
   */
  public static final String   ALLOW_ALWAYS_PROPERTY = "email.connector.readReceipt.allowAlways";

  /** The message does not ask for a receipt. */
  public static final String   NOT_REQUESTED         = "emailConnector.readReceipt.notRequested";

  /** The request cannot be answered from here: own mail, Junk/Trash, no address, policy. */
  public static final String   NOT_ALLOWED           = "emailConnector.readReceipt.notAllowed";

  /**
   * An automatic answer (the reader sent it on display, under ALWAYS) to a request
   * that is not to be answered automatically any more -- the user's policy, the
   * administrator's switch or the message changed since the reader was told AUTO. The
   * reader shows the banner instead.
   */
  public static final String   ASK_FIRST             = "emailConnector.readReceipt.askFirst";

  /** No action, or one this service does not know. */
  public static final String   INVALID_ACTION        = "emailConnector.readReceipt.invalidAction";

  /** The receipt could not be sent; nothing left, the request stays pending. */
  public static final String   SEND_FAILED           = "emailConnector.readReceipt.sendFailed";

  /**
   * The mail server failed after the receipt may have been accepted. The request is
   * kept answered, so no second receipt can follow the one that may be out.
   */
  public static final String   UNCONFIRMED           = "emailConnector.readReceipt.unconfirmed";

  /** What the report part names as the user agent, after the user's own mail domain. */
  static final String          REPORTING_UA_PRODUCT  = "eXo Email Connector";

  /** Auto-Submitted, RFC 3834, set on a receipt sent without asking. */
  static final String          HEADER_AUTO_SUBMITTED = "Auto-Submitted";

  private static final String  CRLF                  = "\r\n";

  @Autowired
  private EmailBoxService         emailBoxService;

  @Autowired
  private EmailBoxStorage         emailBoxStorage;

  @Autowired
  private UserEmailSettingService userEmailSettingService;

  @Autowired
  private SettingService          settingService;

  @Autowired
  private EmailReadReceiptAnswerStorage answerStorage;

  /**
   * The user's read-receipt preferences, with the defaults (no request by default,
   * ASK) when they never chose, and ALWAYS answered as ASK when the administrator
   * disabled it. Never null.
   *
   * @param username the user
   * @return the effective preferences
   */
  public ReadReceiptSettings getSettings(String username) {
    ReadReceiptSettings stored = null;
    SettingValue<?> value = settingService.get(Context.USER.id(username), UserEmailSettingService.EMAIL_CONNECTOR_SCOPE, SETTINGS_KEY);
    if (value != null && value.getValue() != null) {
      try {
        stored = JsonUtils.fromJsonString(value.getValue().toString(), ReadReceiptSettings.class);
      } catch (Exception e) {
        // Unreadable preferences fall back on the defaults, the most conservative ones.
        LOG.warn("The read-receipt settings of user {} could not be read, using the defaults", username, e);
      }
    }
    boolean alwaysAllowed = isAlwaysAllowed();
    ReadReceiptPolicy policy = stored == null || stored.getResponsePolicy() == null ? ReadReceiptPolicy.ASK
                                                                                     : stored.getResponsePolicy();
    if (policy == ReadReceiptPolicy.ALWAYS && !alwaysAllowed) {
      policy = ReadReceiptPolicy.ASK;
    }
    return new ReadReceiptSettings(stored != null && stored.isRequestByDefault(), policy, alwaysAllowed);
  }

  /**
   * Stores the user's read-receipt preferences. A null policy is stored as ASK; ALWAYS
   * is refused while the administrator disables it, rather than stored and silently
   * not honoured.
   *
   * @param username the user
   * @param settings the preferences; {@code alwaysAllowed} is ignored
   * @return the preferences as they now stand
   * @throws IllegalArgumentException {@link #NOT_ALLOWED} for ALWAYS while it is
   *           disabled, or when no preferences are given
   */
  public ReadReceiptSettings saveSettings(String username, ReadReceiptSettings settings) {
    if (settings == null) {
      throw new IllegalArgumentException(NOT_ALLOWED);
    }
    ReadReceiptPolicy policy = settings.getResponsePolicy() == null ? ReadReceiptPolicy.ASK : settings.getResponsePolicy();
    if (policy == ReadReceiptPolicy.ALWAYS && !isAlwaysAllowed()) {
      throw new IllegalArgumentException(NOT_ALLOWED);
    }
    ReadReceiptSettings stored = new ReadReceiptSettings(settings.isRequestByDefault(), policy, false);
    settingService.set(Context.USER.id(username),
                       UserEmailSettingService.EMAIL_CONNECTOR_SCOPE,
                       SETTINGS_KEY,
                       SettingValue.create(JsonUtils.toJsonString(stored)));
    return getSettings(username);
  }

  /**
   * Whether the administrator allows the ALWAYS policy ({@link #ALLOW_ALWAYS_PROPERTY}).
   *
   * @return true unless it was set to false
   */
  public boolean isAlwaysAllowed() {
    return !"false".equalsIgnoreCase(StringUtils.trim(System.getProperty(ALLOW_ALWAYS_PROPERTY, "true")));
  }

  /**
   * Tells the reader what to do about each message's read-receipt request, and what was
   * already done about it, for the user reading them: sets {@code readReceiptPrompt} and
   * {@code readReceiptAnswer} on every message given. The preferences are read once for
   * the lot, and the answer store once for the pending requests among them: a request
   * answered before its row was re-created (on a mailbox that stores no keywords) reads
   * as answered, is not offered again, and says how it was answered -- which is the whole
   * point of the store.
   * <p>
   * Both fields are set on every message, the answer to null where there is none: a
   * message that carried no request has nothing to say, and neither has an outgoing copy
   * of a message this user sent, whose {@code readReceiptRequested} means "I asked", not
   * "they ask" ({@link #carriesReceivedRequest}).
   *
   * @param emails the messages about to be shown, may be null or hold nulls
   * @param username the user reading them
   */
  public void decorate(Collection<Email> emails, String username) {
    if (emails == null || emails.isEmpty()) {
      return;
    }
    List<Email> pending = new ArrayList<>();
    for (Email email : emails) {
      if (email == null) {
        continue;
      }
      email.setReadReceiptAnswer(null);
      email.setReadReceiptAddress(null);
      if (!carriesReceivedRequest(email)) {
        // The common case, decided without reading anything.
        email.setReadReceiptPrompt(ReadReceiptPrompt.NONE);
      } else if (email.getReadReceiptState() != null) {
        // This copy carries the answer itself: nothing to look up.
        email.setReadReceiptPrompt(ReadReceiptPrompt.NONE);
        email.setReadReceiptAnswer(email.getReadReceiptState());
      } else {
        pending.add(email);
      }
    }
    if (pending.isEmpty()) {
      return;
    }
    ReadReceiptSettings settings = getSettings(username);
    String ownAddress = ownAddress(username);
    Map<String, ReadReceiptState> answered = answerStorage.findAnswers(username,
                                                                       pending.stream().map(Email::getMailHeaderId).toList());
    for (Email email : pending) {
      String key = EmailReadReceiptAnswerStorage.messageIdHash(email.getMailHeaderId());
      ReadReceiptState stored = key == null ? null : answered.get(key);
      if (stored == null) {
        ReadReceiptPrompt prompt = promptFor(email, ownAddress, settings);
        email.setReadReceiptPrompt(prompt);
        if (prompt != ReadReceiptPrompt.NONE) {
          // NONE unless isAnswerable, which requires exactly one address
          email.setReadReceiptAddress(requestedAddresses(email.getReadReceiptTo())[0].getAddress());
        }
      } else {
        // Answered before this copy existed: the store is what remembers it.
        email.setReadReceiptPrompt(ReadReceiptPrompt.NONE);
        email.setReadReceiptAnswer(stored);
      }
    }
  }

  /**
   * {@link #decorate(Collection, String)} for one message.
   *
   * @param email the message, may be null
   * @param username the user reading it
   */
  public void decorate(Email email, String username) {
    if (email != null) {
      decorate(List.of(email), username);
    }
  }

  /**
   * Answers a message's read-receipt request, for the user who owns it.
   * <p>
   * Everything is checked again here, whatever the reader was told. Then the answer is
   * claimed in the database -- in the answer store, keyed by user and Message-ID, and
   * on every cached copy of the message -- which is what makes a receipt leave at most
   * once however many tabs, clicks or nodes race, and whatever became of the cached
   * rows since an earlier answer ({@link #claim}). SEND then transmits the receipt as
   * the user; IGNORE transmits nothing. Either way {@code $MDNSent} is set on the
   * server copy when the mailbox accepts keywords, so the user's other clients do not
   * ask again -- the keyword says that the request was answered, never which answer,
   * so it is the database record that says SENT or IGNORED, on every mailbox alike
   * (EXO-90435: a row re-created from a keyword alone read an IGNORE as a receipt
   * sent). Where the mailbox keeps no keyword, that record is also what keeps the
   * request from being asked again.
   * <p>
   * A receipt that cannot leave gives its claim back, so the user can try again. One
   * whose transmission failed after the mail server may have accepted it keeps its
   * claim: a doubtful receipt is better than two.
   *
   * @param emailId the cached message's technical id
   * @param username the user answering, who must own it
   * @param action SEND or IGNORE
   * @param automatic whether the reader answers on its own (SEND on display, under
   *          ALWAYS) rather than on a click: allowed only while this service decides
   *          AUTO for the message, and what makes the receipt say it was sent
   *          automatically. A click is never automatic, whatever the policy.
   * @throws ObjectNotFoundException when there is no such message of this user
   * @throws IllegalArgumentException {@link #NOT_REQUESTED}, {@link #NOT_ALLOWED},
   *           {@link #ASK_FIRST} or {@link #INVALID_ACTION}
   * @throws ReadReceiptConflictException when the request was already answered
   * @throws IllegalAccessException when the user's mailbox connector is not usable
   * @throws IllegalStateException {@link #SEND_FAILED} or {@link #UNCONFIRMED}
   */
  public void respond(long emailId,
                      String username,
                      ReadReceiptAction action,
                      boolean automatic) throws ObjectNotFoundException, IllegalAccessException {
    if (action == null) {
      throw new IllegalArgumentException(INVALID_ACTION);
    }
    Email email;
    try {
      email = emailBoxService.getOwnedEmailById(emailId, username);
    } catch (IllegalAccessException e) {
      // Somebody else's message is reported as missing: "forbidden" would confirm it exists.
      throw new ObjectNotFoundException("emailConnector.readReceipt.notFound");
    }
    if (email == null) {
      throw new ObjectNotFoundException("emailConnector.readReceipt.notFound");
    }
    if (!email.isReadReceiptRequested()) {
      throw new IllegalArgumentException(NOT_REQUESTED);
    }
    if (email.getReadReceiptState() != null) {
      throw new ReadReceiptConflictException(ReadReceiptConflictException.ALREADY_HANDLED);
    }
    String ownAddress = ownAddress(username);
    if (!isAnswerable(email, ownAddress)) {
      throw new IllegalArgumentException(NOT_ALLOWED);
    }
    ReadReceiptPrompt prompt = promptFor(email, ownAddress, getSettings(username));
    if (action == ReadReceiptAction.SEND && prompt == ReadReceiptPrompt.NONE) {
      // The user's policy is NEVER: a receipt is refused even when asked for by hand,
      // since nothing in the reader offers one.
      throw new IllegalArgumentException(NOT_ALLOWED);
    }
    if (action == ReadReceiptAction.SEND && automatic && prompt != ReadReceiptPrompt.AUTO) {
      // Decided now, never on what the reader was told before: a reader holding an
      // AUTO from before a policy change (or a cached response) must not send without
      // asking. It shows the banner instead.
      throw new IllegalArgumentException(ASK_FIRST);
    }
    ReadReceiptState answer = action == ReadReceiptAction.SEND ? ReadReceiptState.SENT : ReadReceiptState.IGNORED;
    Long answerId = claim(username, email, answer);
    try (EmailBoxService.ServerCopy serverCopy = emailBoxService.openServerCopy(username, email)) {
      if (action == ReadReceiptAction.SEND) {
        sendReceipt(email, username, ownAddress, automatic, serverCopy, answerId);
      }
      serverCopy.addKeyword(EmailBoxService.MDN_SENT_KEYWORD);
    }
  }

  /**
   * Takes the answer to a message's request, or refuses: the at-most-once decision.
   * <p>
   * A message with a Message-ID of its own is decided by the answer store: the insert
   * its unique index lets through once per user and message, whoever races (tabs,
   * nodes, the sync recording the server's keyword). Its cached copies then follow, as
   * a mirror; if one of them turns out answered already -- the sync mirrored another
   * client's keyword a moment ago -- nothing is sent either, and the store keeps the
   * record that the request is answered. That record's STATE and ORIGIN are then this
   * call's, not those of whoever answered: only "answered" is authoritative in it.
   * When the store holds an answer already, the copies still pending are brought in
   * line with it before refusing. A message that came with no Message-ID is decided
   * by its cached copies, as before the store.
   *
   * @param username the user answering
   * @param email the message
   * @param answer the answer
   * @return the id of the store's record, null when the message has none
   * @throws ReadReceiptConflictException when the request was already answered
   */
  private Long claim(String username, Email email, ReadReceiptState answer) {
    String key = EmailReadReceiptAnswerStorage.messageIdHash(email.getMailHeaderId());
    if (key == null) {
      if (!emailBoxStorage.claimReadReceipt(username, email, answer)) {
        throw new ReadReceiptConflictException(ReadReceiptConflictException.ALREADY_HANDLED);
      }
      return null;
    }
    Long answerId = answerStorage.claim(username, email.getMailHeaderId(), answer, ReadReceiptAnswerOrigin.LOCAL, new Date());
    if (answerId == null) {
      ReadReceiptState stored = answerStorage.findAnswers(username, List.of(email.getMailHeaderId())).get(key);
      if (stored != null) {
        emailBoxStorage.claimReadReceipt(username, email, stored);
      }
      throw new ReadReceiptConflictException(ReadReceiptConflictException.ALREADY_HANDLED);
    }
    if (!emailBoxStorage.claimReadReceipt(username, email, answer)) {
      throw new ReadReceiptConflictException(ReadReceiptConflictException.ALREADY_HANDLED);
    }
    return answerId;
  }

  /**
   * Gives back an answer taken by {@link #claim}, when the receipt it was taken for
   * could not leave at all: the cached copies first, then the store's record, so a
   * concurrent answer is refused by the store until the copies are free again.
   *
   * @param username the user
   * @param email the message
   * @param answerId the store's record, null when there is none
   */
  private void release(String username, Email email, Long answerId) {
    emailBoxStorage.releaseReadReceipt(username, email, ReadReceiptState.SENT);
    if (answerId != null) {
      answerStorage.release(username, answerId);
    }
  }

  /**
   * Transmits the receipt of one message, and gives the claim back when it could not
   * leave.
   *
   * @param email the message whose receipt is sent
   * @param username the user sending it
   * @param ownAddress the user's mailbox address, the Final-Recipient
   * @param automatic whether it is sent without asking (ALWAYS)
   * @param serverCopy the server copy, for the Original-Recipient it may carry
   * @param answerId the answer store's record of the claim, null when there is none
   * @throws IllegalAccessException when the user's connector is not usable
   */
  private void sendReceipt(Email email,
                           String username,
                           String ownAddress,
                           boolean automatic,
                           EmailBoxService.ServerCopy serverCopy,
                           Long answerId) throws IllegalAccessException {
    String originalRecipient = serverCopy.header("Original-Recipient");
    InternetAddress[] to = requestedAddresses(email.getReadReceiptTo());
    try {
      emailBoxService.transmitAsUser(username,
                                     (session, from) -> buildReceipt(session,
                                                                     from,
                                                                     to,
                                                                     email,
                                                                     ownAddress,
                                                                     originalRecipient,
                                                                     automatic));
    } catch (IllegalAccessException e) {
      release(username, email, answerId);
      throw e;
    } catch (SmtpTransmitter.TransmissionException e) {
      if (e.getPhase() == SmtpTransmitter.Phase.SEND) {
        // Kept answered, here AND on the server: the user's other clients must not
        // send the second receipt this one refuses to.
        serverCopy.addKeyword(EmailBoxService.MDN_SENT_KEYWORD);
        LOG.warn("The read receipt of a message of user {} may or may not have been sent; it is not sent again", username, e);
        throw new IllegalStateException(UNCONFIRMED, e);
      }
      release(username, email, answerId);
      LOG.warn("The read receipt of a message of user {} could not be sent ({})", username, e.getPhase(), e);
      throw new IllegalStateException(SEND_FAILED, e);
    } catch (RuntimeException e) {
      // Nothing was transmitted: the transmitter's own failures all arrive above,
      // classified, so this is a lookup failing before it. The claim goes back.
      release(username, email, answerId);
      LOG.warn("The read receipt of a message of user {} could not be prepared", username, e);
      throw new IllegalStateException(SEND_FAILED, e);
    }
  }

  /**
   * What the reader should do about one message's request. NONE unless the request is
   * pending and answerable and the user's policy is not NEVER; AUTO only under ALWAYS
   * (allowed by the administrator) and only when every one of these holds -- otherwise
   * ASK: the request names exactly one address and the message's Return-Path is that
   * address; the user is a direct recipient (To or Cc, so neither Bcc-only nor reached
   * through a list); the message came through no mailing list; and nobody's robot
   * wrote it.
   *
   * @param email the message
   * @param ownAddress the user's mailbox address
   * @param settings the user's effective preferences
   * @return the prompt
   */
  ReadReceiptPrompt promptFor(Email email, String ownAddress, ReadReceiptSettings settings) {
    if (!email.isReadReceiptRequested() || email.getReadReceiptState() != null || !isAnswerable(email, ownAddress)
        || settings.getResponsePolicy() == ReadReceiptPolicy.NEVER) {
      return ReadReceiptPrompt.NONE;
    }
    if (settings.getResponsePolicy() == ReadReceiptPolicy.ALWAYS && settings.isAlwaysAllowed()
        && email.isReadReceiptReturnPathMatch()
        && requestedAddresses(email.getReadReceiptTo()).length == 1
        && isDirectRecipient(email, ownAddress)
        && !email.isHasListId() && !email.isHasListPost()
        && !email.isAutoSubmitted()) {
      return ReadReceiptPrompt.AUTO;
    }
    return ReadReceiptPrompt.ASK;
  }

  /**
   * Whether a request may be answered at all, whatever the policy: a message received
   * by this user (never one in Sent, Drafts or the Scheduled view, never one they sent
   * themselves), not in Junk or Trash (answering spam confirms the address is read),
   * naming exactly one address to answer to.
   * <p>
   * Exactly one, on the manual path too: the header is written by the sender, and every
   * address it names becomes a recipient of mail sent from the user's own account. A
   * request naming several is never offered and never answered, so one click cannot
   * send the user's receipt to a list the sender chose. The banner names that one
   * address when it is not the sender's.
   *
   * @param email the message
   * @param ownAddress the user's mailbox address
   * @return true when it may be answered
   */
  private boolean isAnswerable(Email email, String ownAddress) {
    String folder = StringUtils.defaultIfBlank(email.getFolder(), MailFolder.INBOX);
    if (isOutgoing(email) || MailFolder.JUNK.equals(folder) || MailFolder.TRASH.equals(folder)) {
      return false;
    }
    if (email.getSender() != null && StringUtils.isNotBlank(ownAddress)
        && StringUtils.equalsIgnoreCase(StringUtils.trim(email.getSender().getAddress()), ownAddress)) {
      return false;
    }
    return requestedAddresses(email.getReadReceiptTo()).length == 1;
  }

  /**
   * Whether a message carries a request <i>addressed to this user</i>: one somebody else
   * asked them to answer, as opposed to a copy of a message they sent asking for one
   * themselves. What separates the two is the direction, not the flag: the very same
   * {@code readReceiptRequested} reads "they ask" on a received message and "I asked" on
   * the Sent, Drafts and Scheduled copies of an outgoing one.
   * <p>
   * Junk and Trash are <b>not</b> excluded here, unlike in {@link #isAnswerable}: a
   * request is never offered there, but an answer given before the message was moved
   * stays what the user did, and the message keeps saying so wherever it ends up.
   *
   * @param email the message
   * @return true when the request is one this user was asked to answer
   */
  private boolean carriesReceivedRequest(Email email) {
    return email.isReadReceiptRequested() && !isOutgoing(email);
  }

  /**
   * Whether a message is this user's own outgoing copy: in Sent, in Drafts, in the
   * Scheduled view, or a draft being written.
   *
   * @param email the message
   * @return true when it is outgoing
   */
  private boolean isOutgoing(Email email) {
    String folder = StringUtils.defaultIfBlank(email.getFolder(), MailFolder.INBOX);
    return MailFolder.SENT.equals(folder) || MailFolder.DRAFTS.equals(folder) || MailFolder.SCHEDULED.equals(folder)
           || StringUtils.isNotBlank(email.getDraftLocalId());
  }

  /**
   * Whether the user is among the To or Cc recipients of a message. A message whose
   * recipients were not read is taken as not addressed to them -- the cautious answer.
   *
   * @param email the message
   * @param ownAddress the user's mailbox address
   * @return true when the user is a To or Cc recipient
   */
  private boolean isDirectRecipient(Email email, String ownAddress) {
    if (StringUtils.isBlank(ownAddress)) {
      return false;
    }
    return Stream.concat(email.getTo() == null ? Stream.empty() : email.getTo().stream(),
                         email.getCc() == null ? Stream.empty() : email.getCc().stream())
                 .filter(Objects::nonNull)
                 .map(EmailRecipient::getAddress)
                 .anyMatch(address -> StringUtils.equalsIgnoreCase(StringUtils.trim(address), ownAddress));
  }

  /**
   * The user's mailbox address, the one mail is delivered to.
   *
   * @param username the user
   * @return the address, trimmed, or null when the user has no mailbox
   */
  private String ownAddress(String username) {
    UserEmailSetting userEmailSetting = userEmailSettingService.getUserEmailSetting(username);
    return userEmailSetting == null ? null : StringUtils.trimToNull(userEmailSetting.getEmailAddress());
  }

  /**
   * The addresses a request names, parsed leniently; none when it names none or cannot
   * be read. A group ({@code name: a@x, b@y;}) counts as none: the transport expands
   * it into its members, so answering it would send to every one of them.
   *
   * @param readReceiptTo the stored Disposition-Notification-To value
   * @return the addresses, never null
   */
  static InternetAddress[] requestedAddresses(String readReceiptTo) {
    if (StringUtils.isBlank(readReceiptTo)) {
      return new InternetAddress[0];
    }
    try {
      return Stream.of(InternetAddress.parseHeader(readReceiptTo, false))
                   .filter(address -> !address.isGroup())
                   .filter(address -> StringUtils.isNotBlank(address.getAddress()) && address.getAddress().contains("@"))
                   .toArray(InternetAddress[]::new);
    } catch (AddressException e) {
      return new InternetAddress[0];
    }
  }

  /**
   * Builds a read receipt, RFC 8098: a {@code multipart/report;
   * report-type=disposition-notification} message to the requested address(es), with a
   * human-readable part and the machine-readable {@code message/disposition-notification}
   * part -- Reporting-UA, Original-Recipient when the original carried one,
   * Final-Recipient (the user), Original-Message-ID, and the disposition: always
   * "displayed", never "denied", {@code manual-action/MDN-sent-manually} when the user
   * clicked and {@code automatic-action/MDN-sent-automatically} under ALWAYS, which also
   * marks the message {@code Auto-Submitted: auto-replied} (RFC 3834). The subject is
   * "Read: " and the original's; In-Reply-To and References tie it to the original, so
   * the sender's client files it with the conversation. No copy of the original's
   * headers is attached: that part is optional, and this one would repeat to the
   * sender what they already have.
   *
   * @param session the user's SMTP session
   * @param from the user's sending address
   * @param to where the request asks the receipt to go
   * @param original the message being acknowledged
   * @param finalRecipient the user's mailbox address
   * @param originalRecipient the original's Original-Recipient header, may be null
   * @param automatic whether the receipt is sent without asking
   * @return the receipt, ready to transmit
   * @throws MessagingException if it cannot be built
   */
  static MimeMessage buildReceipt(Session session,
                                  InternetAddress from,
                                  InternetAddress[] to,
                                  Email original,
                                  String finalRecipient,
                                  String originalRecipient,
                                  boolean automatic) throws MessagingException {
    if (to == null || to.length == 0) {
      throw new MessagingException("A read receipt needs an address to go to");
    }
    String originalMessageId = quotableMessageId(original.getMailHeaderId());
    String subject = oneLine(StringUtils.defaultString(original.getSubject()));
    MimeMessage receipt = new MimeMessage(session);
    receipt.setFrom(from);
    receipt.setRecipients(Message.RecipientType.TO, to);
    receipt.setSubject("Read: " + subject, StandardCharsets.UTF_8.name());
    receipt.setSentDate(new Date());
    if (originalMessageId != null) {
      receipt.setHeader("In-Reply-To", originalMessageId);
      String references = oneLine(StringUtils.defaultString(original.getMailReferences()));
      receipt.setHeader("References", StringUtils.isBlank(references) ? originalMessageId
                                                                      : references + " " + originalMessageId);
    }
    if (automatic) {
      receipt.setHeader(HEADER_AUTO_SUBMITTED, "auto-replied");
    }
    MimeMultipart report = new ReportMultipart();

    MimeBodyPart text = new MimeBodyPart();
    text.setText(humanReadablePart(finalRecipient, subject), StandardCharsets.UTF_8.name());
    report.addBodyPart(text);

    MimeBodyPart notification = new MimeBodyPart();
    String fields = dispositionFields(from, finalRecipient, originalRecipient, originalMessageId, automatic);
    notification.setDataHandler(new DataHandler(new ByteArrayDataSource(fields.getBytes(StandardCharsets.US_ASCII),
                                                                        "message/disposition-notification")));
    notification.setHeader("Content-Type", "message/disposition-notification");
    notification.setHeader("Content-Transfer-Encoding", "7bit");
    report.addBodyPart(notification);

    receipt.setContent(report);
    receipt.saveChanges();
    return receipt;
  }

  /**
   * The machine-readable fields of a receipt, one per line, CRLF-terminated.
   *
   * @param from the user's sending address, whose domain names the reporting agent
   * @param finalRecipient the user's mailbox address
   * @param originalRecipient the original's Original-Recipient header, may be null
   * @param originalMessageId the original's Message-ID, null when it had none
   * @param automatic whether the receipt is sent without asking
   * @return the fields
   */
  static String dispositionFields(InternetAddress from,
                                  String finalRecipient,
                                  String originalRecipient,
                                  String originalMessageId,
                                  boolean automatic) {
    StringBuilder fields = new StringBuilder();
    String address = from == null ? null : from.getAddress();
    String domain = StringUtils.substringAfterLast(StringUtils.defaultString(address), "@");
    // The user's own mail domain, not this server's name: the receipt tells the sender
    // that their message was displayed, and nothing about the platform it was read on.
    fields.append("Reporting-UA: ")
          .append(StringUtils.isBlank(domain) ? "" : oneLine(domain) + "; ")
          .append(REPORTING_UA_PRODUCT)
          .append(CRLF);
    // Copied only when it is what the field is -- one ASCII line, "rfc822;" and an
    // address -- and dropped otherwise rather than repaired: a value spanning lines was
    // written to add fields of its own to this report.
    String original = StringUtils.trimToEmpty(originalRecipient);
    if (StringUtils.startsWithIgnoreCase(original, "rfc822;") && original.length() > "rfc822;".length()
        && StringUtils.containsNone(original, '\r', '\n') && StandardCharsets.US_ASCII.newEncoder().canEncode(original)) {
      fields.append("Original-Recipient: ").append(original).append(CRLF);
    }
    fields.append("Final-Recipient: rfc822;").append(oneLine(StringUtils.defaultString(finalRecipient))).append(CRLF);
    if (originalMessageId != null) {
      fields.append("Original-Message-ID: ").append(originalMessageId).append(CRLF);
    }
    fields.append("Disposition: ")
          .append(automatic ? "automatic-action/MDN-sent-automatically" : "manual-action/MDN-sent-manually")
          .append("; displayed")
          .append(CRLF);
    return fields.toString();
  }

  /**
   * The sentence a person reads in the receipt. English, whatever the user's language:
   * its reader is the original sender, whose language is not known here.
   *
   * @param finalRecipient the user's mailbox address
   * @param subject the original's subject, on one line
   * @return the text
   */
  private static String humanReadablePart(String finalRecipient, String subject) {
    return "This is a read receipt for the message sent to " + oneLine(StringUtils.defaultString(finalRecipient))
        + (StringUtils.isBlank(subject) ? "" : " with the subject \"" + subject + "\"") + "." + CRLF + CRLF
        + "It means the message was displayed on the recipient's screen. It does not guarantee that it was read or understood."
        + CRLF;
  }

  /**
   * A Message-ID that may be quoted back to the sender: the original's own, never the
   * placeholder this add-on synthesizes for a message that had none.
   *
   * @param mailHeaderId the stored Message-ID
   * @return the id on one line, or null when there is none to quote
   */
  private static String quotableMessageId(String mailHeaderId) {
    String id = StringUtils.trimToNull(oneLine(StringUtils.defaultString(mailHeaderId)));
    return id == null || EmailThreadingUtils.isSynthesizedMessageId(id) ? null : id;
  }

  /**
   * A value with its line breaks turned into spaces: nothing read from a message may
   * start a header line of the receipt.
   *
   * @param value the value
   * @return the value on one line
   */
  private static String oneLine(String value) {
    return value.replaceAll("[\\r\\n]+", " ");
  }

  /**
   * {@code multipart/report; report-type=disposition-notification}: JavaMail builds the
   * subtype, the parameter has to be added to the content type it built.
   */
  static final class ReportMultipart extends MimeMultipart {

    /**
     * A report multipart, with its boundary and report type.
     *
     * @throws MessagingException if the content type cannot be parsed
     */
    ReportMultipart() throws MessagingException {
      super("report");
      try {
        ContentType type = new ContentType(contentType);
        type.setParameter("report-type", "disposition-notification");
        contentType = type.toString();
      } catch (ParseException e) {
        throw new MessagingException("Unparsable report content type", e);
      }
    }
  }
}
