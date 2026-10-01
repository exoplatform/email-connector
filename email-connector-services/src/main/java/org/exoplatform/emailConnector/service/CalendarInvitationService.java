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
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Date;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.activation.DataHandler;
import javax.mail.Message;
import javax.mail.MessagingException;
import javax.mail.Session;
import javax.mail.internet.AddressException;
import javax.mail.internet.InternetAddress;
import javax.mail.internet.MimeBodyPart;
import javax.mail.internet.MimeMessage;
import javax.mail.internet.MimeMultipart;
import javax.mail.util.ByteArrayDataSource;

import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import org.exoplatform.commons.api.settings.SettingService;
import org.exoplatform.commons.api.settings.SettingValue;
import org.exoplatform.commons.api.settings.data.Context;
import org.exoplatform.commons.exception.ObjectNotFoundException;
import org.exoplatform.emailConnector.exception.DelegationRevokedException;
import org.exoplatform.emailConnector.exception.SendModeMissingException;
import org.exoplatform.emailConnector.exception.SendModeUnavailableException;
import org.exoplatform.emailConnector.model.CalendarInvitation;
import org.exoplatform.emailConnector.model.CalendarInvitationAnswer;
import org.exoplatform.emailConnector.model.Email;
import org.exoplatform.emailConnector.model.EmailAttachment;
import org.exoplatform.emailConnector.model.EmailDelegation;
import org.exoplatform.emailConnector.model.FolderRole;
import org.exoplatform.emailConnector.model.InvitationAnswer;
import org.exoplatform.emailConnector.model.MailFolder;
import org.exoplatform.emailConnector.model.ParsedInvitation;
import org.exoplatform.emailConnector.model.SendMode;
import org.exoplatform.emailConnector.model.UserEmailSetting;
import org.exoplatform.emailConnector.utils.CalendarInvitationUtils;
import org.exoplatform.emailConnector.utils.EmailThreadingUtils;
import org.exoplatform.services.log.ExoLogger;
import org.exoplatform.services.log.Log;

import io.meeds.social.util.JsonUtils;

/**
 * Calendar invitations in mail (EXO-90840, iTIP RFC 5546 over iMIP RFC 6047): the event
 * a received mail's iCalendar part describes, as the reader shows it above the body, and
 * the attendee's Accept / Maybe / Decline, sent back to the organiser as a REPLY.
 * <p>
 * The iCalendar part is the one the sync already describes among the message's
 * attachments ({@code text/calendar}, {@code application/ics} or a {@code .ics} file),
 * read from the server when the reader asks for it, capped
 * ({@link #MAX_BYTES_PROPERTY}), and parsed by ical4j -- the library the agenda add-on
 * puts on the server's class path, which this add-on does not ship a second copy of.
 * Without it the reader shows the mail as before and says nothing about an event
 * ({@link #UNSUPPORTED}).
 * <p>
 * <b>An answer leaves only on the user's click</b> ({@link #respond}), over the user's
 * own connector, to the organiser the reader showed -- one plain address, never a
 * group -- and from the mailbox the mail is in: the user's own, or a shared mailbox's
 * owner's when she let the user send in her name (on her behalf preferred, as her
 * otherwise), with a copy in her Sent saying who sent it, as a composed mail has;
 * without that consent the invitation is shown and no answer is offered.
 * Every rule is checked again at the answer, whatever the reader was shown.
 * <p>
 * The answer given is remembered per user, attendee address and event (UID and
 * RECURRENCE-ID) with the SEQUENCE it answered, in the setting service, so the reader
 * says it again when the mail is opened later; an update that raises the sequence asks
 * again. An answer may be changed: each click sends a new REPLY, as other clients do.
 * Three limits are stated, not decided here: an answer is remembered for the user who
 * gave it, so in a shared mailbox its owner and her other delegates do not see it (the
 * owner's Sent copy is their record); remembered answers are never deleted -- one small
 * setting per answered event; and a mail whose whole body is the iCalendar object (a
 * single-part {@code text/calendar}, which iMIP allows and the mainstream clients do not
 * send) carries no part the sync describes, so it shows no event.
 * <p>
 * Nothing lands in a calendar here: the agenda add-on is not a dependency of this one.
 */
