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

import static org.exoplatform.emailConnector.service.rules.sieve.ExoSieveScript.EOL;
import static org.exoplatform.emailConnector.service.rules.sieve.ExoSieveScript.SCRIPT_NAME;
import static org.exoplatform.emailConnector.service.rules.sieve.ExoSieveScript.WRAPPER_NAME;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

import org.exoplatform.emailConnector.exception.ServerRuleConflictException;
import org.exoplatform.services.log.ExoLogger;
import org.exoplatform.services.log.Log;

/**
 * How eXo publishes its script on a server that runs <b>one</b> active script per
 * account, without ever destroying what another client put there.
 * <p>
 * The four cases, in the order they are evaluated from {@code LISTSCRIPTS}:
 * <ol>
 * <li><b>No active script</b>: store {@value ExoSieveScript#SCRIPT_NAME} and activate
 * it.</li>
 * <li><b>eXo's script is active</b> ({@value ExoSieveScript#SCRIPT_NAME}, or the
 * wrapper {@value ExoSieveScript#WRAPPER_NAME}): replace it in place; a wrapper is
 * re-generated around the script it already included.</li>
 * <li><b>Another script is active and the server advertises {@code include}</b>
 * (RFC 6609): store eXo's script and a wrapper including both, and activate the
 * wrapper. The other script is never modified, renamed or re-uploaded.</li>
 * <li><b>Another script is active and {@code include} is not advertised</b>: refuse,
 * {@link ServerRuleConflictException#SERVER_CONFLICT}. Nothing is written.</li>
 * </ol>
 * Two rules added for the automatic reply:
 * <ul>
 * <li><b>Vacation-token refusal.</b> RFC 5230 §4.6 lets {@code vacation} run once per
 * script execution, and an included script runs inside the same execution; a wrapper
 * next to a foreign script that has its own {@code vacation} would fail at delivery —
 * implicit keep, no reply at all, silently. So before wrapping a script that emits a
 * reply, the foreign script and the personal scripts it includes (one level) are read
 * and scanned for the token; found, eXo refuses with
 * {@link ServerRuleConflictException#MANAGED_ELSEWHERE}. Detection, never parsing, and
 * never by name: a foreign out-of-office is "an active script eXo does not own" plus the
 * token — Stalwart's JMAP one has an empty name, which can be neither read nor included,
 * and is refused as such.</li>
 * <li><b>Wrapper ordering.</b> eXo's script is included first while it files, stops,
 * discards, rejects and redirects nothing — a reply-only script, which a {@code stop}
 * in the other script would otherwise skip — and second otherwise, so the other
 * script's {@code stop} keeps shadowing exactly what it shadowed before.</li>
 * </ul>
 * Invariant, enforced by every write going through one guard: only
 * {@value ExoSieveScript#SCRIPT_NAME} and {@value ExoSieveScript#WRAPPER_NAME} are ever
 * a {@code PUTSCRIPT}, {@code SETACTIVE} or {@code DELETESCRIPT} target. When the
 * server offers {@code CHECKSCRIPT}, eXo's script is checked before anything is
 * written, so a generator bug is a refusal at save time, never a script the server
 * refuses at delivery.
 */
@Component
public class SieveScriptPolicy {

  private static final Log     LOG               = ExoLogger.getLogger(SieveScriptPolicy.class);

  /** The extension the wrapper needs. */
  static final String          INCLUDE_EXTENSION = "include";

  /** The token the vacation-token refusal looks for. */
  static final String          VACATION_TOKEN    = "vacation";

  /** The first line of the wrapper eXo generates. */
  static final String          WRAPPER_HEADER    = "# exo-managed-wrapper-v1";

  /**
   * Exactly the wrapper eXo generates, line endings normalised to LF, capturing the two
   * included names.
   */
  private static final Pattern WRAPPER           = Pattern.compile(Pattern.quote(WRAPPER_HEADER + "\n"
      + "require [\"include\"];\n") + "include :personal \"((?:[^\"\\\\]|\\\\.)*)\";\n"
      + "include :personal \"((?:[^\"\\\\]|\\\\.)*)\";\n");

  /** Which case of the policy a publish took. */
  public enum PolicyCase {
    /** No script was active; eXo's script is now. */
    NO_ACTIVE_SCRIPT,
    /**
     * eXo's wrapper was active around a script that no longer exists; eXo's script now
     * runs alone and the wrapper is gone.
     */
    WRAPPER_REMOVED,
    /** eXo's script was active and was replaced in place. */
    EXO_ACTIVE,
    /** Another script is active through eXo's wrapper, next to eXo's script. */
    WRAPPED
  }

