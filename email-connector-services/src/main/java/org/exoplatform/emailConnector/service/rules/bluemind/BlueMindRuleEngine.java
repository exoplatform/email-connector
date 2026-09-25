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
package org.exoplatform.emailConnector.service.rules.bluemind;

import static org.exoplatform.emailConnector.model.ServerRuleCapabilities.ElementSupport.SUPPORTED;
import static org.exoplatform.emailConnector.model.ServerRuleCapabilities.ElementSupport.unsupported;

import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.mail.PasswordAuthentication;

import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import org.exoplatform.emailConnector.exception.MailboxAclException;
import org.exoplatform.emailConnector.exception.ServerRuleUnavailableException;
import org.exoplatform.emailConnector.exception.ServerRuleUnsupportedException;
import org.exoplatform.emailConnector.model.EmailConnector;
import org.exoplatform.emailConnector.model.ForwardingSetting;
import org.exoplatform.emailConnector.model.ServerRuleCapabilities;
import org.exoplatform.emailConnector.model.ServerRuleCapabilities.ElementSupport;
import org.exoplatform.emailConnector.model.ServerRuleCapabilities.VocabularySource;
import org.exoplatform.emailConnector.model.ServerVacation;
import org.exoplatform.emailConnector.model.VacationSetting;
import org.exoplatform.emailConnector.model.VacationState;
import org.exoplatform.emailConnector.provider.EmailCredentialsResolver;
import org.exoplatform.emailConnector.service.acl.MailboxAclSession;
import org.exoplatform.emailConnector.service.bluemind.BlueMindEndpoint;
import org.exoplatform.emailConnector.service.bluemind.BlueMindForwarding;
import org.exoplatform.emailConnector.service.bluemind.BlueMindMailboxTransport;
import org.exoplatform.emailConnector.service.bluemind.BlueMindSession;
import org.exoplatform.emailConnector.service.bluemind.BlueMindTransportException;
import org.exoplatform.emailConnector.service.bluemind.BlueMindVacation;
import org.exoplatform.emailConnector.service.bluemind.BlueMindTransportException.Kind;
import org.exoplatform.emailConnector.service.rules.ServerRuleEngine;
import org.exoplatform.services.connector.credentials.ConnectorCredentialsChannel;
import org.exoplatform.services.log.ExoLogger;
import org.exoplatform.services.log.Log;

/**
 * The server-rule engine of a BlueMind server: the automatic reply is BlueMind's own
 * {@code MailFilter.Vacation}, read and written through the dedicated {@code _vacation}
 * endpoint of the caller's own mailbox, over the one BlueMind port
 * {@link BlueMindMailboxTransport}. Selected by
 * {@code email.connector.rulesEngine[.<connectorId>]=bluemind}.
 * <p>
 * <b>eXo does not own the reply here.</b> BlueMind holds one reply per mailbox, whoever
 * set it -- eXo, the webmail, another client -- so there is no "ours" and no "set
 * elsewhere": a reply set in the webmail is read and adopted, shown and editable in eXo,
 * and eXo's save replaces it. No script, no hash, no ETag: last writer wins, and the
 * engine reads the reply immediately before writing it.
 * <p>
 * <b>Only {@code _vacation} is written.</b> The port offers no write of {@code _filter}
 * or {@code _forwarding}, so the user's rules and forward are never sent back by a
 * reply saved in eXo.
 * <p>
 * <b>Days and instants.</b> BlueMind stores the window as two instants. eXo sends the
 * instant of 00:00 on the first day and of 23:59:59.999 on the last day, in the user's
 * zone; it answers days in the zone the caller gives (else the BlueMind account's own
 * zone, from its login answer, else UTC), and keeps a stored instant
 * unchanged when its day in that zone is the day asked for, so saving the form as it was
 * read never moves a window set in the webmail.
 * <p>
 * <b>What BlueMind decides.</b> The interval between two replies to one sender and the
 * mail that gets no reply are BlueMind's own rules; the {@code days} eXo passes is not
 * part of BlueMind's model and is not sent. The reply is plain text in this phase: a text
 * edited in eXo is sent with {@code textHtml} empty; a text left as the server holds it
 * -- a save or a switch-off that does not touch it -- is sent back as stored, its HTML
 * body and line breaks included, so a reply written in the webmail keeps its body.
 * <p>
 * <b>No transport, no reply.</b> The port's one implementation to come is the adapter to
 * {@code bluemind-commons}, a library not published yet. Until a bean implements the port,
 * {@link #probe} answers unsupported with {@value #TRANSPORT_MISSING}, nothing is read,
 * every write is refused, and no network call and no credential resolution happen.
 * <p>
 * One login per verb, as the caller, with the IMAP channel's login and password the
 * session resolves; the session is closed at the end of the verb. The mailbox addressed
 * is the login's own, from the login answer, never from a request.
 */
