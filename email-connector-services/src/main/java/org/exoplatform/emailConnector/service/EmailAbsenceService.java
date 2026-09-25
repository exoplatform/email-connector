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

import java.time.Clock;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import org.exoplatform.commons.api.settings.SettingService;
import org.exoplatform.commons.api.settings.SettingValue;
import org.exoplatform.commons.api.settings.data.Context;
import org.exoplatform.commons.exception.ObjectNotFoundException;
import org.exoplatform.emailConnector.exception.ServerRuleConflictException;
import org.exoplatform.emailConnector.exception.ServerRuleUnavailableException;
import org.exoplatform.emailConnector.exception.ServerRuleUnsupportedException;
import org.exoplatform.emailConnector.model.AbsenceSettings;
import org.exoplatform.emailConnector.model.AbsenceStatus;
import org.exoplatform.emailConnector.model.EmailConnector;
import org.exoplatform.emailConnector.model.ServerRuleCapabilities;
import org.exoplatform.emailConnector.model.ServerVacation;
import org.exoplatform.emailConnector.model.UserEmailSetting;
import org.exoplatform.emailConnector.model.VacationSetting;
import org.exoplatform.emailConnector.model.VacationState;
import org.exoplatform.emailConnector.service.acl.MailboxAclSession;
import org.exoplatform.emailConnector.service.rules.ServerRuleEngine;
import org.exoplatform.emailConnector.service.rules.ServerRuleEngineRegistry;
import org.exoplatform.emailConnector.service.rules.sieve.ExoSieveScript;
import org.exoplatform.services.log.ExoLogger;
import org.exoplatform.services.log.Log;

import io.meeds.social.util.JsonUtils;

/**
 * The user's automatic reply on their <b>own</b> mailbox: every rule of the feature.
 * <p>
 * The reply is a setting of the mail server, which runs it at delivery whether eXo runs
 * or not; eXo is its authoring interface and keeps <b>no copy of its text</b>. Each read
 * asks the server, through the connector preset's rules engine
 * ({@code email.connector.rulesEngine[.<connectorId>]}), acting as the caller with the
 * caller's own session -- never as anyone else, and never on a shared mailbox: a request
 * made from someone else's mailbox is refused.
 * <p>
 * Two small user settings are kept, under the add-on's scope: {@code emailSieveScript},
 * the hash of eXo's Sieve script as eXo last wrote it (shared with the server rules,
 * which write the same script), to notice an edit made outside eXo; and
 * {@code emailAbsence}, a dates-only summary for the mailbox band, refreshed from the
 * server when older than {@code email.connector.absence.status.ttlSeconds} and at once
 * after a change made in eXo. Both are written only after the server accepted a write.
 * <p>
 * Boot-time properties: {@code email.connector.absence.enabled} (default true; false
 * answers "not found" everywhere), {@code email.connector.absence.vacation.days}
 * (default 7, the minimum interval between two replies to one sender),
 * {@code email.connector.absence.status.ttlSeconds} (default 900).
 */
@Service
public class EmailAbsenceService {

  private static final Log         LOG                 = ExoLogger.getLogger(EmailAbsenceService.class);

  /** Whether the feature is on for the deployment. */
  public static final String       ENABLED_PROPERTY    = "email.connector.absence.enabled";

  /** The minimum days between two replies to one sender. */
  public static final String       DAYS_PROPERTY       = "email.connector.absence.vacation.days";

  /** How old the cached summary may be before the band's read asks the server again. */
  public static final String       TTL_PROPERTY        = "email.connector.absence.status.ttlSeconds";

  /** The default of {@value #DAYS_PROPERTY}. */
  public static final int          DEFAULT_DAYS        = 7;

  /** The default of {@value #TTL_PROPERTY}. */
  public static final long         DEFAULT_TTL_SECONDS = 900;

  /** The user setting holding the dates-only summary. */
  public static final String       ABSENCE_SETTING_KEY = "emailAbsence";