  /**
   * What a publish did.
   *
   * @param policyCase the case taken
   * @param activeScript the script now active
   * @param foreignScript the other script the wrapper includes, null when none
   * @param exoFirst whether the wrapper runs eXo's script first; false when no wrapper
   * @param scriptHash the SHA-256 of the text of {@value ExoSieveScript#SCRIPT_NAME} as
   *          stored
   */
  public record PublishOutcome(PolicyCase policyCase,
                               String activeScript,
                               String foreignScript,
                               boolean exoFirst,
                               String scriptHash) {
  }

  /**
   * Publishes eXo's script according to the policy.
   *
   * @param client an authenticated client
   * @param script the script to publish
   * @return what was done
   * @throws ManageSieveException when the server refuses a command or fails
   * @throws ServerRuleConflictException when publishing would replace or break another
   *           client's script; nothing was written
   */
  public PublishOutcome publish(ManageSieveClient client,
                                ExoSieveScript script) throws ManageSieveException, ServerRuleConflictException {
    String text = script.toScript(SieveStringEncoding.forCapabilities(client.getCapabilities()));
    String hash = ExoSieveScript.sha256(text);
    boolean exoFirst = exoFirst(script);
    List<SieveScriptInfo> scripts = client.listScripts();
    String active = activeScript(scripts);
    if (active == null) {
      client.checkScript(text);
      put(client, SCRIPT_NAME, text);
      activate(client, SCRIPT_NAME);
      return new PublishOutcome(PolicyCase.NO_ACTIVE_SCRIPT, SCRIPT_NAME, null, false, hash);
    }
    if (SCRIPT_NAME.equals(active)) {
      client.checkScript(text);
      put(client, SCRIPT_NAME, text);
      return new PublishOutcome(PolicyCase.EXO_ACTIVE, SCRIPT_NAME, null, false, hash);
    }
    if (WRAPPER_NAME.equals(active)) {
      String foreign = wrappedScript(client.getScript(WRAPPER_NAME));
      if (!exists(scripts, foreign)) {
        // The script the wrapper included is gone: running the wrapper would fail at
        // delivery, so eXo's script takes over alone, as in case 1.
        client.checkScript(text);
        put(client, SCRIPT_NAME, text);
        activate(client, SCRIPT_NAME);
        try {
          delete(client, WRAPPER_NAME);
        } catch (ManageSieveException e) {
          // eXo's script is stored and active: an inactive wrapper left behind is inert,
          // and failing here would hide that the publish took effect.
          LOG.debug("Could not delete the inactive {} script", WRAPPER_NAME, e);
        }
        return new PublishOutcome(PolicyCase.WRAPPER_REMOVED, SCRIPT_NAME, null, false, hash);
      }
      requireNoForeignVacation(client, scripts, foreign, script);
      client.checkScript(text);
      put(client, SCRIPT_NAME, text);
      put(client, WRAPPER_NAME, wrapper(foreign, exoFirst));
      return new PublishOutcome(PolicyCase.WRAPPED, WRAPPER_NAME, foreign, exoFirst, hash);
    }
    if (active.isEmpty()) {
      // A nameless active script (what Stalwart's JMAP out-of-office creates) can be
      // neither read nor included by name: with a reply to publish it is an
      // out-of-office managed elsewhere, without one a script eXo cannot wrap.
      throw new ServerRuleConflictException(script.emitsVacation() ? ServerRuleConflictException.MANAGED_ELSEWHERE
                                                                   : ServerRuleConflictException.SERVER_CONFLICT,
                                            active);
    }
    if (!client.getCapabilities().hasExtension(INCLUDE_EXTENSION) || !includable(active)) {
      throw new ServerRuleConflictException(ServerRuleConflictException.SERVER_CONFLICT, active);
    }
    requireNoForeignVacation(client, scripts, active, script);
    client.checkScript(text);
    put(client, SCRIPT_NAME, text);
    put(client, WRAPPER_NAME, wrapper(active, exoFirst));
    activate(client, WRAPPER_NAME);
    return new PublishOutcome(PolicyCase.WRAPPED, WRAPPER_NAME, active, exoFirst, hash);
  }

