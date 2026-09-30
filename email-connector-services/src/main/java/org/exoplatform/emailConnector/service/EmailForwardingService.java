/**
 * Copyright (C) 2026 eXo Platform SAS.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License
 * as published by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program. If not, see <gnu.org/licenses>.
 */
package org.exoplatform.emailConnector.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.TreeSet;

import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.web.util.HtmlUtils;

import org.exoplatform.commons.api.notification.NotificationContext;
import org.exoplatform.commons.api.notification.model.PluginKey;
import org.exoplatform.commons.api.notification.plugin.NotificationPluginUtils;
import org.exoplatform.commons.api.settings.SettingService;
import org.exoplatform.commons.api.settings.SettingValue;
import org.exoplatform.commons.api.settings.data.Context;
import org.exoplatform.commons.exception.ObjectNotFoundException;
import org.exoplatform.commons.notification.impl.NotificationContextImpl;
import org.exoplatform.emailConnector.exception.ForwardingRefusedException;
import org.exoplatform.emailConnector.exception.ServerRuleConflictException;
import org.exoplatform.emailConnector.exception.ServerRuleUnavailableException;
import org.exoplatform.emailConnector.exception.ServerRuleUnsupportedException;
import org.exoplatform.emailConnector.model.EmailConnector;
import org.exoplatform.emailConnector.model.ForwardingAuthoring;
import org.exoplatform.emailConnector.model.ForwardingCodeSent;
import org.exoplatform.emailConnector.model.ForwardingDestination;
import org.exoplatform.emailConnector.model.ForwardingSetting;
import org.exoplatform.emailConnector.model.ForwardingState;
import org.exoplatform.emailConnector.model.ForwardingStatus;
import org.exoplatform.emailConnector.model.ServerForwarding;
import org.exoplatform.emailConnector.model.ServerRule;
import org.exoplatform.emailConnector.model.ServerRuleCapabilities;
import org.exoplatform.emailConnector.model.ServerRuleCapabilities.ElementSupport;
import org.exoplatform.emailConnector.model.UserEmailSetting;
import org.exoplatform.emailConnector.notification.plugin.EmailForwardingNotificationPlugin;
import org.exoplatform.emailConnector.notification.plugin.EmailForwardingNotificationPlugin.Change;
import org.exoplatform.emailConnector.service.acl.MailboxAclSession;
import org.exoplatform.emailConnector.service.rules.ForwardingGuard;
import org.exoplatform.emailConnector.service.rules.ServerRuleEngine;
import org.exoplatform.emailConnector.service.rules.ServerRuleEngineRegistry;
import org.exoplatform.emailConnector.service.rules.sieve.ExoSieveScript;
import org.exoplatform.emailConnector.utils.NotificationConstants;
import org.exoplatform.services.log.ExoLogger;
import org.exoplatform.services.log.Log;
import org.exoplatform.services.mail.MailService;
import org.exoplatform.services.mail.Message;
import org.exoplatform.services.resources.ResourceBundleService;

import io.meeds.social.util.JsonUtils;

/**
 * Forwarding the user's own mail, set from eXo, behind layered safeguards (EXO-90656). A
 * forward is exfiltration by construction -- every future mail leaves the mailbox at
 * delivery -- so none of these stands alone:
 * <ul>
 * <li><b>The deployment enables it per connector</b> ({@link ForwardingGuard}), off by
 * default; off, nothing new is set from eXo, and the deployment-wide key set to false
 * switches it off everywhere at once.</li>
 * <li><b>Destinations are limited to allowed domains</b>, by default the domain of the
 * user's own mailbox, checked here and again by the generator that writes the
 * {@code redirect}.</li>
 * <li><b>A copy always stays in the mailbox</b>: {@code redirect :copy} on Sieve, never a
 * plain redirect, and none at all on a server without {@code copy};
 * {@code localCopy} on BlueMind.</li>
 * <li><b>The destination proves it is reachable and controlled</b>: a short-lived code is
 * sent to it by the platform's own mail service, and nothing forwards to it before the
 * code is entered; a confirmed destination can then be reused. The pending code is kept
 * hashed and salted with its expiry, sends are limited per hour and tries per code, and
 * a code works once.</li>
 * <li><b>Every change is told to the owner</b>, set, changed or removed, a rule that
 * forwards saved or removed, and a forward eXo did not set found on the server: an eXo
 * notification, and a mail into the mailbox itself from the platform's sender -- the one
 * channel a session cannot switch off from eXo's notification settings.</li>
 * <li><b>A band stays in the mailbox while a forward is on</b>, from a status eXo caches
 * ({@link #getStatus}).</li>
 * </ul>
 * Every state this service keeps -- the pending code, the confirmed destinations, the
 * status the band reads, what was last seen of a foreign forward and of the rules that
 * forward -- lives in the {@code GLOBAL} setting context under the user's name, never in
 * the user's own context, which the platform's settings REST lets a session write: a
 * safeguard a session could rewrite for itself would prove nothing. No table is kept:
 * the log lines and the notifications are the history.
 * <p>
 * A second proof of identity before any change ({@link ForwardingStepUp}) plugs in when a
 * bean implements it.
 */
@Service
public class EmailForwardingService {

  /** The pending confirmation's count of wrong codes typed. */
  private static final String FIELD_ATTEMPTS = "attempts";

  /** The pending confirmation's times a code was sent, for the send limit. */
  private static final String FIELD_SENDS    = "sends";

  private static final Log         LOG                     = ExoLogger.getLogger(EmailForwardingService.class);

  /** How long a confirmation code can be entered, in seconds. */
  public static final String       CODE_TTL_PROPERTY       = "email.connector.forwarding.code.ttlSeconds";

  /** How many codes one user may have sent in an hour. */
  public static final String       CODE_MAX_SENDS_PROPERTY = "email.connector.forwarding.code.maxPerHour";

  /** How many wrong tries one code survives. */
  public static final String       CODE_MAX_TRIES_PROPERTY = "email.connector.forwarding.code.maxAttempts";

  /** The default of {@value #CODE_TTL_PROPERTY}: fifteen minutes. */
  public static final long         DEFAULT_CODE_TTL        = 900;

  /** The default of {@value #CODE_MAX_SENDS_PROPERTY}. */
  public static final int          DEFAULT_MAX_SENDS       = 3;

  /** The default of {@value #CODE_MAX_TRIES_PROPERTY}. */
  public static final int          DEFAULT_MAX_TRIES       = 5;

  /** The least time between two codes, in milliseconds. */
  static final long                MIN_SEND_INTERVAL       = 60_000L;

  /** The code's number of digits. */
  static final int                 CODE_DIGITS             = 6;

  /** No code of this user's is waiting for that destination, or the code is wrong. */
  public static final String       CODE_INVALID            = "emailConnector.forwarding.code.invalid";

  /** The code expired; a new one must be asked for. */
  public static final String       CODE_EXPIRED            = "emailConnector.forwarding.code.expired";