@Service
public class CalendarInvitationService {

  /** The most decoded bytes of an iCalendar part the reader parses. */
  public static final String  MAX_BYTES_PROPERTY      = "email.connector.invitation.maxBytes";

  /** The default of {@link #MAX_BYTES_PROPERTY}: 256 KiB, well above a real invitation. */
  public static final long    DEFAULT_MAX_BYTES       = 256L * 1024;

  /** The message is not the user's, or carries no invitation. */
  public static final String  NOT_FOUND               = "emailConnector.invitation.notFound";

  /** The iCalendar part is larger than {@link #MAX_BYTES_PROPERTY}. */
  public static final String  TOO_LARGE               = "emailConnector.invitation.tooLarge";

  /** The iCalendar part could not be parsed, or holds no event. */
  public static final String  UNREADABLE              = "emailConnector.invitation.unreadable";

  /** The iCalendar library is not on the server's class path (no agenda add-on). */
  public static final String  UNSUPPORTED             = "emailConnector.invitation.unsupported";

  /** No answer, or one this service does not know. */
  public static final String  INVALID_ANSWER          = "emailConnector.invitation.invalidAnswer";

  /** The invitation asks for no answer that may be given from here. */
  public static final String  NOT_ANSWERABLE          = "emailConnector.invitation.notAnswerable";

  /** The event was cancelled: there is nothing left to answer. */
  public static final String  CANCELLED               = "emailConnector.invitation.cancelled";

  /** The shared mailbox's owner has not let the user send in her name. */
  public static final String  SEND_NOT_ALLOWED        = "emailConnector.invitation.sendNotAllowed";

  /** The answer could not be sent; nothing left. */
  public static final String  SEND_FAILED             = "emailConnector.invitation.sendFailed";

  /** The mail server failed after the answer may have been accepted. */
  public static final String  UNCONFIRMED             = "emailConnector.invitation.unconfirmed";

  /** The highest cap an administrator may set: 64 MiB, far above any calendar. */
  static final long           MAX_CAP                 = 64L * 1024 * 1024;

  /** How many attendees the reader lists at most; the count says how many there are. */
  static final int            MAX_ATTENDEES           = 50;

  /** The prefix of the setting key an answer is remembered under. */
  static final String         ANSWER_KEY_PREFIX       = "emailInvitationAnswer.";

  /** The logger. */
  private static final Log    LOG                     = ExoLogger.getLogger(CalendarInvitationService.class);

  /** The ical4j version this code is written against, the one the agenda add-on ships. */
  private static final String ICAL4J_VERSION          = "3.2.x";

  /** Whether the missing library was already said at WARN. */
  private static final AtomicBoolean LIBRARY_MISSING_LOGGED = new AtomicBoolean();

  /** A mail line's end. */
  private static final String CRLF                    = "\r\n";

  /** Reads the iCalendar part and transmits the answer. */
  @Autowired
  private EmailBoxService         emailBoxService;

  /** The user's mailbox address. */
  @Autowired
  private UserEmailSettingService userEmailSettingService;

  /** The shared mailbox a message is in, and its owner's consent. */
  @Autowired
  private EmailDelegationService  emailDelegationService;

  /** Where the answers given are remembered. */
  @Autowired
  private SettingService          settingService;

  /**
   * The invitation a message of the user carries, described for the reader.
   *
   * @param emailId the cached message's technical id
   * @param username the user reading it, who must own the cached row
   * @return the invitation
   * @throws ObjectNotFoundException {@link #NOT_FOUND} when the message is not the
   *           user's, or carries no iCalendar part, or no longer has it on the server
   * @throws IllegalAccessException when the user's connector is not usable
   * @throws IllegalArgumentException {@link #TOO_LARGE}, {@link #UNREADABLE} or
   *           {@link #UNSUPPORTED}
   * @throws IllegalStateException when the mailbox cannot be read
   */
  public CalendarInvitation getInvitation(long emailId, String username) throws ObjectNotFoundException, IllegalAccessException {
    Email email = ownedEmail(emailId, username);
    return read(email, username).invitation();
  }