@Service
public class BlueMindRuleEngine implements ServerRuleEngine {

  private static final Log     LOG             = ExoLogger.getLogger(BlueMindRuleEngine.class);

  /** The engine name a preset selects. */
  public static final String   NAME            = "bluemind";

  /** Why an element is not offered in this phase. */
  static final String          NOT_IN_PHASE_1  = "emailConnector.rules.unsupported.notInPhase1";

  /** The last instant of a day: 23:59:59.999. */
  static final LocalTime       LAST_INSTANT    = LocalTime.of(23, 59, 59, 999_000_000);

  /**
   * Why the engine supports nothing: no bean implements {@link BlueMindMailboxTransport}
   * yet (the phase-4 plan's {@code bluemind.transportMissing}).
   */
  public static final String   TRANSPORT_MISSING = "emailConnector.rules.bluemind.transportMissing";

  /** The port's implementation; none until the {@code bluemind-commons} adapter exists. */
  @Autowired(required = false)
  private BlueMindMailboxTransport transport;

  private final EmailCredentialsResolver emailCredentialsResolver;

  /**
   * A job run inside one BlueMind session.
   *
   * @param <T> what it produces
   */
  @FunctionalInterface
  private interface Job<T> {

    /**
     * Runs the job.
     *
     * @param session the open session
     * @return what it produces
     * @throws BlueMindTransportException when a call fails
     */
    T run(BlueMindSession session) throws BlueMindTransportException;
  }

  /**
   * The engine Spring builds: the port's implementation is injected when a bean provides
   * it, and absent otherwise.
   *
   * @param emailCredentialsResolver told when BlueMind refuses the provider's material
   */
  @Autowired
  public BlueMindRuleEngine(EmailCredentialsResolver emailCredentialsResolver) {
    this.emailCredentialsResolver = emailCredentialsResolver;
  }

  /**
   * The engine over a given implementation of the port.
   *
   * @param transport the port's implementation, possibly null for none
   * @param emailCredentialsResolver told when BlueMind refuses the provider's material
   */
  public BlueMindRuleEngine(BlueMindMailboxTransport transport, EmailCredentialsResolver emailCredentialsResolver) {
    this.transport = transport;
    this.emailCredentialsResolver = emailCredentialsResolver;
  }

  /**
   * The engine's name.
   *
   * @return {@value #NAME}
   */
  @Override
  public String getName() {
    return NAME;
  }

  /**
   * What the server can do, decided by attempting it: a login as the caller and a read
   * of the caller's reply. A server without the {@code _vacation} endpoint is answered
   * unsupported rather than failed; without an implementation of the port, unsupported
   * with {@value #TRANSPORT_MISSING}, and nothing is attempted.
   *
   * @param session the caller's own session
   * @return the capabilities
   * @throws ServerRuleUnavailableException when the server cannot be used
   */
  @Override
  public ServerRuleCapabilities probe(MailboxAclSession session) throws ServerRuleUnavailableException {
    if (transport == null) {
      return ServerRuleCapabilities.unsupported(TRANSPORT_MISSING, VocabularySource.FIXED);
    }
    try {
      call(session, transport::getVacation);
      return capabilities();
    } catch (ServerRuleUnavailableException e) {
      if (ServerRuleUnavailableException.SERVER_UNSUPPORTED.equals(e.getMessage())) {
        return ServerRuleCapabilities.unsupported(ServerRuleUnavailableException.SERVER_UNSUPPORTED, VocabularySource.FIXED);
      }
      throw e;
    }
  }