  /** Too many codes were asked for in the last hour, or the last one was too recent. */
  public static final String       CODE_TOO_MANY_SENDS     = "emailConnector.forwarding.code.tooMany";

  /** The code was tried wrong too many times; a new one must be asked for. */
  public static final String       CODE_TOO_MANY_TRIES     = "emailConnector.forwarding.code.tooManyAttempts";

  /** The platform could not send the code. */
  public static final String       CODE_NOT_SENT           = "emailConnector.forwarding.code.notSent";

  /** A filter that forwards must run on the mail server. */
  public static final String       SERVER_ONLY             = "emailConnector.forwarding.serverOnly";

  /** The key prefix of a user's pending code. */
  static final String              PENDING_KEY_PREFIX      = "pending.";

  /** The key prefix of a user's cached status. */
  static final String              STATUS_KEY_PREFIX       = "status.";

  /** The key prefix of what was last seen of a forward eXo did not set. */
  static final String              FOREIGN_KEY_PREFIX      = "foreign.";

  /** The fingerprint's prefix of another client's script that may forward. */
  static final String              SCRIPT_FINGERPRINT      = "script:";

  /** The fingerprint's prefix of a server forward eXo did not set. */
  static final String              SERVER_FINGERPRINT      = "server:";

  /** The key prefix of the forward eXo last set on an engine without a script (BlueMind). */
  static final String              WRITTEN_KEY_PREFIX      = "written.";

  /** The key prefix of the rules that forwarded when last written. */
  static final String              RULES_KEY_PREFIX        = "rules.";

  /** The subject of the confirmation mail. */
  static final String              CODE_MAIL_SUBJECT_KEY   = "emailForwarding.code.mail.subject";

  /** The body of the confirmation mail: {0} the code, {1} the requester, {2} minutes. */
  static final String              CODE_MAIL_BODY_KEY      = "emailForwarding.code.mail.body";

  /** The subject of the mail dropped into the mailbox on a change. */
  static final String              OWNER_MAIL_SUBJECT_KEY  = "emailForwarding.owner.mail.subject";

  /** The advice under the change in the mailbox's mail. */
  static final String              OWNER_MAIL_ADVICE_KEY   = "emailForwarding.owner.mail.advice";

  /** The locks of the pending codes, a user's by the hash of the user name. */
  private static final Object[]    LOCKS                   = newLocks(64);

  /** Where codes and salts come from. */
  private static final SecureRandom RANDOM                 = new SecureRandom();

  @Autowired
  private UserEmailSettingService  userEmailSettingService;

  @Autowired
  private EmailConnectorService    emailConnectorService;

  @Autowired
  private EmailDelegationService   emailDelegationService;

  @Autowired
  private ServerRuleEngineRegistry serverRuleEngineRegistry;

  @Autowired
  private SettingService           settingService;

  @Autowired
  private ForwardingGuard          forwardingGuard;

  @Autowired
  private MailService              mailService;

  @Autowired
  private ResourceBundleService    resourceBundleService;

  /** The second proof of identity, a re-entry of the mailbox password; none ships. */
  @Autowired(required = false)
  private ForwardingStepUp         stepUp;

  private Clock                    clock                   = Clock.systemUTC();

  /**
   * Replaces the clock.
   *
   * @param newClock the clock
   */
  void setClock(Clock newClock) {
    this.clock = newClock;
  }

  /**
   * Whether the caller may set a forward from eXo, and within which bounds.
   *
   * @param username the caller, from the request's session
   * @param delegationId the share the request was made from; any value is refused
   * @return the bounds
   * @throws ObjectNotFoundException when the feature is off, or no mailbox is connected
   * @throws IllegalAccessException when the request comes from someone else's mailbox, or
   *           the caller may not use their connector
   */
  public ForwardingAuthoring getAuthoring(String username, Long delegationId) throws ObjectNotFoundException, IllegalAccessException {
    Mailbox mailbox = mailboxOf(username, delegationId);
    return authoring(username, mailbox.connector(), mailbox.address(), null);
  }

  /**
   * The bounds of forwarding for a caller, the server's answer included when known.
   *
   * @param username the caller
   * @param connector the caller's connector
   * @param mailboxAddress the caller's mailbox address
   * @param capabilities what the server can do, or null when not read
   * @return the bounds
   */
  public ForwardingAuthoring authoring(String username,
                                       EmailConnector connector,
                                       String mailboxAddress,
                                       ServerRuleCapabilities capabilities) {
    String reason = null;
    if (!forwardingGuard.authoringEnabled(connector)) {
      reason = ForwardingGuard.DISABLED;
    } else if (capabilities != null && !capabilities.isSupported(ServerRuleCapabilities.FORWARDING_WRITE)) {
      ElementSupport support = capabilities.elements().get(ServerRuleCapabilities.FORWARDING_WRITE);
      reason = support == null || support.reasonKey() == null ? ServerRuleUnsupportedException.FORWARDING_UNSUPPORTED
                                                              : support.reasonKey();
    }
    return new ForwardingAuthoring(reason == null,
                                   reason,
                                   forwardingGuard.allowedDomains(connector, mailboxAddress),
                                   new ArrayList<>(forwardingGuard.confirmedDestinations(username)));
  }

  /**
   * Whether users of a connector may author a forward, as {@link ForwardingGuard}
   * resolves it.
   *
   * @param connector the connector
   * @return true when they may
   */
  public boolean authoringEnabled(EmailConnector connector) {
    return forwardingGuard.authoringEnabled(connector);
  }

  /**
   * What the server can do, narrowed by what the deployment allows: forwarding and the
   * rule action that forwards answered unsupported where authoring is off.
   *
   * @param capabilities the server's answer
   * @param connector the caller's connector
   * @return the capabilities the interface is offered
   */
  public ServerRuleCapabilities narrowed(ServerRuleCapabilities capabilities, EmailConnector connector) {
    if (capabilities == null || forwardingGuard.authoringEnabled(connector)) {
      return capabilities;
    }
    return capabilities.withElement(ServerRuleCapabilities.FORWARDING_WRITE, ElementSupport.unsupported(ForwardingGuard.DISABLED))
                       .withElement(ServerRuleCapabilities.FORWARD, ElementSupport.unsupported(ForwardingGuard.DISABLED));
  }