  /**
   * The user setting holding the hash of eXo's Sieve script; the same key as the Sieve
   * foundation's, so both features read one entry.
   */
  public static final String       SCRIPT_SETTING_KEY  = ExoSieveScript.HASH_SETTING_KEY;

  /** The feature is switched off for the deployment. */
  public static final String       DISABLED            = "emailConnector.absence.disabled";

  /** A request made from someone else's mailbox. */
  public static final String       OWN_MAILBOX_ONLY    = "emailConnector.absence.ownMailboxOnly";

  /** The caller has no connected mailbox. */
  public static final String       NOT_CONNECTED       = "emailConnector.absence.notConnected";

  /** The caller may not use the connector they are bound to any more. */
  public static final String       NOT_ALLOWED         = "emailConnector.absence.notAllowed";

  /** An invalid subject: empty, more than one line, or too long. */
  public static final String       INVALID_SUBJECT     = "emailConnector.absence.subject.invalid";

  /** An invalid text: empty, or too long once each line break counts as CRLF. */
  public static final String       INVALID_TEXT        = "emailConnector.absence.text.invalid";

  /** An invalid window: a day that is not one, or the last day before the first. */
  public static final String       INVALID_WINDOW      = "emailConnector.absence.window.invalid";

  /** A window without a valid time zone. */
  public static final String       INVALID_TIME_ZONE   = "emailConnector.absence.timeZone.invalid";

  /** The longest subject accepted. */
  static final int                 MAX_SUBJECT_LENGTH  = 200;

  /** The longest text accepted. */
  static final int                 MAX_TEXT_LENGTH     = 4000;

  /** The largest reply interval accepted from the property. */
  static final int                 MAX_DAYS            = 365;

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

  /** The clock the summary's dates are taken from; a test fixes it. */
  private Clock                    clock               = Clock.systemUTC();

  /**
   * Replaces the clock.
   *
   * @param newClock the clock
   */
  void setClock(Clock newClock) {
    this.clock = newClock;
  }

  /**
   * The caller's automatic reply section, read live from the server: what the engine can
   * do, the reply it holds, and its state compared with what eXo last wrote.
   *
   * @param username the caller, from the request's session
   * @param delegationId the share the request was made from, or null for the caller's
   *          own mailbox; any value is refused
   * @return the section
   * @throws ObjectNotFoundException when the feature is off, or the caller has no
   *           connected mailbox
   * @throws IllegalAccessException when the request comes from someone else's mailbox, or
   *           the caller may not use their connector
   * @throws ServerRuleUnavailableException when the server cannot be used
   */
  public AbsenceSettings getAbsence(String username,
                                    Long delegationId) throws ObjectNotFoundException,
                                                       IllegalAccessException,
                                                       ServerRuleUnavailableException {
    ServerRuleEngine engine = engineOf(username, delegationId);
    try (MailboxAclSession session = emailDelegationService.openOwnSession(username)) {
      ServerRuleCapabilities capabilities = engine.probe(session);
      ServerVacation vacation = capabilities.isSupported(ServerRuleCapabilities.VACATION) ? refresh(username, engine, session)
                                                                                          : noReply(username);
      return settings(capabilities, engine, vacation);
    }
  }

