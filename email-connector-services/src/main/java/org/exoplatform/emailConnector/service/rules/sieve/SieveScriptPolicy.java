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

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

import org.exoplatform.emailConnector.constant.ManageSieveErrorKind;
import org.exoplatform.emailConnector.constant.SievePolicyCase;
import org.exoplatform.emailConnector.exception.ServerRuleConflictException;
import org.exoplatform.emailConnector.model.SievePublishOutcome;
import org.exoplatform.services.log.ExoLogger;
import org.exoplatform.services.log.Log;

/**
 * How eXo publishes its script on a server that runs <b>one</b> active script per
 * account, without ever destroying what another client put there.
 * <p>
 * The cases, in the order they are evaluated from {@code LISTSCRIPTS}:
 * <ol>
 * <li><b>No active script</b>: store {@value ExoSieveScript#SCRIPT_NAME} and activate
 * it.</li>
 * <li><b>eXo's script is active</b> ({@value ExoSieveScript#SCRIPT_NAME}, or the
 * wrapper {@value ExoSieveScript#WRAPPER_NAME}): replace it in place; a wrapper is
 * re-generated around the script it already included.</li>
 * <li><b>Another script is active and the server advertises {@code include}</b>
 * (RFC 6609): store eXo's script and a wrapper including both, and activate the
 * wrapper. The other script is never modified, renamed or re-uploaded.</li>
 * <li><b>Another script is active and already includes
 * {@value ExoSieveScript#SCRIPT_NAME}</b>, itself or through the personal scripts it
 * includes, at any depth (a user who merged eXo's script into their own): store eXo's
 * script and nothing else. The other script stays active, unwrapped, since a wrapper
 * would run eXo's script twice.</li>
 * <li><b>Another script is active and {@code include} is not advertised</b>: refuse,
 * {@link ServerRuleConflictException#SERVER_CONFLICT}. Nothing is written.</li>
 * </ol>
 * A foreign script that reaches {@value ExoSieveScript#WRAPPER_NAME} through its
 * includes is refused with {@link ServerRuleConflictException#SERVER_CONFLICT}: wrapping
 * it would make the wrapper include itself, which RFC 6609 forbids. So is a script eXo's
 * wrapper already runs once it reaches either of eXo's scripts: running it alone again
 * would mean activating a script eXo does not own. The foreign script is always read for
 * its includes, and one that cannot be read is refused, since neither can be ruled
 * out. So is one whose includes eXo cannot follow to the end: a personal include whose
 * name is not a plain string, or may decode into another one, or more than
 * {@value #MAX_INCLUDED_SCRIPTS} scripts to read, which bounds what one publish reads.
 * Two rules added for the automatic reply:
 * <ul>
 * <li><b>Vacation-token refusal.</b> RFC 5230 §4.6 lets {@code vacation} run once per
 * script execution, and an included script runs inside the same execution; a wrapper
 * next to a foreign script that has its own {@code vacation} would fail at delivery —
 * implicit keep, no reply at all, silently, and so would a foreign script that includes
 * eXo's. So before publishing a script that emits a reply next to a foreign one, the
 * foreign script and the personal scripts it includes (one level) are read
 * and scanned for the token; found, eXo refuses with
 * {@link ServerRuleConflictException#MANAGED_ELSEWHERE}. Detection, never parsing, and
 * never by name: a foreign out-of-office is "an active script eXo does not own" plus the
 * token — Stalwart's JMAP one has an empty name, which can be neither read nor included,
 * and is refused as such.</li>
 * <li><b>Forward coexistence.</b> Before wrapping a script that forwards (eXo's forward,
 * or a rule that forwards), the other script and the personal scripts it includes are
 * scanned the same way for {@code redirect} and {@code notify}; found, eXo refuses with
 * {@link ServerRuleConflictException#FORWARDED_ELSEWHERE} -- one mail would leave twice,
 * and two redirects may exceed what one run is allowed.</li>
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

  /** The command a forward is made of. */
  static final String          REDIRECT_TOKEN    = "redirect";

  /**
   * The other command that sends a delivered mail elsewhere: an {@code enotify}
   * notification to a {@code mailto:} address (RFC 5435), scanned beside
   * {@code redirect}.
   */
  static final String          NOTIFY_TOKEN      = "notify";

  /** The first line of the wrapper eXo generates. */
  static final String          WRAPPER_HEADER    = "# exo-managed-wrapper-v1";

  /** The most scripts the foreign one includes, at any depth, that eXo reads to follow them. */
  static final int             MAX_INCLUDED_SCRIPTS = 20;

  /**
   * Exactly the wrapper eXo generates, line endings normalised to LF, capturing the two
   * included names.
   */
  private static final Pattern WRAPPER           = Pattern.compile(Pattern.quote(WRAPPER_HEADER + "\n"
      + "require [\"include\"];\n") + "include :personal \"((?:[^\"\\\\]|\\\\.)*)\";\n"
      + "include :personal \"((?:[^\"\\\\]|\\\\.)*)\";\n");

  /**
   * Publishes eXo's script according to the policy. Every command runs within the
   * client's conversation deadline, {@value ManageSieveClient#DEFAULT_CONVERSATION_TIMEOUT_MILLIS}
   * ms after its connect began: however many scripts the publish reads, it holds the
   * caller's thread that long at most.
   *
   * @param client an authenticated client
   * @param script the script to publish
   * @return what was done
   * @throws ManageSieveException when the server refuses a command or fails
   * @throws ServerRuleConflictException when publishing would replace or break another
   *           client's script; nothing was written
   */
  public SievePublishOutcome publish(ManageSieveClient client,
                                ExoSieveScript script) throws ManageSieveException, ServerRuleConflictException {
    String text = script.toScript(client.getCapabilities());
    String hash = ExoSieveScript.sha256(text);
    boolean exoFirst = exoFirst(script);
    List<SieveScriptInfo> scripts = client.listScripts();
    String active = activeScript(scripts);
    if (active == null) {
      client.checkScript(text);
      put(client, SCRIPT_NAME, text);
      activate(client, SCRIPT_NAME);
      return new SievePublishOutcome(SievePolicyCase.NO_ACTIVE_SCRIPT, SCRIPT_NAME, null, false, hash);
    }
    if (SCRIPT_NAME.equals(active)) {
      client.checkScript(text);
      put(client, SCRIPT_NAME, text);
      return new SievePublishOutcome(SievePolicyCase.EXO_ACTIVE, SCRIPT_NAME, null, false, hash);
    }
    if (WRAPPER_NAME.equals(active)) {
      return publishUnderWrapper(client, scripts, script, text, hash, exoFirst);
    }
    return publishNextToForeign(client, scripts, active, script, text, hash, exoFirst);
  }

  /**
   * Publishes eXo's script while eXo's wrapper is the active script: the wrapper is
   * rewritten around the script it already includes, or dropped when that script is
   * gone.
   *
   * @param client an authenticated client
   * @param scripts the account's scripts
   * @param script the script to publish
   * @param text the script's text
   * @param hash the text's hash
   * @param exoFirst whether eXo's script is included first
   * @return what was done
   * @throws ManageSieveException when the server refuses a command or fails
   * @throws ServerRuleConflictException when the wrapped script reaches one of eXo's
   *           scripts, or a vacation elsewhere cannot be ruled out
   */
  private SievePublishOutcome publishUnderWrapper(ManageSieveClient client,
                                                  List<SieveScriptInfo> scripts,
                                                  ExoSieveScript script,
                                                  String text,
                                                  String hash,
                                                  boolean exoFirst) throws ManageSieveException,
                                                                    ServerRuleConflictException {
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
      return new SievePublishOutcome(SievePolicyCase.WRAPPER_REMOVED, SCRIPT_NAME, null, false, hash);
    }
    String foreignText = readForeign(client, foreign, script);
    if (!ownScriptsReached(client, scripts, foreign, foreignText).isEmpty()) {
      throw conflict(ServerRuleConflictException.SERVER_CONFLICT, foreign);
    }
    requireNoForeignVacation(client, scripts, foreign, foreignText, script);
    requireNoForeignRedirect(client, scripts, foreign, foreignText, script);
    client.checkScript(text);
    put(client, SCRIPT_NAME, text);
    put(client, WRAPPER_NAME, wrapper(foreign, exoFirst));
    return new SievePublishOutcome(SievePolicyCase.WRAPPED, WRAPPER_NAME, foreign, exoFirst, hash);
  }

  /**
   * Publishes eXo's script while another client's script is active: eXo's script is
   * stored and either included in place by that script, or wrapped with it.
   *
   * @param client an authenticated client
   * @param scripts the account's scripts
   * @param active the other client's active script
   * @param script the script to publish
   * @param text the script's text
   * @param hash the text's hash
   * @param exoFirst whether eXo's script is included first
   * @return what was done
   * @throws ManageSieveException when the server refuses a command or fails
   * @throws ServerRuleConflictException when the active script cannot be read, included
   *           or wrapped, reaches eXo's wrapper, or a vacation elsewhere cannot be ruled
   *           out
   */
  private SievePublishOutcome publishNextToForeign(ManageSieveClient client,
                                                   List<SieveScriptInfo> scripts,
                                                   String active,
                                                   ExoSieveScript script,
                                                   String text,
                                                   String hash,
                                                   boolean exoFirst) throws ManageSieveException,
                                                                     ServerRuleConflictException {
    if (active.isEmpty()) {
      // A nameless active script (what Stalwart's JMAP out-of-office creates) can be
      // neither read nor included by name: with a reply to publish it is an
      // out-of-office managed elsewhere, without one a script eXo cannot wrap.
      String code = ServerRuleConflictException.SERVER_CONFLICT;
      if (script.emitsVacation()) {
        code = ServerRuleConflictException.MANAGED_ELSEWHERE;
      } else if (script.emitsRedirect()) {
        code = ServerRuleConflictException.FORWARDED_ELSEWHERE;
      }
      throw conflict(code, active);
    }
    if (!client.getCapabilities().hasExtension(INCLUDE_EXTENSION) || !includable(active)) {
      throw conflict(ServerRuleConflictException.SERVER_CONFLICT, active);
    }
    String foreignText = readForeign(client, active, script);
    Set<String> reached = ownScriptsReached(client, scripts, active, foreignText);
    if (reached.contains(WRAPPER_NAME)) {
      throw conflict(ServerRuleConflictException.SERVER_CONFLICT, active);
    }
    requireNoForeignVacation(client, scripts, active, foreignText, script);
    requireNoForeignRedirect(client, scripts, active, foreignText, script);
    client.checkScript(text);
    put(client, SCRIPT_NAME, text);
    if (reached.contains(SCRIPT_NAME)) {
      return new SievePublishOutcome(SievePolicyCase.INCLUDED_BY_FOREIGN, active, active, false, hash);
    }
    put(client, WRAPPER_NAME, wrapper(active, exoFirst));
    activate(client, WRAPPER_NAME);
    return new SievePublishOutcome(SievePolicyCase.WRAPPED, WRAPPER_NAME, active, exoFirst, hash);
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
    throw conflict(ServerRuleConflictException.MODIFIED_OUTSIDE, WRAPPER_NAME);
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
   * Reads the foreign active script, whose includes and vacation eXo must know before
   * running next to it.
   *
   * @param client the client
   * @param foreign the foreign active script
   * @param script eXo's script
   * @return the foreign script's text
   * @throws ManageSieveException when the server fails
   * @throws ServerRuleConflictException when it is listed as active yet cannot be read:
   *           {@link ServerRuleConflictException#MANAGED_ELSEWHERE} when eXo's script
   *           emits a reply, {@link ServerRuleConflictException#SERVER_CONFLICT} otherwise
   */
  private static String readForeign(ManageSieveClient client,
                                    String foreign,
                                    ExoSieveScript script) throws ManageSieveException, ServerRuleConflictException {
    try {
      return client.getScript(foreign);
    } catch (ManageSieveException e) {
      if (e.getKind() != ManageSieveErrorKind.REFUSED) {
        throw e;
      }
      throw conflict(script.emitsVacation() ? ServerRuleConflictException.MANAGED_ELSEWHERE
                                            : ServerRuleConflictException.SERVER_CONFLICT,
                     foreign);
    }
  }

  /**
   * eXo's scripts the foreign script reaches through its personal includes, at any
   * depth: each script the account holds is read once, when its turn comes, so one text
   * is held at a time, and a missing or {@code :global} include is not followed, since
   * neither can be one of eXo's.
   *
   * @param client the client
   * @param scripts the account's scripts
   * @param foreign the foreign script's name
   * @param foreignText the foreign script's text
   * @return {@value ExoSieveScript#SCRIPT_NAME}, {@value ExoSieveScript#WRAPPER_NAME},
   *         both or neither
   * @throws ManageSieveException when an included script cannot be read
   * @throws ServerRuleConflictException {@link ServerRuleConflictException#SERVER_CONFLICT}
   *           when a personal include cannot be named, or more than
   *           {@value #MAX_INCLUDED_SCRIPTS} scripts would have to be read
   */
  private static Set<String> ownScriptsReached(ManageSieveClient client,
                                               List<SieveScriptInfo> scripts,
                                               String foreign,
                                               String foreignText) throws ManageSieveException,
                                                                   ServerRuleConflictException {
    Set<String> reached = new HashSet<>();
    Set<String> queued = new HashSet<>(Set.of(foreign));
    Deque<String> unread = new ArrayDeque<>();
    String text = foreignText;
    int reads = 0;
    while (true) {
      if (SieveTokenScan.hasUnnamedPersonalInclude(text)) {
        throw conflict(ServerRuleConflictException.SERVER_CONFLICT, foreign);
      }
      for (String included : SieveTokenScan.includedPersonalScripts(text)) {
        if (isOwn(included)) {
          reached.add(included);
        } else if (exists(scripts, included) && queued.add(included)) {
          unread.push(included);
        }
      }
      if (unread.isEmpty()) {
        return reached;
      }
      if (++reads > MAX_INCLUDED_SCRIPTS) {
        throw conflict(ServerRuleConflictException.SERVER_CONFLICT, foreign);
      }
      text = client.getScript(unread.pop());
    }
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
   * @param text the foreign script's text
   * @param script eXo's script
   * @throws ManageSieveException when a script cannot be read
   * @throws ServerRuleConflictException {@link ServerRuleConflictException#MANAGED_ELSEWHERE}
   *           when one does
   */
  private void requireNoForeignVacation(ManageSieveClient client,
                                        List<SieveScriptInfo> scripts,
                                        String foreign,
                                        String text,
                                        ExoSieveScript script) throws ManageSieveException, ServerRuleConflictException {
    if (script.emitsVacation() && mayCarryIn(client, scripts, foreign, text, VACATION_TOKEN)) {
      throw conflict(ServerRuleConflictException.MANAGED_ELSEWHERE, foreign);
    }
  }

  /**
   * The forward coexistence rule: when eXo's script forwards, the foreign script and the
   * personal scripts it includes must not forward too. Recall first, as for the reply.
   *
   * @param client the client
   * @param scripts the account's scripts
   * @param foreign the foreign active script
   * @param text the foreign script's text
   * @param script eXo's script
   * @throws ManageSieveException when a script cannot be read
   * @throws ServerRuleConflictException {@link ServerRuleConflictException#FORWARDED_ELSEWHERE}
   *           when one may
   */
  private void requireNoForeignRedirect(ManageSieveClient client,
                                        List<SieveScriptInfo> scripts,
                                        String foreign,
                                        String text,
                                        ExoSieveScript script) throws ManageSieveException, ServerRuleConflictException {
    if (script.emitsRedirect() && mayCarryIn(client, scripts, foreign, text, REDIRECT_TOKEN, NOTIFY_TOKEN)) {
      throw conflict(ServerRuleConflictException.FORWARDED_ELSEWHERE, foreign);
    }
  }

  /**
   * Whether a foreign script, or a personal script it includes (one level), may carry a
   * {@code vacation} -- the detection behind the vacation-token refusal, also what the
   * automatic reply's read says as "managed elsewhere". Recall first, detection only: see
   * {@link #mayCarry}.
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
    return mayCarry(client, scripts, foreign, VACATION_TOKEN);
  }

  /**
   * Whether a foreign script, or a personal script it includes (one level), may send a
   * delivered mail elsewhere -- a {@code redirect}, or a {@code notify} that can mail a
   * {@code mailto:} address -- what the forward's read says as "a forward may be
   * configured by this script", and what eXo refuses to add its own forward next to.
   * Recall first, detection only: the destinations are never read out of the script. See
   * {@link #mayCarry}.
   *
   * @param client the client
   * @param scripts the account's scripts
   * @param foreign the foreign script's name
   * @return true when it may carry a {@code redirect}
   * @throws ManageSieveException when a script cannot be read for another reason than
   *           the server refusing it
   */
  boolean mayCarryRedirect(ManageSieveClient client,
                           List<SieveScriptInfo> scripts,
                           String foreign) throws ManageSieveException {
    return mayCarry(client, scripts, foreign, REDIRECT_TOKEN, NOTIFY_TOKEN);
  }

  /**
   * Whether a foreign script, or a personal script it includes (one level), may carry a
   * command token. Recall first: a nameless script, an unreadable one, a {@code :global}
   * or non-literal include, an included script that itself includes, or a listed include
   * that does not exist all answer true, since absence cannot be established. Only
   * {@code GETSCRIPT} is issued. Detection, never parsing.
   *
   * @param client the client
   * @param scripts the account's scripts
   * @param foreign the foreign script's name
   * @param tokens the commands' names; any one found is enough
   * @return true when it may carry one of the commands
   * @throws ManageSieveException when a script cannot be read for another reason than
   *           the server refusing it
   */
  private boolean mayCarry(ManageSieveClient client,
                           List<SieveScriptInfo> scripts,
                           String foreign,
                           String... tokens) throws ManageSieveException {
    if (foreign.isEmpty()) {
      return true;
    }
    String text;
    try {
      text = client.getScript(foreign);
    } catch (ManageSieveException e) {
      if (e.getKind() != ManageSieveErrorKind.REFUSED) {
        throw e;
      }
      // Listed yet unreadable: absence of the command cannot be established.
      return true;
    }
    return mayCarryIn(client, scripts, foreign, text, tokens);
  }

  /**
   * Whether the foreign script whose text is already read, or a personal script it
   * includes (one level), may carry one of the command tokens. See {@link #mayCarry}.
   *
   * @param client the client
   * @param scripts the account's scripts
   * @param foreign the foreign script's name
   * @param text the foreign script's text
   * @param tokens the commands' names; any one found is enough
   * @return true when it may carry one of the commands
   * @throws ManageSieveException when an included script cannot be read
   */
  private boolean mayCarryIn(ManageSieveClient client,
                             List<SieveScriptInfo> scripts,
                             String foreign,
                             String text,
                             String... tokens) throws ManageSieveException {
    if (containsAny(text, tokens) || SieveTokenScan.hasUnreadableInclude(text)) {
      return true;
    }
    for (String included : new LinkedHashSet<>(SieveTokenScan.includedPersonalScripts(text))) {
      if (isOwn(included) || included.equals(foreign)) {
        continue;
      }
      if (!exists(scripts, included)) {
        return true;
      }
      String includedText = client.getScript(included);
      if (containsAny(includedText, tokens) || SieveTokenScan.includesAnything(includedText)) {
        return true;
      }
    }
    return false;
  }

  /**
   * Whether a script's text holds one of the command tokens, as a word outside comments.
   *
   * @param text the text
   * @param tokens the tokens
   * @return true when one is found
   */
  private static boolean containsAny(String text, String... tokens) {
    for (String token : tokens) {
      if (SieveTokenScan.containsWord(text, token)) {
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
    String text = script.toScript(client.getCapabilities());
    client.checkScript(text);
    put(client, SCRIPT_NAME, text);
    return ExoSieveScript.sha256(text);
  }

  /**
   * A conflict about a script the server named, its name made printable and capped as
   * any server text, since the REST layer returns it to the user.
   *
   * @param code the conflict code
   * @param scriptName the name as the server stores it
   * @return the exception to throw
   */
  private static ServerRuleConflictException conflict(String code, String scriptName) {
    return new ServerRuleConflictException(code, ManageSieveClient.printable(scriptName));
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