  /**
   * Sends a short-lived code to a destination the caller wants to forward to, with the
   * platform's own mail service -- never the caller's mailbox, which a session thief may
   * control. The destination must pass every check a forward does but the confirmation.
   * A new code replaces the one waiting.
   *
   * @param username the caller, from the request's session
   * @param delegationId the share the request was made from; any value is refused
   * @param destination the address, as typed
   * @return the normalised destination and until when the code can be entered
   * @throws ObjectNotFoundException when the feature is off, or no mailbox is connected
   * @throws IllegalAccessException when forwarding is off for the connector, the domain
   *           is not allowed, too many codes were asked for, or the request comes from
   *           someone else's mailbox
   * @throws IllegalArgumentException with a message code for a destination that is not
   *           a plain address, or the mailbox itself
   * @throws ServerRuleUnavailableException {@value #CODE_NOT_SENT} when the platform
   *           could not send the mail
   */
  public ForwardingCodeSent sendCode(String username, Long delegationId, String destination) throws ObjectNotFoundException,
                                                                                            IllegalAccessException,
                                                                                            ServerRuleUnavailableException {
    Mailbox mailbox = mailboxOf(username, delegationId);
    String to = allowedNewDestination(username, mailbox, destination);
    long now = clock.millis();
    String code = newCode();
    long expiresAt = now + codeTtlSeconds() * 1000L;
    Map<String, Object> stored = new LinkedHashMap<>();
    // The limit is checked and the send counted in one step per user: requests sent at
    // once are not each allowed their own send.
    synchronized (lockOf(username)) {
      List<Long> sends = recentSends(pending(username), now);
      if (sends.size() >= intProperty(CODE_MAX_SENDS_PROPERTY, DEFAULT_MAX_SENDS)
          || !sends.isEmpty() && now - sends.get(sends.size() - 1) < MIN_SEND_INTERVAL) {
        throw new IllegalAccessException(CODE_TOO_MANY_SENDS);
      }
      String salt = HexFormat.of().formatHex(randomBytes());
      sends.add(now);
      stored.put("destination", to);
      stored.put("salt", salt);
      stored.put("hash", hash(salt, code));
      stored.put("expiresAt", expiresAt);
      stored.put(FIELD_ATTEMPTS, 0);
      stored.put(FIELD_SENDS, sends);
      storePending(username, stored);
    }
    try {
      sendCodeMail(username, to, code);
    } catch (Exception e) {
      // The send still counts against the limit: a failing relay is not a way around it.
      synchronized (lockOf(username)) {
        Map<String, Object> current = pending(username);
        if (Objects.equals(current.get("hash"), stored.get("hash"))) {
          current.remove("hash");
          storePending(username, current);
        }
      }
      LOG.warn("The forwarding confirmation code of user {} could not be sent to {}", username, to, e);
      throw new ServerRuleUnavailableException(CODE_NOT_SENT, e);
    }
    LOG.info("Forwarding confirmation code sent by user {} to {}, valid until {}", username, to, expiresAt);
    return new ForwardingCodeSent(to, expiresAt);
  }

  /**
   * Confirms a destination with the code sent to it: a code works once, for the
   * destination it was sent to, before it expires, and survives a bounded number of wrong
   * tries. Once confirmed, the destination can be used by the forward and by the rules
   * that forward without a new code.
   *
   * @param username the caller, from the request's session
   * @param delegationId the share the request was made from; any value is refused
   * @param destination the address the code was sent to
   * @param code the code, as typed
   * @return the confirmed destination, normalised
   * @throws ObjectNotFoundException when the feature is off, or no mailbox is connected
   * @throws IllegalAccessException when forwarding is off for the connector, the domain
   *           is not allowed, the code was tried wrong too many times, or the request
   *           comes from someone else's mailbox
   * @throws IllegalArgumentException {@value #CODE_INVALID} for a wrong code or no code
   *           waiting, {@value #CODE_EXPIRED} for an expired one
   */
  public String confirm(String username, Long delegationId, String destination, String code) throws ObjectNotFoundException,
                                                                                            IllegalAccessException {
    Mailbox mailbox = mailboxOf(username, delegationId);
    String to = allowedNewDestination(username, mailbox, destination);
    verifyCode(username, to, code);
    forwardingGuard.confirm(username, to);
    LOG.info("Forwarding destination {} confirmed by user {}", to, username);
    return to;
  }

  /**
   * Sets or changes the caller's forward: a copy of every mail to one confirmed address,
   * the mail kept. The destination must be confirmed already, or confirmed by the code
   * this request carries. The owner is notified and mailed, and the band shows it.
   *
   * @param username the caller, from the request's session
   * @param delegationId the share the request was made from; any value is refused
   * @param destination the address
   * @param code the code sent to it, or null when it is confirmed already
   * @param republish true to overwrite eXo's script although it changed outside eXo
   * @return the forward as the server holds it
   * @throws ObjectNotFoundException when the feature is off, or no mailbox is connected
   * @throws IllegalAccessException when forwarding is off for the connector, the domain
   *           is not allowed, the destination is not confirmed, or the request comes
   *           from someone else's mailbox
   * @throws IllegalArgumentException with a message code for an invalid destination or
   *           code
   * @throws ServerRuleUnavailableException when the server cannot be used
   * @throws ServerRuleConflictException when another client may forward too, or eXo's
   *           script changed outside eXo; nothing was written
   * @throws ServerRuleUnsupportedException when the server cannot keep a copy
   * @throws ForwardingRefusedException {@value ForwardingRefusedException#NOT_AUTHORIZED}
   *           when the destination is not one the {@link ForwardingGuard} authorizes for
   *           the caller's own session; checked here, before any engine is called, so
   *           every engine writes only an authorized destination
   */
  public ForwardingSetting setForwarding(String username,
                                         Long delegationId,
                                         String destination,
                                         String code,
                                         boolean republish) throws ObjectNotFoundException,
                                                            IllegalAccessException,
                                                            ServerRuleUnavailableException,
                                                            ServerRuleConflictException,
                                                            ServerRuleUnsupportedException {
    Mailbox mailbox = mailboxOf(username, delegationId);
    String to = allowedNewDestination(username, mailbox, destination);
    if (!forwardingGuard.confirmedDestinations(username).contains(to)) {
      if (StringUtils.isBlank(code)) {
        throw new IllegalAccessException(ForwardingGuard.NOT_CONFIRMED);
      }
      verifyCode(username, to, code);
      forwardingGuard.confirm(username, to);
    }
    ServerRuleEngine engine = serverRuleEngineRegistry.engineFor(mailbox.connector());
    try (MailboxAclSession session = emailDelegationService.openOwnSession(username)) {
      if (!forwardingGuard.authorizedDestinations(session).contains(to)) {
        throw new ForwardingRefusedException(ForwardingRefusedException.NOT_AUTHORIZED);
      }
      ForwardingSetting before = safeRead(engine, session);
      ServerForwarding written = engine.writeForwarding(session, to, republish ? null : storedHash(username));
      written = recordWrite(username, written, to);
      String previous = before != null && before.managedByExo() && !before.destinations().isEmpty() ? before.destinations().get(0)
                                                                                                     : null;
      if (!to.equals(previous)) {
        notifyChange(username, mailbox.address(), previous == null ? Change.SET : Change.CHANGED, to, null);
      }
      LOG.info("Mail forward {} by user {} on connector {}: to {}",
               previous == null ? "set" : "changed",
               username,
               mailbox.connector().getId(),
               to);
      return written.forwarding().withManageUrl(EmailAbsenceService.webmailUrl(mailbox.connector()));
    }
  }