  /**
   * Writes the caller's automatic reply on the server. A reply switched on gets a new
   * {@code :handle}, so senders answered by a previous one are answered again; editing a
   * reply that stays on keeps it, so they are not.
   *
   * @param username the caller, from the request's session
   * @param delegationId the share the request was made from; any value is refused
   * @param vacation the reply
   * @param republish true to overwrite eXo's own script even though it changed outside
   *          eXo since eXo last wrote it ("Re-publish")
   * @return the section after the write, capabilities not re-read (null)
   * @throws ObjectNotFoundException when the feature is off, or no mailbox is connected
   * @throws IllegalAccessException when the request comes from someone else's mailbox
   * @throws IllegalArgumentException with a message code when a value is invalid
   * @throws ServerRuleUnavailableException when the server cannot be used
   * @throws ServerRuleConflictException when writing would replace or break another
   *           client's script, or eXo's script changed outside eXo; nothing was written
   * @throws ServerRuleUnsupportedException when this connector cannot hold a reply
   */
  public AbsenceSettings setVacation(String username,
                                     Long delegationId,
                                     VacationSetting vacation,
                                     boolean republish) throws ObjectNotFoundException,
                                                        IllegalAccessException,
                                                        ServerRuleUnavailableException,
                                                        ServerRuleConflictException,
                                                        ServerRuleUnsupportedException {
    ServerRuleEngine engine = engineOf(username, delegationId);
    VacationSetting validated = validate(vacation);
    try (MailboxAclSession session = emailDelegationService.openOwnSession(username)) {
      ServerVacation written = engine.writeVacation(session, validated, days(), republish ? null : storedHash(username));
      recordWrite(username, written);
      LOG.info("Automatic reply {} by user {} on connector {}, window {}..{} {}",
               validated.isEnabled() ? "switched on" : "switched off",
               username,
               session.connector().getId(),
               validated.getStart(),
               validated.getEnd(),
               validated.getTimeZone());
      return settings(null, engine, written);
    }
  }

  /**
   * Switches the caller's automatic reply off, keeping its text on the server for when it
   * is switched on again. Nothing is written when no reply of eXo's is on.
   *
   * @param username the caller, from the request's session
   * @param delegationId the share the request was made from; any value is refused
   * @throws ObjectNotFoundException when the feature is off, or no mailbox is connected
   * @throws IllegalAccessException when the request comes from someone else's mailbox
   * @throws ServerRuleUnavailableException when the server cannot be used
   * @throws ServerRuleConflictException when eXo's script changed outside eXo; nothing
   *           was written
   * @throws ServerRuleUnsupportedException when this connector cannot hold a reply
   */
  public void disableVacation(String username,
                              Long delegationId) throws ObjectNotFoundException,
                                                 IllegalAccessException,
                                                 ServerRuleUnavailableException,
                                                 ServerRuleConflictException,
                                                 ServerRuleUnsupportedException {
    ServerRuleEngine engine = engineOf(username, delegationId);
    try (MailboxAclSession session = emailDelegationService.openOwnSession(username)) {
      ServerVacation current = engine.readVacation(session);
      VacationSetting reply = current.vacation();
      if (reply == null || !reply.isEnabled()) {
        storeStatus(username, summary(current), true);
        return;
      }
      reply.setEnabled(false);
      ServerVacation written = engine.writeVacation(session, reply, days(), storedHash(username));
      recordWrite(username, written);
      LOG.info("Automatic reply switched off by user {} on connector {}", username, session.connector().getId());
    }
  }

  /**
   * The dates-only summary of the caller's reply, for the mailbox band: the cached one,
   * refreshed from the server when older than the TTL. A server that cannot be read
   * leaves the cached summary in place and is not asked again before the TTL; a caller
   * without a mailbox, or whose connector manages no reply, gets a summary saying "off".
   *
   * @param username the caller, from the request's session
   * @param delegationId the share the request was made from; any value is refused
   * @return the summary, never the text
   * @throws ObjectNotFoundException when the feature is off
   * @throws IllegalAccessException when the request comes from someone else's mailbox
   */
  public AbsenceStatus getStatus(String username, Long delegationId) throws ObjectNotFoundException, IllegalAccessException {
    requireOwnMailbox(delegationId);
    AbsenceStatus cached = storedStatus(username);
    if (cached != null && clock.millis() - cached.getLastServerReadDate() <= ttlSeconds() * 1000L) {
      return cached;
    }
    try {
      ServerRuleEngine engine = engineOf(username, null);
      try (MailboxAclSession session = emailDelegationService.openOwnSession(username)) {
        refresh(username, engine, session);
      }
    } catch (ObjectNotFoundException | IllegalAccessException | ServerRuleUnavailableException e) {
      LOG.debug("The automatic reply of user {} could not be read: {}", username, e.getMessage());
      AbsenceStatus kept = cached == null ? new AbsenceStatus() : cached;
      kept.setLastServerReadDate(clock.millis());
      storeStatus(username, kept);
    }
    AbsenceStatus status = storedStatus(username);
    return status == null ? new AbsenceStatus() : status;
  }