  /**
   * Answers an invitation: sends the attendee's REPLY to its organiser, then remembers
   * the answer. Everything the reader was shown is decided again here.
   *
   * @param emailId the cached message's technical id
   * @param username the user answering, who must own the cached row
   * @param answer the answer
   * @return the invitation, with the answer just given
   * @throws ObjectNotFoundException {@link #NOT_FOUND}
   * @throws IllegalAccessException when the user's connector is not usable, or
   *           {@link #SEND_NOT_ALLOWED} for a shared mailbox whose owner did not let the
   *           user send in her name
   * @throws IllegalArgumentException {@link #INVALID_ANSWER}, {@link #NOT_ANSWERABLE},
   *           {@link #CANCELLED}, {@link #TOO_LARGE}, {@link #UNREADABLE},
   *           {@link #UNSUPPORTED}, or the shared mailbox's send refusal codes
   * @throws IllegalStateException {@link #SEND_FAILED} or {@link #UNCONFIRMED}
   */
  public CalendarInvitation respond(long emailId,
                                    String username,
                                    InvitationAnswer answer) throws ObjectNotFoundException, IllegalAccessException {
    if (answer == null) {
      throw new IllegalArgumentException(INVALID_ANSWER);
    }
    Email email = ownedEmail(emailId, username);
    ParsedInvitation parsed = read(email, username);
    CalendarInvitation invitation = parsed.invitation();
    if (invitation.isCancelled()) {
      throw new IllegalArgumentException(CANCELLED);
    }
    if (invitation.getAnswerRefusal() != null) {
      throw new IllegalAccessException(invitation.getAnswerRefusal());
    }
    if (!invitation.isAnswerable()) {
      throw new IllegalArgumentException(NOT_ANSWERABLE);
    }
    InternetAddress organizer = organizerAddress(invitation);
    EmailDelegation delegation = emailDelegationService.delegationOf(username, email.getFolder());
    SendMode mode = delegation == null ? null : sendMode(username, delegation);
    if (delegation != null && mode == null) {
      throw new IllegalAccessException(SEND_NOT_ALLOWED);
    }
    EmailBoxService.OutgoingMessageFactory factory = new EmailBoxService.OutgoingMessageFactory() {
      @Override
      public MimeMessage build(Session session, InternetAddress from) throws MessagingException {
        return build(session, from, null);
      }

      @Override
      public MimeMessage build(Session session, InternetAddress from, InternetAddress sender) throws MessagingException {
        return buildReply(session, from, organizer, email, parsed, answer, sender == null ? null : sender.getAddress());
      }
    };
    try {
      if (delegation == null) {
        emailBoxService.transmitAsUser(username, factory);
      } else {
        emailBoxService.transmitFromSharedMailbox(username, delegation.getId(), mode, factory);
      }
    } catch (SendModeMissingException | DelegationRevokedException e) {
      // The owner withdrew her consent, or the share, since the invitation was read.
      throw new IllegalAccessException(SEND_NOT_ALLOWED);
    } catch (SmtpTransmitter.TransmissionException e) {
      if (e.getPhase() != SmtpTransmitter.Phase.SEND) {
        LOG.warn("The answer of user {} to an invitation could not be sent ({})", username, e.getPhase(), e);
        throw new IllegalStateException(SEND_FAILED, e);
      }
      // It may be out: remembered, so the reader does not invite a second one.
      remember(username, parsed, answer);
      LOG.warn("The answer of user {} to an invitation may or may not have been sent", username, e);
      throw new IllegalStateException(UNCONFIRMED, e);
    }
    remember(username, parsed, answer);
    invitation.setAnswer(answer);
    return invitation;
  }