  /**
   * Removes the forward eXo set for the caller. Allowed even when the deployment switched
   * forwarding off: removing a forward can only keep more mail in. Nothing is written
   * when eXo set none.
   *
   * @param username the caller, from the request's session
   * @param delegationId the share the request was made from; any value is refused
   * @param republish true to overwrite eXo's script although it changed outside eXo
   * @throws ObjectNotFoundException when the feature is off, or no mailbox is connected
   * @throws IllegalAccessException when the request comes from someone else's mailbox, or
   *           the caller may not use their connector
   * @throws ServerRuleUnavailableException when the server cannot be used
   * @throws ServerRuleConflictException when eXo's script changed outside eXo
   * @throws ServerRuleUnsupportedException when the connector cannot hold a forward
   */
  public void removeForwarding(String username, Long delegationId, boolean republish) throws ObjectNotFoundException,
                                                                                      IllegalAccessException,
                                                                                      ServerRuleUnavailableException,
                                                                                      ServerRuleConflictException,
                                                                                      ServerRuleUnsupportedException {
    Mailbox mailbox = mailboxOf(username, delegationId);
    ServerRuleEngine engine = serverRuleEngineRegistry.engineFor(mailbox.connector());
    try (MailboxAclSession session = emailDelegationService.openOwnSession(username)) {
      ForwardingSetting before = safeRead(engine, session);
      if (before != null && before.state() == ForwardingState.SERVER_FORWARD && !before.managedByExo()) {
        // A forward eXo did not set -- the one BlueMind holds, set in the webmail -- is
        // managed where it was set: removing eXo's forward never switches it off.
        return;
      }
      ServerForwarding written = engine.writeForwarding(session, null, republish ? null : storedHash(username));
      recordWrite(username, written, null);
      if (before != null && before.managedByExo() && !before.destinations().isEmpty()) {
        notifyChange(username, mailbox.address(), Change.REMOVED, before.destinations().get(0), null);
        LOG.info("Mail forward removed by user {} on connector {}: was to {}",
                 username,
                 mailbox.connector().getId(),
                 before.destinations().get(0));
      }
    }
  }

  /**
   * The forward the mailbox band shows: the cached status, read again from the server
   * when older than the reply's TTL; a server that cannot be read leaves the cached one.
   * Each read also tells the owner about a forward eXo did not set.
   *
   * @param username the caller, from the request's session
   * @param delegationId the share the request was made from; any value is refused
   * @return the status; empty (no state) when nothing is known
   * @throws ObjectNotFoundException when the feature is off
   * @throws IllegalAccessException when the request comes from someone else's mailbox
   */
  public ForwardingStatus getStatus(String username, Long delegationId) throws ObjectNotFoundException, IllegalAccessException {
    EmailAbsenceService.requireOwnMailbox(delegationId);
    ForwardingStatus cached = storedStatus(username);
    long now = clock.millis();
    boolean shown;
    try {
      shown = foreignShown(mailboxOf(username, null).connector());
    } catch (ObjectNotFoundException | IllegalAccessException e) {
      shown = false;
    }
    if (cached != null && now - cached.getLastServerReadDate() <= EmailAbsenceService.ttlSeconds() * 1000L) {
      return visible(cached, shown);
    }
    try {
      Mailbox mailbox = mailboxOf(username, null);
      boolean exoForwarded = cached != null && (cached.isManagedByExo() || !cached.getRuleForwards().isEmpty());
      if (!shown && !exoForwarded) {
        // Neither shown nor authored: unless eXo set a forward, which is always shown
        // while it runs.
        return new ForwardingStatus();
      }
      ServerRuleEngine engine = serverRuleEngineRegistry.engineFor(mailbox.connector());
      try (MailboxAclSession session = emailDelegationService.openOwnSession(username)) {
        observe(username, mailbox.address(), engine.readForwarding(session), shown);
      }
    } catch (ObjectNotFoundException | IllegalAccessException | ServerRuleUnavailableException e) {
      LOG.debug("The forward of user {} could not be read: {}", username, e.getMessage());
      ForwardingStatus kept = cached == null ? new ForwardingStatus() : cached;
      kept.setLastServerReadDate(now);
      storeStatus(username, kept);
    }
    ForwardingStatus status = storedStatus(username);
    return status == null ? new ForwardingStatus() : visible(status, shown);
  }

  /**
   * Whether forwards eXo did not set are shown and told on a connector: when the
   * deployment shows forwards, or lets users set one. The forward eXo set, and eXo's rules
   * that forward, are shown whatever the switches.
   *
   * @param connector the connector
   * @return true when shown
   */
  public boolean foreignShown(EmailConnector connector) {
    return EmailAbsenceService.forwardingDisplayed() || forwardingGuard.authoringEnabled(connector);
  }

  /**
   * A status as the band may show it: whole when forwards eXo did not set are shown, else
   * only eXo's own forward and rules.
   *
   * @param status the status
   * @param foreignShown whether forwards eXo did not set are shown
   * @return the status to answer
   */
  static ForwardingStatus visible(ForwardingStatus status, boolean foreignShown) {
    if (foreignShown || status.isManagedByExo()) {
      return status;
    }
    ForwardingStatus own = new ForwardingStatus();
    own.setState(status.getState() == null ? null : ForwardingState.NONE);
    own.setRuleForwards(status.getRuleForwards());
    own.setLastServerReadDate(status.getLastServerReadDate());
    return own;
  }

  /**
   * Records what a live read of the server found: the band's status, and -- when the
   * server holds a forward eXo did not set that differs from the last one seen -- a
   * notification and a mail to the owner. A read that established nothing changes
   * nothing.
   *
   * @param username the owner
   * @param mailboxAddress the owner's mailbox address, where the mail goes
   * @param forwarding what the server holds
   */
  public void observe(String username, String mailboxAddress, ForwardingSetting forwarding) {
    observe(username, mailboxAddress, forwarding, true);
  }

  /**
   * Records what a live read of the server found, as {@link #observe(String, String,
   * ForwardingSetting)} does; a forward eXo did not set is told only where such forwards
   * are shown.
   *
   * @param username the owner
   * @param mailboxAddress the owner's mailbox address, where the mail goes
   * @param forwarding what the server holds
   * @param foreignShown whether forwards eXo did not set are shown on the connector
   */
  public void observe(String username, String mailboxAddress, ForwardingSetting forwarding, boolean foreignShown) {
    ForwardingSetting read = recognise(username, forwarding);
    if (read == null || read.state() == ForwardingState.UNKNOWN) {
      return;
    }
    storeStatus(username, ForwardingStatus.of(read, clock.millis()));
    String foreign = foreignFingerprint(read);
    String seen = global(FOREIGN_KEY_PREFIX + username);
    // Where forwards eXo did not set are not shown, nothing is told -- nor remembered, so
    // one is told once they are shown -- but eXo's own forward edited outside eXo, which
    // is always shown.
    if ((foreignShown || read.managedByExo()) && !Objects.equals(foreign, StringUtils.defaultString(seen))) {
      setGlobal(FOREIGN_KEY_PREFIX + username, foreign);
      if (!foreign.isEmpty()) {
        if (foreign.startsWith(SCRIPT_FINGERPRINT)) {
          notifyChange(username, mailboxAddress, Change.FOREIGN_SCRIPT, null, read.scriptName());
        } else {
          notifyChange(username, mailboxAddress, Change.FOREIGN, String.join(", ", read.destinations()), null);
        }
        LOG.info("A mail forward eXo did not set was found for user {}: {}", username, foreign);
      }
    }
  }

