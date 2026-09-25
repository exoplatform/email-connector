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
package org.exoplatform.emailConnector.service.rules.sieve;

import static org.exoplatform.emailConnector.service.rules.sieve.ExoSieveScript.SCRIPT_NAME;
import static org.exoplatform.emailConnector.service.rules.sieve.ExoSieveScript.WRAPPER_NAME;

import java.security.cert.CertificateException;
import java.time.Clock;
import java.time.LocalDate;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import org.exoplatform.emailConnector.exception.MailboxAclException;
import org.exoplatform.emailConnector.exception.ServerRuleConflictException;
import org.exoplatform.emailConnector.exception.ServerRuleUnavailableException;
import org.exoplatform.emailConnector.exception.ServerRuleUnsupportedException;
import org.exoplatform.emailConnector.model.ForwardingSetting;
import org.exoplatform.emailConnector.model.ServerRuleCapabilities;
import org.exoplatform.emailConnector.model.ServerRuleCapabilities.VocabularySource;
import org.exoplatform.emailConnector.model.ServerVacation;
import org.exoplatform.emailConnector.model.VacationSetting;
import org.exoplatform.emailConnector.model.VacationState;
import org.exoplatform.emailConnector.service.acl.MailboxAclSession;
import org.exoplatform.emailConnector.service.rules.ServerRuleEngine;
import org.exoplatform.emailConnector.service.rules.sieve.ExoSieveScript.Vacation;
import org.exoplatform.services.connector.credentials.ConnectorCredentialsException;
import org.exoplatform.services.log.ExoLogger;
import org.exoplatform.services.log.Log;

/**
 * The server-rule engine of a Sieve server (Stalwart, Dovecot/Pigeonhole), over
 * ManageSieve: the automatic reply is the vacation section of eXo's own script
 * {@value ExoSieveScript#SCRIPT_NAME}, read back from its self-describing header and
 * published through the one-active-script policy.
 * <p>
 * One authenticated conversation per verb, as the caller, with the IMAP channel's
 * material the session resolves. What the server holds is read, never assumed: the
 * reply's state comes from {@code LISTSCRIPTS} and eXo's header, a reply another client
 * manages is detected by the policy's token scan and named, never parsed.
 * <p>
 * The {@code :handle} of the reply is stable while it stays on, so editing the text
 * never answers a sender twice, and new each time it is switched on again (RFC 5230
 * §4.2). A reply switched off keeps its text in the header; while eXo's script is not
 * the one running, switching it off stores the script without activating anything, so no
 * wrapper is ever created around another client's script for a script that sends
 * nothing.
 */
@Service
public class SieveRuleEngine implements ServerRuleEngine {

  private static final Log         LOG           = ExoLogger.getLogger(SieveRuleEngine.class);

  /** The engine name a preset selects. */
  public static final String       NAME          = "sieve";

  /** The prefix of every {@code :handle} eXo writes. */
  static final String              HANDLE_PREFIX = "exo-vacation-";

  private final ManageSieveConnector manageSieveConnector;

  private final SieveScriptPolicy  policy;

  /** The clock the handles are taken from; a test fixes it. */
  private Clock                    clock         = Clock.systemUTC();

  /**
   * The engine over the shared Sieve foundation.
   *
   * @param manageSieveConnector opens authenticated conversations
   * @param policy the one-active-script policy
   */
  @Autowired
  public SieveRuleEngine(ManageSieveConnector manageSieveConnector, SieveScriptPolicy policy) {
    this.manageSieveConnector = manageSieveConnector;
    this.policy = policy;
  }