  /**
   * Reads the reply, its days in the BlueMind account's own zone: the caller's zone is
   * not known on this path.
   *
   * @param session the caller's own session
   * @return what the server holds
   * @throws ServerRuleUnavailableException when the server cannot be used
   */
  @Override
  public ServerVacation readVacation(MailboxAclSession session) throws ServerRuleUnavailableException {
    return readVacation(session, null);
  }

  /**
   * Reads the reply, its days in the caller's zone.
   *
   * @param session the caller's own session
   * @param zone the caller's zone; null for the BlueMind account's own zone, else UTC
   * @return what the server holds: {@link VacationState#OWN} for any reply the server
   *         holds, whoever set it; {@link ServerVacation#none()} when nothing was ever set,
   *         or when no implementation of the port exists
   * @throws ServerRuleUnavailableException when the server cannot be used
   */
  @Override
  public ServerVacation readVacation(MailboxAclSession session, ZoneId zone) throws ServerRuleUnavailableException {
    if (transport == null) {
      return ServerVacation.none();
    }
    return call(session, bm -> toServerVacation(transport.getVacation(bm), dayZone(zone, bm)));
  }

  /**
   * Replaces the reply through {@code _vacation}, then reads back what the server holds.
   *
   * @param session the caller's own session
   * @param vacation the reply, validated by the caller
   * @param days not part of BlueMind's model; not sent
   * @param expectedScriptHash not used: BlueMind has no script and no version to compare
   * @return what the server holds after the write
   * @throws ServerRuleUnavailableException when the server cannot be used
   * @throws ServerRuleUnsupportedException when no implementation of the port exists
   */
  @Override
  public ServerVacation writeVacation(MailboxAclSession session,
                                      VacationSetting vacation,
                                      int days,
                                      String expectedScriptHash) throws ServerRuleUnavailableException,
                                                                 ServerRuleUnsupportedException {
    if (transport == null) {
      throw new ServerRuleUnsupportedException(ServerRuleUnsupportedException.VACATION_UNSUPPORTED);
    }
    LocalDate first = day(vacation.getStart());
    LocalDate last = day(vacation.getEnd());
    ZoneId zone = zone(vacation.getTimeZone(), first != null || last != null);
    return call(session, bm -> {
      ZoneId dayZone = dayZone(zone, bm);
      BlueMindVacation current = transport.getVacation(bm);
      transport.setVacation(bm,
                            new BlueMindVacation(vacation.isEnabled(),
                                                 startInstant(first, dayZone, current.start()),
                                                 endInstant(last, dayZone, current.end()),
                                                 vacation.getSubject(),
                                                 sameText(vacation.getText(), current.text()) ? current.text()
                                                                                             : vacation.getText(),
                                                 sameText(vacation.getText(), current.text()) ? current.textHtml()
                                                                                             : null));
      return toServerVacation(transport.getVacation(bm), dayZone);
    });
  }

  /**
   * Reads the forward of the caller's own mailbox through {@code _forwarding}, read only:
   * the port offers no write of it. Without an implementation of the port, nothing is
   * read and no network call is made.
   *
   * @param session the caller's own session
   * @return the destinations and whether a copy is kept, {@link ForwardingSetting#none()}
   *         when no forward is on, {@link ForwardingSetting#unknown()} without an
   *         implementation of the port
   * @throws ServerRuleUnavailableException when the server cannot be used
   */
  @Override
  public ForwardingSetting readForwarding(MailboxAclSession session) throws ServerRuleUnavailableException {
    if (transport == null) {
      return ForwardingSetting.unknown();
    }
    return toForwardingSetting(call(session, transport::getForwarding));
  }