  /**
   * Refuses a rule that forwards unless every destination passes the forward's checks:
   * the connector allows forwarding, the domain is allowed, and the destination was
   * confirmed. Called before a rule is written to the server; the generator checks the
   * destinations again when it writes the {@code redirect}.
   *
   * @param username the caller
   * @param actions the rule's actions, as sent
   * @throws IllegalAccessException when forwarding is off, a domain is not allowed or a
   *           destination not confirmed
   * @throws IllegalArgumentException with a message code for an invalid destination
   * @throws ObjectNotFoundException when no mailbox is connected
   */
  public void requireRuleForwardsAllowed(String username, List<ServerRule.Action> actions) throws IllegalAccessException,
                                                                                         ObjectNotFoundException {
    List<String> destinations = new ArrayList<>();
    for (ServerRule.Action action : actions == null ? List.<ServerRule.Action> of() : actions) {
      if (action != null && action.type() != null && ServerRule.FORWARD.equalsIgnoreCase(action.type().trim())) {
        destinations.add(action.destination());
      }
    }
    if (destinations.isEmpty()) {
      return;
    }
    Mailbox mailbox = mailboxOf(username, null);
    for (String destination : destinations) {
      String to = allowedNewDestination(username, mailbox, destination);
      if (!forwardingGuard.confirmedDestinations(username).contains(to)) {
        throw new IllegalAccessException(ForwardingGuard.NOT_CONFIRMED);
      }
    }
  }

  /**
   * After the server accepted a write of the rules: tells the owner about every rule
   * that now forwards, forwards elsewhere, or no longer forwards, compared with what the
   * last write left.
   *
   * @param username the owner
   * @param rules the rules as the server holds them after the write
   */
  @SuppressWarnings("unchecked")
  public void recordRuleForwards(String username, List<ServerRule> rules) {
    Map<String, String> now = new LinkedHashMap<>();
    Map<String, String> names = new LinkedHashMap<>();
    for (ServerRule rule : rules == null ? List.<ServerRule> of() : rules) {
      if (rule.enabled() && !rule.forwardDestinations().isEmpty() && rule.ref() != null) {
        now.put(rule.ref(), String.join(", ", rule.forwardDestinations()));
        names.put(rule.ref(), rule.name());
      }
    }
    Map<String, Object> before = new LinkedHashMap<>();
    String stored = global(RULES_KEY_PREFIX + username);
    if (stored != null) {
      try {
        Map<String, Object> read = JsonUtils.fromJsonString(stored, Map.class);
        if (read != null) {
          before.putAll(read);
        }
      } catch (RuntimeException e) {
        LOG.debug("The forwarding rules last seen for user {} could not be read", username, e);
      }
    }
    Map<String, Object> seenNames = before.get("names") instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
    Map<String, Object> seen = before.get("rules") instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
    if (now.equals(stringMap(seen))) {
      return;
    }
    String mailboxAddress = forwardingGuard.mailboxAddress(username);
    for (Map.Entry<String, String> entry : now.entrySet()) {
      Object previous = seen.get(entry.getKey());
      if (previous == null) {
        notifyChange(username, mailboxAddress, Change.RULE_SET, entry.getValue(), names.get(entry.getKey()));
      } else if (!entry.getValue().equals(previous.toString())) {
        notifyChange(username, mailboxAddress, Change.RULE_CHANGED, entry.getValue(), names.get(entry.getKey()));
      }
    }
    for (Map.Entry<String, Object> entry : seen.entrySet()) {
      if (!now.containsKey(entry.getKey())) {
        Object name = seenNames.get(entry.getKey());
        notifyChange(username, mailboxAddress, Change.RULE_REMOVED, String.valueOf(entry.getValue()), name == null ? "" : name.toString());
      }
    }
    Map<String, Object> value = new LinkedHashMap<>();
    value.put("rules", now);
    value.put("names", names);
    setGlobal(RULES_KEY_PREFIX + username, JsonUtils.toJsonString(value));
    // The band says the rules that forward at once, not after its status' TTL.
    ForwardingStatus status = storedStatus(username);
    if (status == null) {
      status = new ForwardingStatus();
      status.setState(ForwardingState.NONE);
    }
    List<ForwardingSetting.RuleForward> ruleForwards = new ArrayList<>();
    for (ServerRule rule : rules == null ? List.<ServerRule> of() : rules) {
      if (rule.enabled()) {
        rule.forwardDestinations().forEach(to -> ruleForwards.add(new ForwardingSetting.RuleForward(rule.name(), to)));
      }
    }
    status.setRuleForwards(ruleForwards);
    storeStatus(username, status);
    LOG.info("Mail filters that forward, user {}: {}", username, now);
  }

  /**
   * A destination that passes every check a new forward does but the confirmation: the
   * connector allows forwarding, the second proof of identity when one is configured, a
   * plain address, not the mailbox itself, in an allowed domain.
   *
   * @param username the caller
   * @param mailbox the caller's mailbox
   * @param destination the address, as typed
   * @return the address, normalised
   * @throws IllegalAccessException when forwarding is off, the proof fails, or the domain
   *           is not allowed
   * @throws IllegalArgumentException with a message code for an invalid address or the
   *           mailbox itself
   */
  String allowedNewDestination(String username, Mailbox mailbox, String destination) throws IllegalAccessException {
    if (!forwardingGuard.authoringEnabled(mailbox.connector())) {
      throw new IllegalAccessException(ForwardingGuard.DISABLED);
    }
    requireStepUp(username);
    String to = ForwardingDestination.normalize(destination);
    if (to.equals(mailbox.address())) {
      throw new IllegalArgumentException(ForwardingGuard.OWN_ADDRESS);
    }
    if (!forwardingGuard.isAllowedDomain(to, mailbox.connector(), mailbox.address())) {
      throw new IllegalAccessException(ForwardingGuard.DOMAIN_NOT_ALLOWED);
    }
    return to;
  }

  /**
   * The second proof of identity, a re-entry of the mailbox password: asked of the
   * {@link ForwardingStepUp} bean when one is configured, before any forwarding change.
   * None ships until the settings endpoint no longer answers the decoded password.
   *
   * @param username the caller
   * @throws IllegalAccessException when the configured check refuses
   */
  private void requireStepUp(String username) throws IllegalAccessException {
    if (stepUp != null) {
      stepUp.verify(username);
    }
  }