  /**
   * Reads the reply, compares eXo's script with the hash eXo stored, and refreshes the
   * summary.
   *
   * @param username the caller
   * @param engine the engine
   * @param session the caller's session
   * @return what the server holds, {@link VacationState#MODIFIED} when eXo's script is
   *         not what eXo last wrote
   * @throws ServerRuleUnavailableException when the server cannot be used
   */
  private ServerVacation refresh(String username,
                                 ServerRuleEngine engine,
                                 MailboxAclSession session) throws ServerRuleUnavailableException {
    ServerVacation read = compared(engine.readVacation(session), storedHash(username));
    storeStatus(username, summary(read), true);
    return read;
  }

  /**
   * A reply eXo wrote in its script is {@link VacationState#MODIFIED} when the script
   * the server holds is not the one eXo last wrote.
   *
   * @param read what the engine read
   * @param storedHash the hash eXo stored, possibly null
   * @return the read, its state corrected
   */
  static ServerVacation compared(ServerVacation read, String storedHash) {
    boolean eXosOwn = read.state() == VacationState.OWN || read.state() == VacationState.INACTIVE;
    if (eXosOwn && storedHash != null && read.scriptHash() != null && !storedHash.equals(read.scriptHash())) {
      return new ServerVacation(VacationState.MODIFIED, read.vacation(), read.foreignScriptName(), read.scriptHash());
    }
    return read;
  }

  /**
   * After the server accepted a write: the script's hash, then the summary.
   *
   * @param username the caller
   * @param written what the server holds after the write
   */
  private void recordWrite(String username, ServerVacation written) {
    if (written.scriptHash() != null) {
      Map<String, Object> value = new LinkedHashMap<>();
      value.put("hash", written.scriptHash());
      value.put("lastWriteDate", clock.millis());
      settingService.set(Context.USER.id(username),
                         UserEmailSettingService.EMAIL_CONNECTOR_SCOPE,
                         SCRIPT_SETTING_KEY,
                         SettingValue.create(JsonUtils.toJsonString(value)));
    }
    AbsenceStatus status = summary(written);
    status.setUpdatedDate(clock.millis());
    storeStatus(username, status);
  }

  /**
   * The summary of what the server holds: on only while eXo's own reply is on and the
   * server runs it as eXo wrote it.
   *
   * @param read what the server holds
   * @return the summary, read now
   */
  private AbsenceStatus summary(ServerVacation read) {
    AbsenceStatus status = new AbsenceStatus();
    VacationSetting reply = read.vacation();
    if (reply != null && reply.isEnabled() && read.state() == VacationState.OWN) {
      status.setEnabled(true);
      status.setStart(reply.getStart());
      status.setEnd(reply.getEnd());
      status.setTimeZone(reply.getTimeZone());
      status.setSource(reply.getSource() == null ? null : reply.getSource().name());
    }
    status.setLastServerReadDate(clock.millis());
    return status;
  }

  /**
   * Stores a summary read from the server, keeping the date it last changed when nothing
   * changed.
   *
   * @param username the caller
   * @param status the summary just read
   * @param keepUpdatedDate whether to keep the stored change date when the reply is the
   *          same
   */
  private void storeStatus(String username, AbsenceStatus status, boolean keepUpdatedDate) {
    AbsenceStatus previous = storedStatus(username);
    if (keepUpdatedDate && previous != null && sameReply(previous, status)) {
      status.setUpdatedDate(previous.getUpdatedDate());
    } else if (status.getUpdatedDate() == 0) {
      status.setUpdatedDate(clock.millis());
    }
    storeStatus(username, status);
  }