  /**
   * The wrapper ordering rule: eXo's script first while it files, stops, discards,
   * rejects and redirects nothing.
   *
   * @param script eXo's script
   * @return true when eXo's script must be included first
   */
  static boolean exoFirst(ExoSieveScript script) {
    return !script.filesOrStops();
  }

  /**
   * The wrapper text including eXo's script and another one, in the order the rule
   * decided.
   *
   * @param foreign the other script's name
   * @param exoFirst whether eXo's script runs first
   * @return the wrapper text
   */
  static String wrapper(String foreign, boolean exoFirst) {
    String exo = "include :personal " + ExoSieveScript.quote(SCRIPT_NAME) + ";" + EOL;
    String theirs = "include :personal " + ExoSieveScript.quote(foreign) + ";" + EOL;
    return WRAPPER_HEADER + EOL + "require [\"include\"];" + EOL + (exoFirst ? exo + theirs : theirs + exo);
  }

  /**
   * The other script an eXo wrapper includes, read back from the exact text eXo
   * generates — compared with line endings normalised, so a server that stores LF for
   * CRLF does not lock eXo out of its own wrapper.
   *
   * @param text the wrapper's text on the server
   * @return the other script's name
   * @throws ServerRuleConflictException {@link ServerRuleConflictException#MODIFIED_OUTSIDE}
   *           when the text is not a wrapper eXo generated
   */
  static String wrappedScript(String text) throws ServerRuleConflictException {
    String normalised = lf(text == null ? "" : text);
    Matcher matcher = WRAPPER.matcher(normalised);
    if (matcher.matches()) {
      String first = unquote(matcher.group(1));
      String second = unquote(matcher.group(2));
      String foreign = SCRIPT_NAME.equals(first) ? second : first;
      boolean exoFirst = SCRIPT_NAME.equals(first);
      if (!isOwn(foreign) && (SCRIPT_NAME.equals(first) || SCRIPT_NAME.equals(second))
          && normalised.equals(lf(wrapper(foreign, exoFirst)))) {
        return foreign;
      }
    }
    throw new ServerRuleConflictException(ServerRuleConflictException.MODIFIED_OUTSIDE, WRAPPER_NAME);
  }

  /**
   * Line endings normalised to LF.
   *
   * @param text the text
   * @return the text with CRLF and CR as LF
   */
  private static String lf(String text) {
    return text.replace("\r\n", "\n").replace('\r', '\n');
  }

  /**
   * The vacation-token refusal: when eXo's script emits a reply, the foreign script and
   * the personal scripts it includes must not carry {@code vacation}. Recall first: when
   * absence cannot be established — a {@code :global} include ManageSieve cannot read,
   * an include name that is not a plain string, an included script that itself
   * includes, or a listed include that does not exist — eXo refuses as well, since a
   * missed {@code vacation} silently breaks every reply and rule at delivery.
   *
   * @param client the client
   * @param scripts the account's scripts
   * @param foreign the foreign active script
   * @param script eXo's script
   * @throws ManageSieveException when a script cannot be read
   * @throws ServerRuleConflictException {@link ServerRuleConflictException#MANAGED_ELSEWHERE}
   *           when one does
   */
  private void requireNoForeignVacation(ManageSieveClient client,
                                        List<SieveScriptInfo> scripts,
                                        String foreign,
                                        ExoSieveScript script) throws ManageSieveException, ServerRuleConflictException {
    if (script.emitsVacation() && mayCarryVacation(client, scripts, foreign)) {
      throw new ServerRuleConflictException(ServerRuleConflictException.MANAGED_ELSEWHERE, foreign);
    }
  }

  /**
   * Whether a foreign script, or a personal script it includes (one level), may carry a
   * {@code vacation} -- the detection behind the vacation-token refusal, also what the
   * automatic reply's read says as "managed elsewhere". Recall first: a nameless script,
   * an unreadable one, a {@code :global} or non-literal include, an included script that
   * itself includes, or a listed include that does not exist all answer true, since
   * absence cannot be established. Detection, never parsing.
   *
   * @param client the client
   * @param scripts the account's scripts
   * @param foreign the foreign script's name
   * @return true when it may carry a {@code vacation}
   * @throws ManageSieveException when a script cannot be read for another reason than
   *           the server refusing it
   */
  boolean mayCarryVacation(ManageSieveClient client,
                           List<SieveScriptInfo> scripts,
                           String foreign) throws ManageSieveException {
    if (foreign.isEmpty()) {
      return true;
    }
    String text;
    try {
      text = client.getScript(foreign);
    } catch (ManageSieveException e) {
      if (e.getKind() != ManageSieveException.Kind.REFUSED) {
        throw e;
      }
      // Listed yet unreadable: absence of a vacation cannot be established.
      return true;
    }
    if (SieveTokenScan.containsWord(text, VACATION_TOKEN) || SieveTokenScan.hasUnreadableInclude(text)) {
      return true;
    }
    for (String included : SieveTokenScan.includedPersonalScripts(text)) {
      if (isOwn(included) || included.equals(foreign)) {
        continue;
      }
      if (!exists(scripts, included)) {
        return true;
      }
      String includedText = client.getScript(included);
      if (SieveTokenScan.containsWord(includedText, VACATION_TOKEN) || SieveTokenScan.includesAnything(includedText)) {
        return true;
      }
    }
    return false;
  }