  /**
   * Replaces the clock the handles are taken from.
   *
   * @param newClock the clock
   */
  void setClock(Clock newClock) {
    this.clock = newClock;
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
   * What the server can do, from the capabilities it re-issued after TLS, and whether
   * another client's script is active. A server without {@code STARTTLS} or SASL
   * {@code PLAIN} is answered unsupported rather than failed: eXo never talks to it.
   *
   * @param session the caller's own session
   * @return the capabilities
   * @throws ServerRuleUnavailableException when the server cannot be used
   */
  @Override
  public ServerRuleCapabilities probe(MailboxAclSession session) throws ServerRuleUnavailableException {
    ManageSieveClient client;
    try {
      client = open(session);
    } catch (ServerRuleUnavailableException e) {
      if (ServerRuleUnavailableException.SERVER_UNSUPPORTED.equals(e.getMessage())) {
        return ServerRuleCapabilities.unsupported(ServerRuleUnavailableException.SERVER_UNSUPPORTED, VocabularySource.DYNAMIC);
      }
      throw e;
    }
    try {
      ServerRuleCapabilities capabilities = SieveCapabilityDerivation.derive(client.getCapabilities());
      String active = SieveScriptPolicy.activeScript(client.listScripts());
      return capabilities.withPublishConflict(active != null && !SieveScriptPolicy.isOwn(active));
    } catch (ManageSieveException e) {
      throw unavailable(e);
    } finally {
      client.logout();
    }
  }

  /**
   * Reads the reply as the server holds it.
   *
   * @param session the caller's own session
   * @return what the server holds
   * @throws ServerRuleUnavailableException when the server cannot be used
   */
  @Override
  public ServerVacation readVacation(MailboxAclSession session) throws ServerRuleUnavailableException {
    ManageSieveClient client = open(session);
    try {
      return read(client);
    } catch (ManageSieveException e) {
      throw unavailable(e);
    } finally {
      client.logout();
    }
  }

  /**
   * Writes the reply into eXo's script and publishes it, then reads back what the server
   * holds.
   *
   * @param session the caller's own session
   * @param vacation the reply, validated by the caller
   * @param days the minimum number of days between two replies to one sender
   * @param expectedScriptHash the hash of eXo's script as eXo last wrote it, or null to
   *          overwrite it whatever it holds
   * @return what the server holds after the write
   * @throws ServerRuleUnavailableException when the server cannot be used
   * @throws ServerRuleConflictException when the policy refuses, or eXo's script changed
   *           outside eXo or holds rules this engine cannot write back; nothing was
   *           written
   * @throws ServerRuleUnsupportedException when the server lacks {@code vacation}, or the
   *           date extensions a window needs
   */
  @Override
  public ServerVacation writeVacation(MailboxAclSession session,
                                      VacationSetting vacation,
                                      int days,
                                      String expectedScriptHash) throws ServerRuleUnavailableException,
                                                                 ServerRuleConflictException,
                                                                 ServerRuleUnsupportedException {
    ManageSieveClient client = open(session);
    try {
      requireSupported(client, vacation);
      List<SieveScriptInfo> scripts = client.listScripts();
      ExoSieveScript base = ExoSieveScript.empty();
      boolean hasExo = SieveScriptPolicy.exists(scripts, SCRIPT_NAME);
      if (hasExo) {
        String text = client.getScript(SCRIPT_NAME);
        Optional<ExoSieveScript> parsed = ExoSieveScript.parse(text);
        if (expectedScriptHash != null && !expectedScriptHash.equals(ExoSieveScript.sha256(text))) {
          throw new ServerRuleConflictException(ServerRuleConflictException.MODIFIED_OUTSIDE, SCRIPT_NAME);
        }
        if (parsed.isPresent() && !parsed.get().getRules().isEmpty()) {
          // Written back without its rules generator, the rules would be lost.
          throw new ServerRuleConflictException(ServerRuleConflictException.MODIFIED_OUTSIDE, SCRIPT_NAME);
        }
        base = parsed.orElse(base);
      }
      ExoSieveScript script = base.withVacation(toVacation(vacation, days, base.getVacation().orElse(null)));
      String active = SieveScriptPolicy.activeScript(scripts);
      if (vacation.isEnabled() || SCRIPT_NAME.equals(active) || WRAPPER_NAME.equals(active)) {
        policy.publish(client, script);
      } else if (hasExo) {
        policy.store(client, script);
      }
      return read(client);
    } catch (ManageSieveException e) {
      throw unavailable(e);
    } finally {
      client.logout();
    }
  }

  /**
   * Whether the caller's mail may be forwarded, as far as ManageSieve lets eXo see: the
   * script another client manages that runs at delivery, and the personal scripts it
   * includes (one level), are scanned for the {@code redirect} token. A hit, or a script
   * that cannot be read far enough, answers "a forward may be configured by this script";
   * its destinations are never read out of it. eXo's own script is not scanned: its
   * generator emits no {@code redirect} in this phase. Only {@code LISTSCRIPTS} and
   * {@code GETSCRIPT} are issued -- nothing is written.
   *
   * @param session the caller's own session
   * @return {@link ForwardingSetting#mayForwardByScript(String)} naming the script, or
   *         {@link ForwardingSetting#none()}
   * @throws ServerRuleUnavailableException when the server cannot be used
   */
  @Override
  public ForwardingSetting readForwarding(MailboxAclSession session) throws ServerRuleUnavailableException {
    ManageSieveClient client = open(session);
    try {
      List<SieveScriptInfo> scripts = client.listScripts();
      String foreign = runningForeignScript(client, scripts);
      if (foreign != null && policy.mayCarryRedirect(client, scripts, foreign)) {
        return ForwardingSetting.mayForwardByScript(foreign);
      }
      return ForwardingSetting.none();
    } catch (ManageSieveException e) {
      throw unavailable(e);
    } finally {
      client.logout();
    }
  }

  /**
   * The script another client manages that the server runs at delivery: the active
   * script when it is not eXo's, the script eXo's wrapper includes next to eXo's, or the
   * wrapper itself once it no longer reads as the one eXo generated.
   *
   * @param client the client
   * @param scripts the account's scripts
   * @return its name, empty for a script without one; null when only eXo's script runs,
   *         or nothing does
   * @throws ManageSieveException when the wrapper cannot be read
   */
  static String runningForeignScript(ManageSieveClient client, List<SieveScriptInfo> scripts) throws ManageSieveException {
    String active = SieveScriptPolicy.activeScript(scripts);
    if (active == null || SCRIPT_NAME.equals(active)) {
      return null;
    }
    if (!WRAPPER_NAME.equals(active)) {
      return active;
    }
    try {
      String wrapped = SieveScriptPolicy.wrappedScript(client.getScript(WRAPPER_NAME));
      // A wrapper including a script that is gone fails at delivery: nothing runs.
      return SieveScriptPolicy.exists(scripts, wrapped) ? wrapped : null;
    } catch (ServerRuleConflictException e) {
      // No longer eXo's wrapper: whatever it holds now is scanned as another client's.
      return WRAPPER_NAME;
    }
  }

  /**
   * The reply as {@code LISTSCRIPTS} and eXo's header say, on an open conversation.
   *
   * @param client the client
   * @return what the server holds
   * @throws ManageSieveException when a command fails
   */
  ServerVacation read(ManageSieveClient client) throws ManageSieveException {
    List<SieveScriptInfo> scripts = client.listScripts();
    String active = SieveScriptPolicy.activeScript(scripts);
    boolean hasExo = SieveScriptPolicy.exists(scripts, SCRIPT_NAME);
    String exoText = hasExo ? client.getScript(SCRIPT_NAME) : null;
    Optional<ExoSieveScript> exo = ExoSieveScript.parse(exoText);
    String hash = exoText == null ? null : ExoSieveScript.sha256(exoText);
    VacationSetting setting = exo.flatMap(ExoSieveScript::getVacation).map(SieveRuleEngine::toSetting).orElse(null);
    boolean exoRuns = hasExo && SCRIPT_NAME.equals(active);
    boolean wrapperModified = false;
    String foreign = null;
    if (WRAPPER_NAME.equals(active)) {
      try {
        String wrapped = SieveScriptPolicy.wrappedScript(client.getScript(WRAPPER_NAME));
        if (SieveScriptPolicy.exists(scripts, wrapped)) {
          foreign = wrapped;
          exoRuns = hasExo;
        }
        // Otherwise the wrapper includes a script that is gone: it fails at delivery and
        // nothing runs, so eXo's reply reads as not running.
      } catch (ServerRuleConflictException e) {
        wrapperModified = true;
      }
    } else if (active != null && !exoRuns) {
      foreign = active;
    }
    if (foreign != null && policy.mayCarryVacation(client, scripts, foreign)) {
      return new ServerVacation(VacationState.ELSEWHERE, setting, foreign, hash);
    }
    if (wrapperModified) {
      // eXo's wrapper no longer reads as eXo's: named, so the interface says which script
      // to repair; "Re-publish" cannot rewrite it, the other script's name being lost.
      return new ServerVacation(VacationState.MODIFIED, setting, WRAPPER_NAME, hash);
    }
    if (!hasExo) {
      return ServerVacation.none();
    }
    if (exo.isEmpty()) {
      return new ServerVacation(VacationState.MODIFIED, setting, null, hash);
    }
    if (setting == null) {
      return new ServerVacation(VacationState.NONE, null, null, hash);
    }
    if (setting.isEnabled() && !exoRuns) {
      return new ServerVacation(VacationState.INACTIVE, setting, null, hash);
    }
    return new ServerVacation(VacationState.OWN, setting, null, hash);
  }

  /**
   * Refuses a reply this server cannot run: no {@code vacation}, or a window without
   * {@code date} and {@code relational}.
   *
   * @param client the client
   * @param vacation the reply
   * @throws ServerRuleUnsupportedException when it cannot
   */
  private void requireSupported(ManageSieveClient client, VacationSetting vacation) throws ServerRuleUnsupportedException {
    ServerRuleCapabilities capabilities = SieveCapabilityDerivation.derive(client.getCapabilities());
    boolean window = StringUtils.isNotBlank(vacation.getStart()) || StringUtils.isNotBlank(vacation.getEnd());
    if (!capabilities.isSupported(ServerRuleCapabilities.VACATION)
        || window && !capabilities.isSupported(ServerRuleCapabilities.VACATION_DATE_WINDOW)) {
      throw new ServerRuleUnsupportedException(ServerRuleUnsupportedException.VACATION_UNSUPPORTED);
    }
  }

  /**
   * The header model of a reply: the handle kept while the reply stays on, new when it is
   * switched on again, kept as it was when it is switched off.
   *
   * @param vacation the reply
   * @param days the interval
   * @param previous the reply eXo's script held, possibly null
   * @return the model
   * @throws IllegalArgumentException with a message code when a value is invalid
   */
  Vacation toVacation(VacationSetting vacation, int days, Vacation previous) {
    String handle;
    if (previous != null && (previous.enabled() || !vacation.isEnabled())) {
      handle = previous.handle();
    } else {
      handle = HANDLE_PREFIX + clock.millis();
    }
    return new Vacation(vacation.isEnabled(),
                        date(vacation.getStart()),
                        date(vacation.getEnd()),
                        StringUtils.trimToNull(vacation.getTimeZone()),
                        vacation.getSubject(),
                        vacation.getText(),
                        handle,
                        days);
  }

  /**
   * The form's value of a header model.
   *
   * @param vacation the model
   * @return the value, with LF line breaks
   */
  static VacationSetting toSetting(Vacation vacation) {
    return new VacationSetting(vacation.enabled(),
                               vacation.start() == null ? null : vacation.start().toString(),
                               vacation.end() == null ? null : vacation.end().toString(),
                               vacation.zone(),
                               vacation.subject(),
                               vacation.text().replace("\r\n", "\n"),
                               vacation.days(),
                               VacationSetting.Source.EXO);
  }

  /**
   * A calendar day.
   *
   * @param value {@code YYYY-MM-DD}, or blank
   * @return the day, or null
   * @throws IllegalArgumentException {@code emailConnector.absence.window.invalid} when
   *           not a day
   */
  private static LocalDate date(String value) {
    if (StringUtils.isBlank(value)) {
      return null;
    }
    try {
      return LocalDate.parse(value.trim());
    } catch (RuntimeException e) {
      throw new IllegalArgumentException("emailConnector.absence.window.invalid", e);
    }
  }

  /**
   * An authenticated conversation as the session's caller.
   *
   * @param session the caller's own session
   * @return the client
   * @throws ServerRuleUnavailableException when it cannot be opened
   */
  private ManageSieveClient open(MailboxAclSession session) throws ServerRuleUnavailableException {
    try {
      return manageSieveConnector.open(session);
    } catch (ManageSieveException e) {
      throw unavailable(e);
    } catch (ConnectorCredentialsException | MailboxAclException e) {
      LOG.debug("No mail credentials for {} to reach the Sieve server: {}", session.username(), e.getMessage());
      throw new ServerRuleUnavailableException(ServerRuleUnavailableException.AUTHENTICATION, e);
    } catch (IllegalStateException e) {
      LOG.debug("No Sieve endpoint for connector {}: {}", session.connector().getId(), e.getMessage());
      throw new ServerRuleUnavailableException(ServerRuleUnavailableException.NOT_CONFIGURED, e);
    }
  }

  /**
   * The code a failed conversation is answered with; the server's text goes to the debug
   * log only.
   *
   * @param e the failure
   * @return the exception to throw
   */
  static ServerRuleUnavailableException unavailable(ManageSieveException e) {
    LOG.debug("Sieve server failed: {} {}", e.getKind(), e.getMessage());
    String code = switch (e.getKind()) {
    case AUTHENTICATION, NO_CREDENTIALS -> ServerRuleUnavailableException.AUTHENTICATION;
    case TLS_REQUIRED, UNSUPPORTED_MECHANISM -> ServerRuleUnavailableException.SERVER_UNSUPPORTED;
    case REFUSED -> ServerRuleUnavailableException.SERVER_REFUSED;
    default -> isHostNameMismatch(e) ? ServerRuleUnavailableException.TLS_HOST_NAME
                                     : ServerRuleUnavailableException.SERVER_UNREACHABLE;
    };
    return new ServerRuleUnavailableException(code, e);
  }

  /**
   * Whether a failure is the TLS host name check: the server's certificate does not name
   * the host eXo connected to, which the administrator fixes with
   * {@code email.connector.sieve.host}, and which a "server unreachable" would hide.
   *
   * @param failure the failure
   * @return true when a certificate exception in its causes says the name does not match
   */
  static boolean isHostNameMismatch(Throwable failure) {
    Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
    for (Throwable current = failure; current != null && seen.add(current); current = current.getCause()) {
      if (current instanceof CertificateException && current.getMessage() != null
          && (current.getMessage().contains("No subject alternative") || current.getMessage().contains("No name matching"))) {
        return true;
      }
    }
    return false;
  }
}