  /**
   * The iCalendar part of a message: the first {@code text/calendar} part, else the first
   * {@code application/ics} part or {@code .ics} file, as the sync described the
   * message's parts.
   *
   * @param email the message, with its attachments
   * @return the part, or null when it carries none
   */
  static EmailAttachment calendarPart(Email email) {
    List<EmailAttachment> attachments = email.getContent() == null ? null : email.getContent().getAttachments();
    if (attachments == null) {
      return null;
    }
    EmailAttachment fallback = null;
    for (EmailAttachment attachment : attachments) {
      if (attachment == null || StringUtils.isBlank(attachment.getAttachmentRemoteId())) {
        continue;
      }
      String type = StringUtils.lowerCase(StringUtils.trimToEmpty(attachment.getMimeType()), Locale.ROOT);
      if ("text/calendar".equals(type)) {
        return attachment;
      }
      if (fallback == null && ("application/ics".equals(type)
          || StringUtils.endsWithIgnoreCase(StringUtils.trimToEmpty(attachment.getName()), ".ics"))) {
        fallback = attachment;
      }
    }
    return fallback;
  }

  /**
   * The user's cached message, or "not found" for anybody else's: "forbidden" would
   * confirm it exists.
   *
   * @param emailId the technical id
   * @param username the user
   * @return the message
   * @throws ObjectNotFoundException when it is not the user's, or there is none
   */
  private Email ownedEmail(long emailId, String username) throws ObjectNotFoundException {
    Email email;
    try {
      email = emailBoxService.getOwnedEmailById(emailId, username);
    } catch (IllegalAccessException e) {
      throw new ObjectNotFoundException(NOT_FOUND);
    }
    if (email == null) {
      throw new ObjectNotFoundException(NOT_FOUND);
    }
    return email;
  }

  /**
   * Reads, parses and describes a message's invitation for the user, with the address
   * they answer for, the answer they gave, and whether they may answer.
   *
   * @param email the user's message
   * @param username the user
   * @return the invitation, decorated for the user
   * @throws ObjectNotFoundException when there is no iCalendar part any more
   * @throws IllegalAccessException when the user's connector is not usable
   */
  private ParsedInvitation read(Email email, String username) throws ObjectNotFoundException, IllegalAccessException {
    EmailAttachment part = calendarPart(email);
    if (part == null) {
      throw new ObjectNotFoundException(NOT_FOUND);
    }
    byte[] content;
    try {
      content = emailBoxService.readMessagePart(username, email, part.getAttachmentRemoteId(), maxBytes());
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException(TOO_LARGE);
    }
    if (content == null) {
      throw new ObjectNotFoundException(NOT_FOUND);
    }
    EmailDelegation delegation = emailDelegationService.delegationOf(username, email.getFolder());
    String mailboxAddress = delegation == null ? ownAddress(username) : delegation.getOwnerMailbox();
    ParsedInvitation parsed;
    try {
      parsed = CalendarInvitationUtils.parseInvitation(content, mailboxAddress, MAX_ATTENDEES);
    } catch (LinkageError e) {
      // Said once at WARN: the feature is off for everybody until the library is back.
      if (LIBRARY_MISSING_LOGGED.compareAndSet(false, true)) {
        LOG.warn("Calendar invitations in mail are not shown as events: the iCalendar library ical4j {}, which the agenda add-on "
            + "ships in the server's lib folder, is missing or incompatible", ICAL4J_VERSION, e);
      } else {
        LOG.debug("The iCalendar library is not available; an invitation is not shown as an event", e);
      }
      throw new IllegalArgumentException(UNSUPPORTED);
    } catch (Exception e) {
      // The sender's content: not an incident.
      LOG.debug("The invitation of a message of user {} could not be parsed", username, e);
      throw new IllegalArgumentException(UNREADABLE);
    }
    CalendarInvitation invitation = parsed.invitation();
    if (invitation.getAttendeeAddress() == null) {
      invitation.setAttendeeAddress(mailboxAddress);
    }
    CalendarInvitationAnswer given = rememberedAnswer(username, invitation, parsed.recurrenceId());
    if (given != null && given.getAnswer() != null && given.getSequence() >= invitation.getSequence()) {
      invitation.setAnswer(given.getAnswer());
    }
    if (asksForAnswer(email, invitation, delegation, username)) {
      boolean mayAnswer = delegation == null || sendMode(username, delegation) != null;
      invitation.setAnswerable(mayAnswer);
      invitation.setAnswerRefusal(mayAnswer ? null : SEND_NOT_ALLOWED);
    }
    return parsed;
  }