  /**
   * eXo's answer for the forward BlueMind holds: on only when it is enabled and names at
   * least one non-blank destination.
   *
   * @param forwarding what BlueMind holds, possibly null
   * @return the answer
   */
  static ForwardingSetting toForwardingSetting(BlueMindForwarding forwarding) {
    if (forwarding == null || !forwarding.enabled()) {
      return ForwardingSetting.none();
    }
    List<String> destinations = forwarding.emails().stream().filter(StringUtils::isNotBlank).map(String::trim).toList();
    return destinations.isEmpty() ? ForwardingSetting.none()
                                  : ForwardingSetting.serverForward(destinations, forwarding.localCopy());
  }

  /**
   * Whether the text eXo sends is the one the server holds, line breaks aside: eXo reads
   * CRLF as LF, so a reply saved or switched off unchanged would otherwise rewrite them.
   *
   * @param sent the text eXo sends, possibly null
   * @param stored the text the server holds, possibly null
   * @return true when both are the same once line breaks are LF
   */
  static boolean sameText(String sent, String stored) {
    if (sent == null || stored == null) {
      return sent == null && stored == null;
    }
    return sent.replace("\r\n", "\n").equals(stored.replace("\r\n", "\n"));
  }

  /**
   * The zone days are stated in: the caller's when known, else the BlueMind account's own
   * when it names a valid one, else UTC.
   *
   * @param zone the caller's zone, possibly null
   * @param session the BlueMind session, carrying the account's zone
   * @return the zone
   */
  static ZoneId dayZone(ZoneId zone, BlueMindSession session) {
    if (zone != null) {
      return zone;
    }
    String account = session == null ? null : session.timeZone();
    if (StringUtils.isNotBlank(account)) {
      try {
        return ZoneId.of(account.trim());
      } catch (DateTimeException e) {
        LOG.debug("BlueMind names an unknown time zone '{}'; UTC is used", account);
      }
    }
    return ZoneOffset.UTC;
  }

  /**
   * What this engine offers: the reply with its window, read whoever set it, and the
   * forward read only; neither HTML nor a forward write in this phase, and no server rules
   * until the server-rules eXip implements them here.
   *
   * @return the capabilities
   */
  static ServerRuleCapabilities capabilities() {
    Map<String, ElementSupport> elements = new LinkedHashMap<>();
    for (String element : ServerRuleCapabilities.ELEMENTS) {
      elements.put(element, unsupported(ServerRuleUnsupportedException.RULES_UNSUPPORTED));
    }
    elements.put(ServerRuleCapabilities.VACATION, SUPPORTED);
    elements.put(ServerRuleCapabilities.VACATION_DATE_WINDOW, SUPPORTED);
    elements.put(ServerRuleCapabilities.VACATION_HTML, unsupported(NOT_IN_PHASE_1));
    elements.put(ServerRuleCapabilities.FORWARDING_READ, SUPPORTED);
    elements.put(ServerRuleCapabilities.FORWARDING_WRITE, unsupported(NOT_IN_PHASE_1));
    elements.put(ServerRuleCapabilities.READS_FOREIGN_VACATION, SUPPORTED);
    return new ServerRuleCapabilities(true, null, false, false, VocabularySource.FIXED, elements);
  }

  /**
   * The engine's answer for what BlueMind holds.
   *
   * @param vacation BlueMind's reply
   * @param zone the zone to state the days in
   * @return the answer; none when nothing was ever set
   */
  static ServerVacation toServerVacation(BlueMindVacation vacation, ZoneId zone) {
    if (vacation == null || vacation.isEmpty()) {
      return ServerVacation.none();
    }
    VacationSetting setting = new VacationSetting(vacation.enabled(),
                                                  vacation.start() == null ? null : dayOf(vacation.start(), zone).toString(),
                                                  vacation.end() == null ? null : dayOf(vacation.end(), zone).toString(),
                                                  zone.getId(),
                                                  vacation.subject(),
                                                  vacation.text() == null ? null : vacation.text().replace("\r\n", "\n"),
                                                  0,
                                                  VacationSetting.Source.SERVER);
    return new ServerVacation(VacationState.OWN, setting, null, null);
  }