  /**
   * Checks a code against the one waiting for the caller, and uses it up: a wrong code
   * counts a try, the last allowed wrong try and an expired code throw the pending one
   * away, the right one is removed so it never works twice.
   *
   * @param username the caller
   * @param destination the destination the code was sent to, normalised
   * @param code the code, as typed
   * @throws IllegalArgumentException {@value #CODE_INVALID} or {@value #CODE_EXPIRED}
   * @throws IllegalAccessException {@value #CODE_TOO_MANY_TRIES} on the last allowed
   *           wrong try, or after it
   */
  private void verifyCode(String username, String destination, String code) throws IllegalAccessException {
    // One check at a time per user, and each try counted before the code is compared:
    // requests sent at once do not each get a free guess.
    synchronized (lockOf(username)) {
      checkCode(username, destination, code);
    }
  }

  /**
   * The check of {@link #verifyCode}, under the user's lock.
   *
   * @param username the caller
   * @param destination the destination the code was sent to, normalised
   * @param code the code, as typed
   * @throws IllegalArgumentException {@value #CODE_INVALID} or {@value #CODE_EXPIRED}
   * @throws IllegalAccessException {@value #CODE_TOO_MANY_TRIES}
   */
  private void checkCode(String username, String destination, String code) throws IllegalAccessException {
    Map<String, Object> pending = pending(username);
    String hash = pending.get("hash") == null ? null : pending.get("hash").toString();
    String salt = pending.get("salt") == null ? null : pending.get("salt").toString();
    if (hash == null || salt == null || !destination.equals(pending.get("destination"))) {
      throw new IllegalArgumentException(CODE_INVALID);
    }
    if (clock.millis() > number(pending.get("expiresAt"))) {
      usedUp(username, pending);
      throw new IllegalArgumentException(CODE_EXPIRED);
    }
    int attempts = (int) number(pending.get(FIELD_ATTEMPTS));
    int maxTries = intProperty(CODE_MAX_TRIES_PROPERTY, DEFAULT_MAX_TRIES);
    if (attempts >= maxTries) {
      usedUp(username, pending);
      throw new IllegalAccessException(CODE_TOO_MANY_TRIES);
    }
    // The try is counted before the comparison.
    attempts++;
    pending.put(FIELD_ATTEMPTS, attempts);
    storePending(username, pending);
    String typed = StringUtils.deleteWhitespace(StringUtils.defaultString(code));
    if (!MessageDigest.isEqual(hash.getBytes(StandardCharsets.US_ASCII), hash(salt, typed).getBytes(StandardCharsets.US_ASCII))) {
      if (attempts >= maxTries) {
        usedUp(username, pending);
        LOG.warn("Forwarding confirmation code of user {} for {} tried wrong {} times: thrown away", username, destination, attempts);
        throw new IllegalAccessException(CODE_TOO_MANY_TRIES);
      }
      throw new IllegalArgumentException(CODE_INVALID);
    }
    usedUp(username, pending);
  }

  /**
   * New locks.
   *
   * @param count how many
   * @return the locks
   */
  private static Object[] newLocks(int count) {
    Object[] locks = new Object[count];
    for (int i = 0; i < count; i++) {
      locks[i] = new Object();
    }
    return locks;
  }

  /**
   * The lock of one user's pending code, on this node: it bounds the requests one user
   * sends at once to one node; on a cluster without sticky sessions each node grants its
   * own tries.
   *
   * @param username the user
   * @return the lock
   */
  private static Object lockOf(String username) {
    return LOCKS[Math.floorMod(username.hashCode(), LOCKS.length)];
  }

  /**
   * Throws a pending code away, keeping when codes were sent so the hourly limit holds.
   *
   * @param username the caller
   * @param pending the pending entry
   */
  private void usedUp(String username, Map<String, Object> pending) {
    Map<String, Object> kept = new LinkedHashMap<>();
    kept.put(FIELD_SENDS, pending.getOrDefault(FIELD_SENDS, List.of()));
    storePending(username, kept);
  }

  /**
   * Sends the confirmation code with the platform's mail service, from the platform's
   * sender, in the requester's language.
   *
   * @param username the requester
   * @param destination where it goes
   * @param code the code
   * @throws Exception when the platform cannot send it
   */
  private void sendCodeMail(String username, String destination, String code) throws Exception {
    Locale locale = localeOf(username);
    String requester = HtmlUtils.htmlEscape(StringUtils.defaultIfBlank(displayName(username), username));
    Message message = new Message();
    message.setFrom(platformSender());
    message.setTo(destination);
    message.setSubject(StringUtils.defaultString(resourceBundleService.getSharedString(CODE_MAIL_SUBJECT_KEY, locale)));
    message.setBody(StringUtils.defaultString(resourceBundleService.getSharedString(CODE_MAIL_BODY_KEY, locale))
                               .replace("{0}", code)
                               .replace("{1}", requester)
                               .replace("{2}", String.valueOf(codeTtlSeconds() / 60)));
    message.setMimeType("text/html");
    mailService.sendMessage(message);
  }

  /**
   * Tells the owner about a change: an eXo notification on the channels she follows,
   * and a mail into the mailbox itself, from the platform's sender -- the channel a
   * session thief cannot silence from eXo. Never fails the change it reports.
   *
   * @param username the owner
   * @param mailboxAddress the owner's mailbox, where the mail goes; null to skip it
   * @param change what happened
   * @param destination where the mail goes, or went
   * @param source the rule's or script's name, or null
   */
  void notifyChange(String username, String mailboxAddress, Change change, String destination, String source) {
    notifyWeb(username, change, destination, source);
    if (StringUtils.isBlank(mailboxAddress)) {
      return;
    }
    try {
      Locale locale = localeOf(username);
      Message message = new Message();
      message.setFrom(platformSender());
      message.setTo(mailboxAddress);
      message.setSubject(StringUtils.defaultString(resourceBundleService.getSharedString(OWNER_MAIL_SUBJECT_KEY, locale)));
      message.setBody("<p>" + EmailForwardingNotificationPlugin.sentence(resourceBundleService, locale, change, destination, source)
          + "</p><p>" + StringUtils.defaultString(resourceBundleService.getSharedString(OWNER_MAIL_ADVICE_KEY, locale)) + "</p>");
      message.setMimeType("text/html");
      mailService.sendMessageInFuture(message);
    } catch (RuntimeException | LinkageError e) {
      LOG.warn("The mail telling user {} of a mail forward change {} could not be sent", username, change, e);
    }
  }