  /**
   * Whether an invitation asks the user for an answer that may be given from where the
   * message is: a REQUEST, not cancelled, with a UID and an organiser who is a mail
   * address other than the user's; in a received message -- never Sent, Drafts or the
   * Scheduled view, and never Junk or Trash, where answering confirms to a spammer that
   * the address is read -- of the user's mailbox, or of a shared one's Inbox or own
   * folders alike.
   *
   * @param email the message
   * @param invitation the invitation
   * @param delegation the share the message is in, null for the user's own mailbox
   * @param username the user
   * @return true when Accept / Maybe / Decline may be offered
   */
  private boolean asksForAnswer(Email email, CalendarInvitation invitation, EmailDelegation delegation, String username) {
    if (!CalendarInvitationUtils.METHOD_REQUEST.equals(invitation.getMethod()) || invitation.isCancelled()
        || StringUtils.isBlank(invitation.getUid()) || invitation.getOrganizer() == null
        || invitation.getOrganizer().getAddress() == null
        || StringUtils.equalsIgnoreCase(invitation.getOrganizer().getAddress(), invitation.getAttendeeAddress())
        || StringUtils.isBlank(invitation.getAttendeeAddress())) {
      return false;
    }
    String folder = StringUtils.defaultIfBlank(email.getFolder(), MailFolder.INBOX);
    if (StringUtils.isNotBlank(email.getDraftLocalId()) || email.isScheduled()) {
      return false;
    }
    if (delegation == null) {
      return !List.of(MailFolder.SENT, MailFolder.DRAFTS, MailFolder.SCHEDULED, MailFolder.JUNK, MailFolder.TRASH)
                  .contains(folder);
    }
    for (FolderRole role : List.of(FolderRole.SENT, FolderRole.DRAFTS, FolderRole.JUNK, FolderRole.TRASH)) {
      if (folder.equals(emailDelegationService.roleFolderKey(username, delegation.getId(), role))) {
        return false;
      }
    }
    return true;
  }

  /**
   * The shape an answer from a shared mailbox goes out in: on the owner's behalf when she
   * allows it, else as her; null when she allows neither, or sending in her name cannot
   * be used now. Decided again by {@link EmailBoxService#transmitFromSharedMailbox} at
   * the send.
   *
   * @param username the delegate
   * @param delegation the share
   * @return the shape, or null
   */
  private SendMode sendMode(String username, EmailDelegation delegation) {
    for (SendMode mode : List.of(SendMode.ON_BEHALF, SendMode.AS)) {
      try {
        emailDelegationService.checkSendMode(username, delegation.getId(), mode);
        return mode;
      } catch (SendModeMissingException | SendModeUnavailableException | ObjectNotFoundException e) {
        LOG.debug("User {} may not answer an invitation of a shared mailbox in mode {}", username, mode, e);
      } catch (RuntimeException e) {
        // A share revoked meanwhile, among others: no answer from it.
        LOG.debug("User {} may not answer an invitation of a shared mailbox", username, e);
        return null;
      }
    }
    return null;
  }

  /**
   * The organiser an answer goes to: one plain address, parsed strictly. A group, a list
   * or anything that is not one address is refused: the address is the sender's, and
   * the transport would expand a group to every member.
   *
   * @param invitation the invitation
   * @return the address
   * @throws IllegalArgumentException {@link #NOT_ANSWERABLE} when it is not one address
   */
  static InternetAddress organizerAddress(CalendarInvitation invitation) {
    String address = invitation.getOrganizer() == null ? null : invitation.getOrganizer().getAddress();
    try {
      InternetAddress[] parsed = InternetAddress.parse(StringUtils.defaultString(address), true);
      if (parsed.length != 1 || parsed[0].isGroup() || !StringUtils.equalsIgnoreCase(parsed[0].getAddress(), address)) {
        throw new IllegalArgumentException(NOT_ANSWERABLE);
      }
      parsed[0].validate();
      return parsed[0];
    } catch (AddressException e) {
      throw new IllegalArgumentException(NOT_ANSWERABLE, e);
    }
  }