  /**
   * The instant sent for the first day: 00:00 of that day in the zone, or the stored
   * instant when it already falls on that day.
   *
   * @param first the first day, or null
   * @param zone the zone
   * @param stored the instant the server holds, or null
   * @return the epoch milliseconds, or null for "from now"
   */
  static Long startInstant(LocalDate first, ZoneId zone, Long stored) {
    if (first == null) {
      return null;
    }
    if (stored != null && first.equals(dayOf(stored, zone))) {
      return stored;
    }
    return first.atStartOfDay(zone).toInstant().toEpochMilli();
  }

  /**
   * The instant sent for the last day: 23:59:59.999 of that day in the zone, or the
   * stored instant when it already falls on that day. Keeping a stored end is a choice
   * the plan leaves open: should the webmail store the last day as its 00:00, an unchanged
   * save keeps a window eXo shows as including that day. It is settled by the live
   * observation of what the webmail stores (plan section 3.3).
   *
   * @param last the last day, or null
   * @param zone the zone
   * @param stored the instant the server holds, or null
   * @return the epoch milliseconds, or null for "until switched off"
   */
  static Long endInstant(LocalDate last, ZoneId zone, Long stored) {
    if (last == null) {
      return null;
    }
    if (stored != null && last.equals(dayOf(stored, zone))) {
      return stored;
    }
    return last.atTime(LAST_INSTANT).atZone(zone).toInstant().toEpochMilli();
  }

  /**
   * The day an instant falls on in a zone.
   *
   * @param epochMillis the instant
   * @param zone the zone
   * @return the day
   */
  static LocalDate dayOf(long epochMillis, ZoneId zone) {
    return LocalDate.ofInstant(Instant.ofEpochMilli(epochMillis), zone);
  }

  /**
   * Runs a job in a session opened as the caller, and closes the session.
   *
   * @param <T> what the job produces
   * @param session the caller's own session
   * @param job the job
   * @return what the job produced
   * @throws ServerRuleUnavailableException when the server cannot be used
   */
  private <T> T call(MailboxAclSession session, Job<T> job) throws ServerRuleUnavailableException {
    BlueMindSession bm = login(session);
    try {
      return job.run(bm);
    } catch (BlueMindTransportException e) {
      throw unavailable(e);
    } finally {
      transport.logout(bm);
    }
  }

  /**
   * Logs in as the caller, with the IMAP channel's login and password the session
   * resolves; a refusal is told to the credentials provider and, when the provider may
   * produce fresh material, the login is tried once more.
   *
   * @param session the caller's own session
   * @return the BlueMind session
   * @throws ServerRuleUnavailableException when the login cannot be made
   */
  private BlueMindSession login(MailboxAclSession session) throws ServerRuleUnavailableException {
    EmailConnector connector = session.connector();
    String apiRoot;
    try {
      apiRoot = BlueMindEndpoint.apiRootOf(connector);
    } catch (IllegalStateException e) {
      LOG.debug("No BlueMind core API for connector {}: {}", connector == null ? null : connector.getId(), e.getMessage());
      throw new ServerRuleUnavailableException(ServerRuleUnavailableException.NOT_CONFIGURED, e);
    }
    try {
      return loginOnce(session, apiRoot);
    } catch (BlueMindTransportException e) {
      if (e.getKind() == Kind.NOT_FOUND) {
        // No login endpoint at this address: the configured core URL is not BlueMind's.
        LOG.debug("No BlueMind login at the core API of connector {}", connector == null ? null : connector.getId());
        throw new ServerRuleUnavailableException(ServerRuleUnavailableException.NOT_CONFIGURED, e);
      }
      if (e.getKind() != Kind.AUTHENTICATION || connector == null) {
        throw unavailable(e);
      }
      emailCredentialsResolver.invalidate(connector.getId(),
                                          connector.getAuthProviderName(),
                                          session.username(),
                                          ConnectorCredentialsChannel.IMAP);
      if (!emailCredentialsResolver.retriesAfterRefusal(connector.getAuthProviderName())) {
        throw unavailable(e);
      }
      try {
        return loginOnce(session, apiRoot);
      } catch (BlueMindTransportException again) {
        throw unavailable(again);
      }
    }
  }