  /**
   * The eXo notification of a change, on the channels the owner follows. Never fails
   * the change it reports.
   *
   * @param username the owner
   * @param change what happened
   * @param destination where the mail goes, or went
   * @param source the rule's or script's name, or null
   */
  void notifyWeb(String username, Change change, String destination, String source) {
    try {
      NotificationContext ctx = NotificationContextImpl.cloneInstance()
                                                       .append(EmailForwardingNotificationPlugin.RECEIVER, username)
                                                       .append(EmailForwardingNotificationPlugin.CHANGE, change.name())
                                                       .append(EmailForwardingNotificationPlugin.DESTINATION,
                                                               StringUtils.defaultString(destination))
                                                       .append(EmailForwardingNotificationPlugin.SOURCE, StringUtils.defaultString(source));
      ctx.getNotificationExecutor()
         .with(ctx.makeCommand(PluginKey.key(NotificationConstants.EMAIL_FORWARDING_NOTIFICATION_PLUGIN)))
         .execute(ctx);
    } catch (RuntimeException | LinkageError e) {
      LOG.warn("User {} could not be notified of a mail forward change {}", username, change, e);
    }
  }

  /**
   * The fingerprint of a forward eXo did not set, to tell a new one from the one already
   * told: the other client's script that may forward, or the destinations the server
   * forwards to when eXo did not set them.
   *
   * @param read what the server holds
   * @return the fingerprint; empty when there is none
   */
  static String foreignFingerprint(ForwardingSetting read) {
    if (read.state() == ForwardingState.MAY_FORWARD_BY_SCRIPT || read.managedByExo() && read.scriptName() != null) {
      return SCRIPT_FINGERPRINT + StringUtils.defaultString(read.scriptName());
    }
    if (read.state() == ForwardingState.SERVER_FORWARD && !read.managedByExo()) {
      return SERVER_FINGERPRINT + String.join(",", new TreeSet<>(read.destinations()));
    }
    return "";
  }

  /**
   * Reads the forward before a change, to tell the owner what it replaced; a read that
   * fails leaves the change to be reported as a new forward.
   *
   * @param engine the engine
   * @param session the caller's own session
   * @return the forward, or null when it could not be read
   */
  private ForwardingSetting safeRead(ServerRuleEngine engine, MailboxAclSession session) {
    try {
      return recognise(session.username(), engine.readForwarding(session));
    } catch (ServerRuleUnavailableException e) {
      LOG.debug("The forward of user {} could not be read before a change: {}", session.username(), e.getMessage());
      return null;
    }
  }

  /**
   * Records the hash of the Sieve script eXo just wrote for a user, whichever feature
   * wrote it, where the user cannot rewrite it: what forwarding trusts to tell eXo's
   * script from one edited outside eXo.
   *
   * @param username the user
   * @param hash the SHA-256 of the text written
   */
  public void recordScriptHash(String username, String hash) {
    forwardingGuard.recordScriptHash(username, hash);
  }

  /**
   * A forward read from an engine without a script, recognised as eXo's own when it is
   * exactly the one eXo last set there: BlueMind holds one forward per mailbox, whoever
   * set it, and only this record tells eXo's from the webmail's. A Sieve engine says so
   * itself, from eXo's script.
   *
   * @param username the owner
   * @param read what the server holds, possibly null
   * @return the same forward, marked eXo's when it is
   */
  public ForwardingSetting recognise(String username, ForwardingSetting read) {
    if (read == null || read.managedByExo() || read.state() != ForwardingState.SERVER_FORWARD) {
      return read;
    }
    String written = global(WRITTEN_KEY_PREFIX + username);
    // eXo's forward always keeps a copy: one edited not to is not eXo's any more.
    return StringUtils.isNotBlank(written) && read.destinations().equals(List.of(written)) && Boolean.TRUE.equals(read.keepCopy())
        ? read.withManagedByExo(true)
        : read;
  }

  /**
   * After the server accepted a forward's write: the script's hash, in the entry the
   * automatic reply and the rules compare with too, or -- on an engine without a script
   * -- the destination eXo set, and the band's status.
   *
   * @param username the caller
   * @param written what the server holds after the write
   * @param destination what eXo set, null when it removed its forward
   * @return what the server holds, eXo's own forward recognised
   */
  private ServerForwarding recordWrite(String username, ServerForwarding written, String destination) {
    if (written.scriptHash() == null) {
      setGlobal(WRITTEN_KEY_PREFIX + username, StringUtils.defaultString(destination));
      written = new ServerForwarding(recognise(username, written.forwarding()), null);
    }
    if (written.scriptHash() != null) {
      Map<String, Object> value = new LinkedHashMap<>();
      value.put("hash", written.scriptHash());
      value.put("lastWriteDate", clock.millis());
      settingService.set(Context.USER.id(username),
                         UserEmailSettingService.EMAIL_CONNECTOR_SCOPE,
                         ExoSieveScript.HASH_SETTING_KEY,
                         SettingValue.create(JsonUtils.toJsonString(value)));
      recordScriptHash(username, written.scriptHash());
    }
    if (written.forwarding() != null && written.forwarding().state() != ForwardingState.UNKNOWN) {
      storeStatus(username, ForwardingStatus.of(written.forwarding(), clock.millis()));
    }
    return written;
  }

  /**
   * The hash of eXo's script as eXo last wrote it, whichever feature wrote it.
   *
   * @param username the caller
   * @return the hash, or null when none or unreadable
   */
  @SuppressWarnings("unchecked")
  private String storedHash(String username) {
    SettingValue<?> value = settingService.get(Context.USER.id(username),
                                               UserEmailSettingService.EMAIL_CONNECTOR_SCOPE,
                                               ExoSieveScript.HASH_SETTING_KEY);
    if (value == null || value.getValue() == null) {
      return null;
    }
    try {
      Map<String, Object> stored = JsonUtils.fromJsonString(value.getValue().toString(), Map.class);
      Object hash = stored == null ? null : stored.get("hash");
      return hash == null ? null : hash.toString();
    } catch (RuntimeException e) {
      LOG.debug("The stored Sieve script hash of user {} could not be read", username, e);
      return null;
    }
  }

  /**
   * The caller's own mailbox, after every check that comes first: the feature is on, the
   * request is about the caller's own mailbox, a mailbox is connected, and the caller may
   * still use its connector.
   *
   * @param username the caller
   * @param delegationId the share the request was made from; any value is refused
   * @return the connector and the mailbox's address
   * @throws ObjectNotFoundException when the feature is off, or no mailbox is connected
   * @throws IllegalAccessException when the request comes from someone else's mailbox, or
   *           the caller may not use the connector
   */
  Mailbox mailboxOf(String username, Long delegationId) throws ObjectNotFoundException, IllegalAccessException {
    EmailAbsenceService.requireOwnMailbox(delegationId);
    UserEmailSetting setting = userEmailSettingService.getUserEmailSetting(username);
    if (setting == null || StringUtils.isBlank(setting.getEmailConnectorId()) || StringUtils.isBlank(setting.getEmailAddress())) {
      throw new ObjectNotFoundException(EmailAbsenceService.NOT_CONNECTED);
    }
    long connectorId = Long.parseLong(setting.getEmailConnectorId());
    EmailConnector connector = emailConnectorService.getEmailConnector(connectorId);
    if (connector == null) {
      throw new ObjectNotFoundException(EmailAbsenceService.NOT_CONNECTED);
    }
    if (!userEmailSettingService.canConnect(connectorId, username)) {
      throw new IllegalAccessException(EmailAbsenceService.NOT_ALLOWED);
    }
    return new Mailbox(connector, setting.getEmailAddress().trim().toLowerCase(Locale.ROOT));
  }