  /**
   * Whether two summaries describe the same reply.
   *
   * @param first a summary
   * @param second another
   * @return true when on/off, the days, the zone and the source are equal
   */
  private static boolean sameReply(AbsenceStatus first, AbsenceStatus second) {
    return first.isEnabled() == second.isEnabled() && Objects.equals(first.getStart(), second.getStart())
        && Objects.equals(first.getEnd(), second.getEnd()) && Objects.equals(first.getTimeZone(), second.getTimeZone())
        && Objects.equals(first.getSource(), second.getSource());
  }

  /**
   * Writes the summary.
   *
   * @param username the caller
   * @param status the summary
   */
  private void storeStatus(String username, AbsenceStatus status) {
    settingService.set(Context.USER.id(username),
                       UserEmailSettingService.EMAIL_CONNECTOR_SCOPE,
                       ABSENCE_SETTING_KEY,
                       SettingValue.create(JsonUtils.toJsonString(status)));
  }

  /**
   * The stored summary.
   *
   * @param username the caller
   * @return the summary, or null when none or unreadable
   */
  private AbsenceStatus storedStatus(String username) {
    SettingValue<?> value = settingService.get(Context.USER.id(username),
                                               UserEmailSettingService.EMAIL_CONNECTOR_SCOPE,
                                               ABSENCE_SETTING_KEY);
    if (value == null || value.getValue() == null) {
      return null;
    }
    try {
      return JsonUtils.fromJsonString(value.getValue().toString(), AbsenceStatus.class);
    } catch (RuntimeException e) {
      LOG.debug("The cached automatic reply summary of user {} could not be read", username, e);
      return null;
    }
  }