  /**
   * One login attempt with freshly resolved material.
   *
   * @param session the caller's own session
   * @param apiRoot the core API root
   * @return the BlueMind session
   * @throws BlueMindTransportException when BlueMind refuses or fails
   * @throws ServerRuleUnavailableException {@code AUTHENTICATION} when the session
   *           resolves no login and password
   */
  private BlueMindSession loginOnce(MailboxAclSession session,
                                    String apiRoot) throws BlueMindTransportException, ServerRuleUnavailableException {
    PasswordAuthentication credentials;
    try {
      credentials = session.mailCredentials();
    } catch (MailboxAclException e) {
      LOG.debug("No mail credentials for {} to reach BlueMind: {}", session.username(), e.getMessage());
      throw new ServerRuleUnavailableException(ServerRuleUnavailableException.AUTHENTICATION, e);
    }
    if (credentials == null || StringUtils.isBlank(credentials.getUserName()) || credentials.getPassword() == null) {
      throw new ServerRuleUnavailableException(ServerRuleUnavailableException.AUTHENTICATION);
    }
    return transport.login(apiRoot, credentials.getUserName(), credentials.getPassword());
  }

  /**
   * The code a failed call is answered with; the call's text goes to the debug log only.
   *
   * @param e the failure
   * @return the exception to throw
   */
  static ServerRuleUnavailableException unavailable(BlueMindTransportException e) {
    LOG.debug("BlueMind failed: {} {}", e.getKind(), e.getMessage());
    String code = switch (e.getKind()) {
    case AUTHENTICATION -> ServerRuleUnavailableException.AUTHENTICATION;
    case REFUSED -> ServerRuleUnavailableException.SERVER_REFUSED;
    case NOT_FOUND -> ServerRuleUnavailableException.SERVER_UNSUPPORTED;
    default -> ServerRuleUnavailableException.SERVER_UNREACHABLE;
    };
    return new ServerRuleUnavailableException(code, e);
  }

  /**
   * A calendar day.
   *
   * @param value {@code YYYY-MM-DD}, or blank
   * @return the day, or null
   * @throws IllegalArgumentException {@code emailConnector.absence.window.invalid} when
   *           not a day
   */
  private static LocalDate day(String value) {
    if (StringUtils.isBlank(value)) {
      return null;
    }
    try {
      return LocalDate.parse(value.trim());
    } catch (DateTimeException e) {
      throw new IllegalArgumentException("emailConnector.absence.window.invalid", e);
    }
  }

  /**
   * The reply's zone.
   *
   * @param value the IANA zone, or blank
   * @param required whether a window needs it
   * @return the zone, or null when blank and not required
   * @throws IllegalArgumentException {@code emailConnector.absence.timeZone.invalid} when
   *           unknown, or blank while a window needs it
   */
  private static ZoneId zone(String value, boolean required) {
    if (StringUtils.isBlank(value)) {
      if (required) {
        throw new IllegalArgumentException("emailConnector.absence.timeZone.invalid");
      }
      return null;
    }
    try {
      return ZoneId.of(value.trim());
    } catch (DateTimeException e) {
      throw new IllegalArgumentException("emailConnector.absence.timeZone.invalid", e);
    }
  }
}