  /**
   * Stores eXo's script <b>without activating anything</b> -- how a reply is switched
   * off while eXo's script is not the one running: the header keeps the text for "switch
   * it back on", and no wrapper is created around another client's script for a script
   * that sends nothing. Checked first when the server offers {@code CHECKSCRIPT}; written
   * through the one guard.
   *
   * @param client an authenticated client
   * @param script the script to store
   * @return the SHA-256 of the text as stored
   * @throws ManageSieveException when the server refuses a command or fails
   */
  public String store(ManageSieveClient client, ExoSieveScript script) throws ManageSieveException {
    String text = script.toScript(SieveStringEncoding.forCapabilities(client.getCapabilities()));
    client.checkScript(text);
    put(client, SCRIPT_NAME, text);
    return ExoSieveScript.sha256(text);
  }

  /**
   * Stores one of eXo's scripts.
   *
   * @param client the client
   * @param name the name, which must be eXo's
   * @param text the text
   * @throws ManageSieveException when the server refuses
   */
  private void put(ManageSieveClient client, String name, String text) throws ManageSieveException {
    client.putScript(own(name), text);
  }

  /**
   * Activates one of eXo's scripts.
   *
   * @param client the client
   * @param name the name, which must be eXo's
   * @throws ManageSieveException when the server refuses
   */
  private void activate(ManageSieveClient client, String name) throws ManageSieveException {
    client.setActive(own(name));
  }

  /**
   * Deletes one of eXo's scripts.
   *
   * @param client the client
   * @param name the name, which must be eXo's
   * @throws ManageSieveException when the server refuses
   */
  private void delete(ManageSieveClient client, String name) throws ManageSieveException {
    client.deleteScript(own(name));
  }

  /**
   * The guard every write goes through.
   *
   * @param name a script name
   * @return the name, when it is eXo's
   * @throws IllegalStateException when it is not: a write to another client's script is
   *           a bug, never a decision
   */
  static String own(String name) {
    if (!isOwn(name)) {
      throw new IllegalStateException("eXo never writes a script it does not own: " + name);
    }
    return name;
  }

  /**
   * Whether a foreign name can be written into an {@code include} line and read back:
   * no line break, no NUL.
   *
   * @param name the name
   * @return true when the wrapper can name it
   */
  private static boolean includable(String name) {
    return name.indexOf('\r') < 0 && name.indexOf('\n') < 0 && name.indexOf('\0') < 0;
  }

  /**
   * Whether a name is one of eXo's two.
   *
   * @param name the name
   * @return true for {@value ExoSieveScript#SCRIPT_NAME} and
   *         {@value ExoSieveScript#WRAPPER_NAME}
   */
  static boolean isOwn(String name) {
    return SCRIPT_NAME.equals(name) || WRAPPER_NAME.equals(name);
  }

  /**
   * The active script's name.
   *
   * @param scripts the account's scripts
   * @return the name, or null when none is active
   */
  static String activeScript(List<SieveScriptInfo> scripts) {
    return scripts.stream().filter(SieveScriptInfo::active).map(SieveScriptInfo::name).findFirst().orElse(null);
  }

  /**
   * Whether the account holds a script of that name.
   *
   * @param scripts the account's scripts
   * @param name the name
   * @return true when listed
   */
  static boolean exists(List<SieveScriptInfo> scripts, String name) {
    return scripts.stream().anyMatch(info -> info.name().equals(name));
  }

  /**
   * Undoes the quoted-string escaping of a captured name.
   *
   * @param value the captured text between the quotes
   * @return the name
   */
  private static String unquote(String value) {
    return value.replaceAll("\\\\(.)", "$1");
  }
}