  /**
   * The caller's connector and mailbox address.
   *
   * @param connector the connector
   * @param address the mailbox's address, lower-case
   */
  record Mailbox(EmailConnector connector, String address) {
  }

  /**
   * The pending code entry of a user.
   *
   * @param username the user
   * @return the entry, empty when none
   */
  @SuppressWarnings("unchecked")
  private Map<String, Object> pending(String username) {
    String stored = global(PENDING_KEY_PREFIX + username);
    if (stored == null) {
      return new LinkedHashMap<>();
    }
    try {
      Map<String, Object> read = JsonUtils.fromJsonString(stored, Map.class);
      return read == null ? new LinkedHashMap<>() : new LinkedHashMap<>(read);
    } catch (RuntimeException e) {
      LOG.debug("The pending forwarding code of user {} could not be read", username, e);
      return new LinkedHashMap<>();
    }
  }

  /**
   * Stores a user's pending code entry.
   *
   * @param username the user
   * @param pending the entry
   */
  private void storePending(String username, Map<String, Object> pending) {
    setGlobal(PENDING_KEY_PREFIX + username, JsonUtils.toJsonString(pending));
  }

  /**
   * When codes were sent in the last hour, oldest first.
   *
   * @param pending the pending entry
   * @param now the current time
   * @return the send times, modifiable
   */
  private static List<Long> recentSends(Map<String, Object> pending, long now) {
    List<Long> sends = new ArrayList<>();
    if (pending.get(FIELD_SENDS) instanceof List<?> list) {
      for (Object item : list) {
        long at = number(item);
        if (now - at < 3_600_000L) {
          sends.add(at);
        }
      }
    }
    sends.sort(Long::compare);
    return sends;
  }

  /**
   * The band's cached status.
   *
   * @param username the user
   * @return the status, or null when none or unreadable
   */
  private ForwardingStatus storedStatus(String username) {
    String stored = global(STATUS_KEY_PREFIX + username);
    if (stored == null) {
      return null;
    }
    try {
      return JsonUtils.fromJsonString(stored, ForwardingStatus.class);
    } catch (RuntimeException e) {
      LOG.debug("The cached forward status of user {} could not be read", username, e);
      return null;
    }
  }

  /**
   * Stores the band's status.
   *
   * @param username the user
   * @param status the status
   */
  private void storeStatus(String username, ForwardingStatus status) {
    setGlobal(STATUS_KEY_PREFIX + username, JsonUtils.toJsonString(status));
  }

  /**
   * A forwarding entry of the {@code GLOBAL} context.
   *
   * @param key the key
   * @return the value, or null
   */
  private String global(String key) {
    SettingValue<?> value = settingService.get(Context.GLOBAL, ForwardingGuard.FORWARDING_SCOPE, key);
    return value == null || value.getValue() == null ? null : value.getValue().toString();
  }

  /**
   * Writes a forwarding entry of the {@code GLOBAL} context.
   *
   * @param key the key
   * @param value the value
   */
  private void setGlobal(String key, String value) {
    settingService.set(Context.GLOBAL, ForwardingGuard.FORWARDING_SCOPE, key, SettingValue.create(value));
  }

  /**
   * A new code: {@value #CODE_DIGITS} digits from a strong random source.
   *
   * @return the code
   */
  static String newCode() {
    StringBuilder code = new StringBuilder();
    for (int i = 0; i < CODE_DIGITS; i++) {
      code.append(RANDOM.nextInt(10));
    }
    return code.toString();
  }

  /**
   * Sixteen random bytes, a code's salt.
   *
   * @return the bytes
   */
  private static byte[] randomBytes() {
    byte[] bytes = new byte[16];
    RANDOM.nextBytes(bytes);
    return bytes;
  }

  /**
   * The salted SHA-256 of a code: what is stored, never the code.
   *
   * @param salt the salt, hex
   * @param code the code
   * @return the hex digest
   */
  static String hash(String salt, String code) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      digest.update(salt.getBytes(StandardCharsets.US_ASCII));
      digest.update((byte) ':');
      return HexFormat.of().formatHex(digest.digest(code.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 is not available", e);
    }
  }

  /**
   * A stored number.
   *
   * @param value the value
   * @return the number, 0 when not one
   */
  private static long number(Object value) {
    if (value instanceof Number number) {
      return number.longValue();
    }
    try {
      return value == null ? 0 : Long.parseLong(value.toString());
    } catch (NumberFormatException e) {
      return 0;
    }
  }

  /**
   * A map's values as strings.
   *
   * @param map the map
   * @return the copy
   */
  private static Map<String, String> stringMap(Map<String, Object> map) {
    Map<String, String> copy = new LinkedHashMap<>();
    map.forEach((key, value) -> copy.put(key, value == null ? null : value.toString()));
    return copy;
  }

  /**
   * A user's language.
   *
   * @param username the user
   * @return the locale, English when it cannot be read
   */
  Locale localeOf(String username) {
    try {
      return Locale.of(NotificationPluginUtils.getLanguage(username));
    } catch (RuntimeException e) {
      return Locale.ENGLISH;
    }
  }

  /**
   * The platform's own sender, a name and an address, which every mail of this service
   * is sent from -- never the user's mailbox.
   *
   * @return the sender
   */
  String platformSender() {
    return NotificationPluginUtils.getFrom(null);
  }

  /**
   * A user's full name, as the confirmation mail names the requester.
   *
   * @param username the user
   * @return the name, or null when it cannot be read
   */
  String displayName(String username) {
    try {
      return NotificationPluginUtils.getFullName(username);
    } catch (RuntimeException e) {
      return null;
    }
  }

  /**
   * How long a code can be entered.
   *
   * @return seconds, at least one minute
   */
  static long codeTtlSeconds() {
    try {
      return Math.max(60, Long.parseLong(System.getProperty(CODE_TTL_PROPERTY, String.valueOf(DEFAULT_CODE_TTL)).trim()));
    } catch (NumberFormatException e) {
      return DEFAULT_CODE_TTL;
    }
  }

  /**
   * A positive integer property.
   *
   * @param name the property
   * @param defaultValue its default
   * @return the value, at least one
   */
  private static int intProperty(String name, int defaultValue) {
    try {
      return Math.max(1, Integer.parseInt(System.getProperty(name, String.valueOf(defaultValue)).trim()));
    } catch (NumberFormatException e) {
      return defaultValue;
    }
  }
}