  /**
   * Builds the answer mail (iMIP, RFC 6047): to the organiser, "Accepted: {title}" and
   * the like, a short text for a person and the REPLY as {@code text/calendar;
   * method=REPLY}, both alternatives of one {@code multipart/alternative}. In-Reply-To
   * and References tie it to the invitation.
   *
   * @param session the user's SMTP session
   * @param from the address it is from: the user's, or the shared mailbox owner's
   * @param organizer the organiser
   * @param email the invitation's message
   * @param parsed the invitation
   * @param answer the answer
   * @param sentBy the delegate's address when answering on the owner's behalf, else null
   * @return the mail
   * @throws MessagingException if it cannot be built
   */
  private MimeMessage buildReply(Session session,
                                 InternetAddress from,
                                 InternetAddress organizer,
                                 Email email,
                                 ParsedInvitation parsed,
                                 InvitationAnswer answer,
                                 String sentBy) throws MessagingException {
    CalendarInvitation invitation = parsed.invitation();
    String attendee = StringUtils.defaultIfBlank(invitation.getAttendeeAddress(), from.getAddress());
    String ics;
    try {
      ics = parsed.replyWriter().write(attendee, from.getPersonal(), answer, sentBy);
    } catch (java.io.IOException e) {
      throw new MessagingException("The reply to the invitation could not be written", e);
    }
    String summary = StringUtils.defaultString(invitation.getSummary());
    // The Message-ID names the domain of the address the answer is from, as a composed
    // mail in a shared mailbox owner's name does: JavaMail's own would name this
    // server's host, on the organiser's copy and on the owner's.
    String replyMessageId = "<" + UUID.randomUUID() + "@"
        + StringUtils.defaultIfBlank(StringUtils.substringAfterLast(StringUtils.defaultString(from.getAddress()), "@"), "email-connector")
        + ">";
    MimeMessage reply = new MimeMessage(session) {
      @Override
      protected void updateMessageID() throws MessagingException {
        setHeader("Message-ID", replyMessageId);
      }
    };
    reply.setFrom(from);
    reply.setRecipient(Message.RecipientType.TO, organizer);
    reply.setSubject(answer.getSubjectPrefix() + ": " + summary, StandardCharsets.UTF_8.name());
    reply.setSentDate(new Date());
    String messageId = StringUtils.trimToNull(email.getMailHeaderId());
    if (messageId != null && !EmailThreadingUtils.isSynthesizedMessageId(messageId)) {
      reply.setHeader("In-Reply-To", messageId);
      reply.setHeader("References", messageId);
    }
    MimeMultipart alternative = new MimeMultipart("alternative");
    MimeBodyPart text = new MimeBodyPart();
    text.setText(humanReadablePart(from, answer, summary), StandardCharsets.UTF_8.name());
    alternative.addBodyPart(text);
    MimeBodyPart calendar = new MimeBodyPart();
    calendar.setDataHandler(new DataHandler(new ByteArrayDataSource(ics.getBytes(StandardCharsets.UTF_8),
                                                                    "text/calendar; method=REPLY; charset=UTF-8")));
    calendar.setHeader("Content-Type", "text/calendar; method=REPLY; charset=UTF-8");
    alternative.addBodyPart(calendar);
    reply.setContent(alternative);
    reply.saveChanges();
    return reply;
  }

  /**
   * The sentence a person reads in the answer. English, whatever the user's language:
   * its reader is the organiser.
   *
   * @param from who answers
   * @param answer the answer
   * @param summary the event's title
   * @return the text
   */
  private static String humanReadablePart(InternetAddress from, InvitationAnswer answer, String summary) {
    String who = StringUtils.defaultIfBlank(from.getPersonal(), from.getAddress());
    String verb = switch (answer) {
      case ACCEPTED -> "accepted";
      case TENTATIVE -> "tentatively accepted";
      case DECLINED -> "declined";
    };
    return who + " has " + verb + " this invitation" + (StringUtils.isBlank(summary) ? "" : ": " + summary) + "." + CRLF;
  }