  /**
   * The hash of eXo's script as eXo last wrote it.
   *
   * @param username the caller
   * @return the hash, or null when none or unreadable
   */
  @SuppressWarnings("unchecked")
  String storedHash(String username) {
    SettingValue<?> value = settingService.get(Context.USER.id(username),
                                               UserEmailSettingService.EMAIL_CONNECTOR_SCOPE,
                                               SCRIPT_SETTING_KEY);
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
   * The answer when the engine cannot hold a reply: nothing to read, the summary off.
   *
   * @param username the caller
   * @return {@link ServerVacation#none()}
   */
  private ServerVacation noReply(String username) {
    ServerVacation none = ServerVacation.none();
    storeStatus(username, summary(none), true);
    return none;
  }

  /**
   * The section's answer.
   *
   * @param capabilities the probe's answer, or null when not re-read
   * @param engine the engine
   * @param vacation what the server holds
   * @return the section
   */
  private AbsenceSettings settings(ServerRuleCapabilities capabilities, ServerRuleEngine engine, ServerVacation vacation) {
    return new AbsenceSettings(capabilities,
                               engine.getName(),
                               vacation.vacation(),
                               vacation.state(),
                               vacation.foreignScriptName(),
                               days());
  }

  /**
   * The engine of the caller's own connector, after every check that comes first: the
   * feature is on, the request is about the caller's own mailbox, a mailbox is
   * connected, and the caller may still use its connector.
   *
   * @param username the caller
   * @param delegationId the share the request was made from; any value is refused
   * @return the engine
   * @throws ObjectNotFoundException when the feature is off, or no mailbox is connected
   * @throws IllegalAccessException when the request comes from someone else's mailbox, or
   *           the caller may not use the connector
   */
  private ServerRuleEngine engineOf(String username, Long delegationId) throws ObjectNotFoundException, IllegalAccessException {
    requireOwnMailbox(delegationId);
    UserEmailSetting setting = userEmailSettingService.getUserEmailSetting(username);
    if (setting == null || StringUtils.isBlank(setting.getEmailConnectorId()) || StringUtils.isBlank(setting.getEmailAddress())) {
      throw new ObjectNotFoundException(NOT_CONNECTED);
    }
    long connectorId = Long.parseLong(setting.getEmailConnectorId());
    EmailConnector connector = emailConnectorService.getEmailConnector(connectorId);
    if (connector == null) {
      throw new ObjectNotFoundException(NOT_CONNECTED);
    }
    if (!userEmailSettingService.canConnect(connectorId, username)) {
      throw new IllegalAccessException(NOT_ALLOWED);
    }
    return serverRuleEngineRegistry.engineFor(connector);
  }

  /**
   * The checks every request passes first: the feature is on, and the request is about
   * the caller's own mailbox. The automatic reply of a shared mailbox is out: no server
   * lets eXo act as its owner.
   *
   * @param delegationId the share the request was made from, or null
   * @throws ObjectNotFoundException when the feature is off
   * @throws IllegalAccessException when the request comes from someone else's mailbox
   */
  private void requireOwnMailbox(Long delegationId) throws ObjectNotFoundException, IllegalAccessException {
    if (!Boolean.parseBoolean(System.getProperty(ENABLED_PROPERTY, "true").trim())) {
      throw new ObjectNotFoundException(DISABLED);
    }
    if (delegationId != null) {
      throw new IllegalAccessException(OWN_MAILBOX_ONLY);
    }
  }

  /**
   * Validates a reply the way every engine needs it, and copies the fields a caller may
   * set.
   *
   * @param vacation the reply as sent
   * @return the copy
   * @throws IllegalArgumentException with a message code when a value is invalid
   */
  static VacationSetting validate(VacationSetting vacation) {
    if (vacation == null || StringUtils.isBlank(vacation.getText())
        || ExoSieveScript.Vacation.storedText(vacation.getText()).length() > MAX_TEXT_LENGTH
        || vacation.getText().indexOf('\0') >= 0) {
      throw new IllegalArgumentException(INVALID_TEXT);
    }
    String subject = vacation.getSubject();
    if (StringUtils.isBlank(subject) || subject.length() > MAX_SUBJECT_LENGTH || subject.indexOf('\r') >= 0
        || subject.indexOf('\n') >= 0 || subject.indexOf('\0') >= 0) {
      throw new IllegalArgumentException(INVALID_SUBJECT);
    }
    String start = StringUtils.trimToNull(vacation.getStart());
    String end = StringUtils.trimToNull(vacation.getEnd());
    LocalDate first = day(start);
    LocalDate last = day(end);
    if (first != null && last != null && first.isAfter(last)) {
      throw new IllegalArgumentException(INVALID_WINDOW);
    }
    String zone = StringUtils.trimToNull(vacation.getTimeZone());
    if (zone != null || first != null || last != null) {
      try {
        ZoneId.of(zone == null ? "" : zone);
      } catch (DateTimeException e) {
        throw new IllegalArgumentException(INVALID_TIME_ZONE, e);
      }
    }
    return new VacationSetting(vacation.isEnabled(), start, end, zone, subject.trim(), vacation.getText(), 0, null);
  }

  /**
   * A calendar day.
   *
   * @param value {@code YYYY-MM-DD}, or null
   * @return the day, or null
   * @throws IllegalArgumentException {@value #INVALID_WINDOW} when not a day
   */
  private static LocalDate day(String value) {
    if (value == null) {
      return null;
    }
    try {
      return LocalDate.parse(value);
    } catch (DateTimeException e) {
      throw new IllegalArgumentException(INVALID_WINDOW, e);
    }
  }

  /**
   * The reply interval, from the property, within 1..{@value #MAX_DAYS}.
   *
   * @return the days
   */
  static int days() {
    try {
      int days = Integer.parseInt(System.getProperty(DAYS_PROPERTY, String.valueOf(DEFAULT_DAYS)).trim());
      return Math.min(MAX_DAYS, Math.max(1, days));
    } catch (NumberFormatException e) {
      return DEFAULT_DAYS;
    }
  }

  /**
   * The summary's time to live, from the property.
   *
   * @return seconds, never negative
   */
  static long ttlSeconds() {
    try {
      return Math.max(0, Long.parseLong(System.getProperty(TTL_PROPERTY, String.valueOf(DEFAULT_TTL_SECONDS)).trim()));
    } catch (NumberFormatException e) {
      return DEFAULT_TTL_SECONDS;
    }
  }
}