  /**
   * The answer the user gave to an event from here, when there is one.
   *
   * @param username the user
   * @param invitation the invitation, for its attendee address and UID
   * @param recurrenceId the occurrence's RECURRENCE-ID, null for the series
   * @return the answer, or null
   */
  private CalendarInvitationAnswer rememberedAnswer(String username, CalendarInvitation invitation, String recurrenceId) {
    String key = answerKey(invitation.getAttendeeAddress(), invitation.getUid(), recurrenceId);
    if (key == null) {
      return null;
    }
    try {
      SettingValue<?> value = settingService.get(Context.USER.id(username), UserEmailSettingService.EMAIL_CONNECTOR_SCOPE, key);
      return value == null || value.getValue() == null ? null
                                                       : JsonUtils.fromJsonString(value.getValue().toString(),
                                                                                  CalendarInvitationAnswer.class);
    } catch (RuntimeException e) {
      LOG.debug("The answer of user {} to an invitation could not be read", username, e);
      return null;
    }
  }

  /**
   * Remembers the answer the user just gave. Best effort: the answer is out, and a
   * failure to remember it only means the reader will not say it next time.
   *
   * @param username the user
   * @param parsed the invitation
   * @param answer the answer
   */
  private void remember(String username, ParsedInvitation parsed, InvitationAnswer answer) {
    CalendarInvitation invitation = parsed.invitation();
    String key = answerKey(invitation.getAttendeeAddress(), invitation.getUid(), parsed.recurrenceId());
    if (key == null) {
      return;
    }
    try {
      settingService.set(Context.USER.id(username),
                         UserEmailSettingService.EMAIL_CONNECTOR_SCOPE,
                         key,
                         SettingValue.create(JsonUtils.toJsonString(new CalendarInvitationAnswer(answer,
                                                                                                 invitation.getSequence(),
                                                                                                 System.currentTimeMillis()))));
    } catch (RuntimeException e) {
      LOG.warn("The answer of user {} to an invitation was sent and could not be remembered", username, e);
    }
  }

  /**
   * The key an answer is remembered under: a hash of the attendee address, the UID and
   * the RECURRENCE-ID, so the key stays short and says nothing of the event.
   *
   * @param attendeeAddress the address answered for
   * @param uid the event's UID
   * @param recurrenceId the occurrence's RECURRENCE-ID, null for the series
   * @return the key, or null without an address or a UID
   */
  static String answerKey(String attendeeAddress, String uid, String recurrenceId) {
    if (StringUtils.isBlank(attendeeAddress) || StringUtils.isBlank(uid)) {
      return null;
    }
    String identity = attendeeAddress.trim().toLowerCase(Locale.ROOT) + "\n" + uid.trim() + "\n"
        + StringUtils.trimToEmpty(recurrenceId);
    try {
      byte[] digest = MessageDigest.getInstance("SHA-256").digest(identity.getBytes(StandardCharsets.UTF_8));
      return ANSWER_KEY_PREFIX + HexFormat.of().formatHex(digest);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 is not available", e);
    }
  }

  /**
   * The user's mailbox address.
   *
   * @param username the user
   * @return the address, trimmed, or null when the user has no mailbox
   */
  private String ownAddress(String username) {
    UserEmailSetting setting = userEmailSettingService.getUserEmailSetting(username);
    return setting == null ? null : StringUtils.trimToNull(setting.getEmailAddress());
  }

  /**
   * The most bytes of an iCalendar part the reader parses ({@link #MAX_BYTES_PROPERTY}),
   * read at every use.
   *
   * @return the cap, the default when the property is not a positive number, at most
   *         {@link #MAX_CAP}
   */
  static long maxBytes() {
    try {
      long value = Long.parseLong(StringUtils.trim(System.getProperty(MAX_BYTES_PROPERTY, String.valueOf(DEFAULT_MAX_BYTES))));
      // Bounded so the reader's arithmetic on it (twice the cap, the cap plus one) cannot overflow.
      return value > 0 ? Math.min(value, MAX_CAP) : DEFAULT_MAX_BYTES;
    } catch (NumberFormatException e) {
      return DEFAULT_MAX_BYTES;
    }
  }
}
