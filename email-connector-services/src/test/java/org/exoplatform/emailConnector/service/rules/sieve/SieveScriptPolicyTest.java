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
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.exoplatform.emailConnector.exception.ServerRuleConflictException;
import org.exoplatform.emailConnector.model.ServerRule;
import org.exoplatform.emailConnector.service.rules.sieve.ExoSieveScript.Vacation;
import org.exoplatform.emailConnector.service.rules.sieve.ManageSieveException.Kind;
import org.exoplatform.emailConnector.service.rules.sieve.SieveScriptPolicy.PolicyCase;
import org.exoplatform.emailConnector.service.rules.sieve.SieveScriptPolicy.PublishOutcome;

/**
 * The one-active-script policy against a ManageSieve server over a real socket: the
 * four cases, the vacation-token refusal, the wrapper ordering rule, and the invariant
 * that no write ever targets a script eXo does not own.
 */
public class SieveScriptPolicyTest {

  private static final String   FOREIGN      = "roundcube";

  private static final String   FILTERS_ONLY = "require [\"fileinto\"];\r\nif header :contains \"subject\" \"invoice\" {\r\n"
      + "  fileinto \"Accounting\";\r\n  stop;\r\n}\r\n";

  private final SieveScriptPolicy policy = new SieveScriptPolicy();

  private FakeManageSieveServer server;

  /**
   * Starts a fresh server.
   *
   * @throws Exception when it cannot start
   */
  @BeforeEach
  public void startServer() throws Exception {
    server = new FakeManageSieveServer();
  }

  /**
   * Stops the server and checks the invariant every scenario must keep.
   */
  @AfterEach
  public void stopServerAndCheckTheInvariant() {
    server.close();
    for (String command : server.getCommands()) {
      if (command.startsWith("PUTSCRIPT") || command.startsWith("SETACTIVE") || command.startsWith("DELETESCRIPT")) {
        String target = command.substring(command.indexOf(' ') + 1);
        assertTrue(target.startsWith(SCRIPT_NAME + " ") || target.equals(SCRIPT_NAME) || target.startsWith(WRAPPER_NAME + " ")
            || target.equals(WRAPPER_NAME), "a write targeted a script eXo does not own: " + command);
      }
    }
  }

  /**
   * Case 1: no active script — eXo's script is checked, stored and activated.
   *
   * @throws Exception on failure
   */
  @Test
  public void testCase1NoActiveScript() throws Exception {
    server.script("old", "keep;", false);
    ExoSieveScript script = replyOn();
    PublishOutcome outcome = publish(script);
    assertEquals(PolicyCase.NO_ACTIVE_SCRIPT, outcome.policyCase());
    assertEquals(SCRIPT_NAME, server.getActive());
    assertEquals(script.toScript(), server.getScripts().get(SCRIPT_NAME));
    assertEquals(ExoSieveScript.sha256(script.toScript()), outcome.scriptHash());
    assertEquals("keep;", server.getScripts().get("old"));
    assertEquals(List.of("CHECKSCRIPT", "PUTSCRIPT", "SETACTIVE " + SCRIPT_NAME), verbs("CHECKSCRIPT", "PUTSCRIPT", "SETACTIVE"));
  }

  /**
   * Case 2: eXo's script is active — replaced in place, not re-activated.
   *
   * @throws Exception on failure
   */
  @Test
  public void testCase2ExoScriptActiveIsReplacedInPlace() throws Exception {
    server.script(SCRIPT_NAME, replyOff().toScript(), true);
    PublishOutcome outcome = publish(replyOn());
    assertEquals(PolicyCase.EXO_ACTIVE, outcome.policyCase());
    assertEquals(replyOn().toScript(), server.getScripts().get(SCRIPT_NAME));
    assertEquals(List.of(), server.getCommands("SETACTIVE"));
  }

  /**
   * Case 3: another script is active and include is advertised — eXo's script and a
   * wrapper are stored, the wrapper activated, the other script untouched; a reply-only
   * script is included first.
   *
   * @throws Exception on failure
   */
  @Test
  public void testCase3WrapsTheForeignScriptReplyFirst() throws Exception {
    server.script(FOREIGN, FILTERS_ONLY, true);
    PublishOutcome outcome = publish(replyOn());
    assertEquals(PolicyCase.WRAPPED, outcome.policyCase());
    assertEquals(FOREIGN, outcome.foreignScript());
    assertTrue(outcome.exoFirst());
    assertEquals(WRAPPER_NAME, server.getActive());
    assertEquals(FILTERS_ONLY, server.getScripts().get(FOREIGN));
    assertEquals("# exo-managed-wrapper-v1\r\nrequire [\"include\"];\r\ninclude :personal \"exo-rules\";\r\n"
        + "include :personal \"roundcube\";\r\n", server.getScripts().get(WRAPPER_NAME));
  }

  /**
   * The vacation-token refusal: a foreign active script with its own vacation, a
   * vacation in its require list only, or in a script it includes, makes eXo refuse and
   * write nothing.
   *
   * @throws Exception on failure
   */
  @Test
  public void testVacationTokenRefusal() throws Exception {
    for (Map<String, String> account : List.of(Map.of(FOREIGN, "require [\"vacation\"];\r\nvacation \"Away\";\r\n"),
                                               Map.of(FOREIGN, "require [\"vacation\"];\r\nkeep;\r\n"),
                                               Map.of(FOREIGN,
                                                      "require [\"include\"];\r\ninclude :personal \"ooo\";\r\n",
                                                      "ooo",
                                                      "vacation \"Away\";\r\n"))) {
      server.close();
      server = new FakeManageSieveServer();
      account.forEach((name, text) -> server.script(name, text, FOREIGN.equals(name)));
      ServerRuleConflictException e = assertThrows(ServerRuleConflictException.class, () -> publish(replyOn()));
      assertEquals(ServerRuleConflictException.MANAGED_ELSEWHERE, e.getMessage());
      assertEquals(FOREIGN, e.getScriptName());
      assertNoWrite();
      assertEquals(FOREIGN, server.getActive());
    }
  }

  /**
   * Recall first: when absence of a vacation cannot be established — a global include, a
   * nested include, an include of a script that does not exist — eXo refuses too.
   *
   * @throws Exception on failure
   */
  @Test
  public void testRefusesWhenAbsenceCannotBeEstablished() throws Exception {
    for (Map<String, String> account : List.of(Map.of(FOREIGN, "require [\"include\"];\r\ninclude :global \"ooo\";\r\n"),
                                               Map.of(FOREIGN,
                                                      "require [\"include\"];\r\ninclude \"a\";\r\n",
                                                      "a",
                                                      "require [\"include\"];\r\ninclude \"b\";\r\n",
                                                      "b",
                                                      "vacation \"Away\";\r\n"),
                                               Map.of(FOREIGN, "require [\"include\"];\r\ninclude \"gone\";\r\n"))) {
      server.close();
      server = new FakeManageSieveServer();
      account.forEach((name, text) -> server.script(name, text, FOREIGN.equals(name)));
      ServerRuleConflictException e = assertThrows(ServerRuleConflictException.class, () -> publish(replyOn()));
      assertEquals(ServerRuleConflictException.MANAGED_ELSEWHERE, e.getMessage());
      assertNoWrite();
    }
  }

  /**
   * A wrapper the server stored with LF line endings still reads as eXo's.
   *
   * @throws Exception on failure
   */
  @Test
  public void testAWrapperStoredWithLfStillReadsBack() throws Exception {
    server.script(FOREIGN, FILTERS_ONLY, false)
          .script(SCRIPT_NAME, replyOff().toScript(), false)
          .script(WRAPPER_NAME, SieveScriptPolicy.wrapper(FOREIGN, true).replace("\r\n", "\n"), true);
    assertEquals(PolicyCase.WRAPPED, publish(replyOn()).policyCase());
  }

  /**
   * Stalwart's JMAP out-of-office: an active script with an empty name. eXo recognises it
   * as an active script it does not own — never by a name — and refuses: managed
   * elsewhere when eXo would publish a reply, a conflict otherwise.
   *
   * @throws Exception on failure
   */
  @Test
  public void testANamelessActiveScriptIsRefused() throws Exception {
    server.script("", "require [\"vacation\"];\r\nvacation \"Away\";\r\n", true);
    ServerRuleConflictException e = assertThrows(ServerRuleConflictException.class, () -> publish(replyOn()));
    assertEquals(ServerRuleConflictException.MANAGED_ELSEWHERE, e.getMessage());
    assertEquals("", e.getScriptName());
    e = assertThrows(ServerRuleConflictException.class, () -> publish(replyOff()));
    assertEquals(ServerRuleConflictException.SERVER_CONFLICT, e.getMessage());
    assertNoWrite();
    assertEquals("", server.getActive());
  }

  /**
   * An active foreign script GETSCRIPT cannot find is unreadable: absence of a vacation
   * cannot be established, so eXo refuses.
   *
   * @throws Exception on failure
   */
  @Test
  public void testAnUnreadableActiveScriptIsRefused() throws Exception {
    server.script(FOREIGN, FILTERS_ONLY, true).refuse("GETSCRIPT", "NO (NONEXISTENT) \"There is no script by that name\"");
    ServerRuleConflictException e = assertThrows(ServerRuleConflictException.class, () -> publish(replyOn()));
    assertEquals(ServerRuleConflictException.MANAGED_ELSEWHERE, e.getMessage());
    assertNoWrite();
  }

  /**
   * On a server advertising encoded-character, the published text uses it for a
   * backslash, and the outcome's hash is that text's.
   *
   * @throws Exception on failure
   */
  @Test
  public void testPublishUsesTheServersStringEncoding() throws Exception {
    ExoSieveScript script = ExoSieveScript.empty()
                                          .withVacation(new Vacation(true, null, null, null, "Away", "C:\\temp", "exo-vacation-1", 7));
    PublishOutcome outcome = publish(script);
    String stored = server.getScripts().get(SCRIPT_NAME);
    assertEquals(script.toScript(SieveStringEncoding.ENCODED_CHARACTER), stored);
    assertTrue(stored.contains("C:${unicode:5C}temp"));
    assertEquals(ExoSieveScript.sha256(stored), outcome.scriptHash());
  }

  /**
   * The word only in a comment of the foreign script is not a vacation: eXo wraps.
   *
   * @throws Exception on failure
   */
  @Test
  public void testAVacationInACommentIsNotRefused() throws Exception {
    server.script(FOREIGN, "# vacation: see the webmail\r\nkeep;\r\n", true);
    assertEquals(PolicyCase.WRAPPED, publish(replyOn()).policyCase());
  }

  /**
   * The refusal bites only when eXo's script emits a reply: a switched-off reply wraps
   * next to a foreign vacation, and the foreign script is not even read.
   *
   * @throws Exception on failure
   */
  @Test
  public void testNoRefusalWhenEXoEmitsNoReply() throws Exception {
    server.script(FOREIGN, "vacation \"Away\";\r\n", true);
    assertEquals(PolicyCase.WRAPPED, publish(replyOff()).policyCase());
    assertEquals(List.of(), server.getCommands("GETSCRIPT"));
  }

  /**
   * Case 4: another script is active and include is not advertised — refused, nothing
   * written, not even checked.
   *
   * @throws Exception on failure
   */
  @Test
  public void testCase4RefusesWithoutInclude() throws Exception {
    server.sieveExtensions("fileinto imap4flags vacation date relational").script(FOREIGN, FILTERS_ONLY, true);
    ServerRuleConflictException e = assertThrows(ServerRuleConflictException.class, () -> publish(replyOn()));
    assertEquals(ServerRuleConflictException.SERVER_CONFLICT, e.getMessage());
    assertEquals(FOREIGN, e.getScriptName());
    assertNoWrite();
    assertEquals(List.of(), server.getCommands("CHECKSCRIPT"));
    assertEquals(FOREIGN, server.getActive());
  }

  /**
   * Case 2 through the wrapper: re-publishing keeps the included script, re-generates the
   * wrapper and does not re-activate anything.
   *
   * @throws Exception on failure
   */
  @Test
  public void testCase2WrapperIsRegeneratedAroundTheSameScript() throws Exception {
    server.script(FOREIGN, FILTERS_ONLY, false)
          .script(SCRIPT_NAME, replyOff().toScript(), false)
          .script(WRAPPER_NAME, SieveScriptPolicy.wrapper(FOREIGN, false), true);
    PublishOutcome outcome = publish(replyOn());
    assertEquals(PolicyCase.WRAPPED, outcome.policyCase());
    assertEquals(SieveScriptPolicy.wrapper(FOREIGN, true), server.getScripts().get(WRAPPER_NAME));
    assertEquals(replyOn().toScript(), server.getScripts().get(SCRIPT_NAME));
    assertEquals(FILTERS_ONLY, server.getScripts().get(FOREIGN));
    assertEquals(List.of(), server.getCommands("SETACTIVE"));
  }

  /**
   * Case 2 through the wrapper, the vacation-token refusal still applies: the foreign
   * script may have gained a vacation since eXo wrapped it.
   *
   * @throws Exception on failure
   */
  @Test
  public void testCase2WrapperRefusesAForeignVacationAddedLater() throws Exception {
    server.script(FOREIGN, "vacation \"Away\";\r\n", false)
          .script(SCRIPT_NAME, replyOff().toScript(), false)
          .script(WRAPPER_NAME, SieveScriptPolicy.wrapper(FOREIGN, true), true);
    ServerRuleConflictException e = assertThrows(ServerRuleConflictException.class, () -> publish(replyOn()));
    assertEquals(ServerRuleConflictException.MANAGED_ELSEWHERE, e.getMessage());
    assertNoWrite();
  }

  /**
   * When the script the wrapper included is gone, eXo's script takes over alone and the
   * wrapper is removed.
   *
   * @throws Exception on failure
   */
  @Test
  public void testWrapperAroundADeletedScriptFallsBackToCase1() throws Exception {
    server.script(SCRIPT_NAME, replyOff().toScript(), false).script(WRAPPER_NAME, SieveScriptPolicy.wrapper(FOREIGN, true), true);
    PublishOutcome outcome = publish(replyOn());
    assertEquals(PolicyCase.WRAPPER_REMOVED, outcome.policyCase());
    assertEquals(SCRIPT_NAME, server.getActive());
    assertNull(server.getScripts().get(WRAPPER_NAME));
  }

  /**
   * Once eXo's script is active, a refused deletion of the inert wrapper does not turn
   * a publish that took effect into a failure.
   *
   * @throws Exception on failure
   */
  @Test
  public void testARefusedWrapperDeletionDoesNotFailThePublish() throws Exception {
    server.script(SCRIPT_NAME, replyOff().toScript(), false)
          .script(WRAPPER_NAME, SieveScriptPolicy.wrapper(FOREIGN, true), true)
          .refuse("DELETESCRIPT", "NO \"Not now\"");
    PublishOutcome outcome = publish(replyOn());
    assertEquals(PolicyCase.WRAPPER_REMOVED, outcome.policyCase());
    assertEquals(SCRIPT_NAME, server.getActive());
    assertEquals(ExoSieveScript.sha256(replyOn().toScript()), outcome.scriptHash());
  }

  /**
   * A wrapper another client edited is no longer eXo's to regenerate: refused, nothing
   * written.
   *
   * @throws Exception on failure
   */
  @Test
  public void testAWrapperEditedOutsideIsRefused() throws Exception {
    server.script(FOREIGN, FILTERS_ONLY, false)
          .script(WRAPPER_NAME, SieveScriptPolicy.wrapper(FOREIGN, true) + "discard;\r\n", true);
    ServerRuleConflictException e = assertThrows(ServerRuleConflictException.class, () -> publish(replyOn()));
    assertEquals(ServerRuleConflictException.MODIFIED_OUTSIDE, e.getMessage());
    assertNoWrite();
  }

  /**
   * A script the server refuses to compile is never stored.
   *
   * @throws Exception on failure
   */
  @Test
  public void testACheckScriptRefusalWritesNothing() throws Exception {
    server.refuse("CHECKSCRIPT", "NO \"line 4: error\"");
    ManageSieveException e = assertThrows(ManageSieveException.class, () -> publish(replyOn()));
    assertEquals(Kind.REFUSED, e.getKind());
    assertNoWrite();
  }

  /**
   * On a server without CHECKSCRIPT the script is published without it.
   *
   * @throws Exception on failure
   */
  @Test
  public void testPublishesWithoutCheckScriptWhenNotAdvertised() throws Exception {
    server.advertiseVersion(false);
    assertEquals(PolicyCase.NO_ACTIVE_SCRIPT, publish(replyOn()).policyCase());
    assertEquals(List.of(), server.getCommands("CHECKSCRIPT"));
  }

  /**
   * The ordering rule: eXo first while its script files and stops nothing -- a reply, a
   * rule that only flags, a disabled filing rule -- theirs first once an enabled rule
   * files or stops; the wrapper text follows it.
   */
  @Test
  public void testWrapperOrderingRule() {
    assertTrue(SieveScriptPolicy.exoFirst(replyOn()));
    assertTrue(SieveScriptPolicy.exoFirst(replyOn().withRules(List.of(ExoSieveScriptTest.listsRead()))));
    ServerRule filing = ExoSieveScriptTest.acmeInvoices();
    ServerRule disabled = new ServerRule("1", "Off", false, true, filing.conditions(), filing.actions(), true);
    assertTrue(SieveScriptPolicy.exoFirst(replyOn().withRules(List.of(disabled))));
    assertFalse(SieveScriptPolicy.exoFirst(replyOn().withRules(List.of(filing))));
    assertEquals("# exo-managed-wrapper-v1\r\nrequire [\"include\"];\r\ninclude :personal \"a\\\"b\";\r\n"
        + "include :personal \"exo-rules\";\r\n", SieveScriptPolicy.wrapper("a\"b", false));
  }

  /**
   * The wrapper eXo generates reads back, in both orders and with an escaped name.
   *
   * @throws Exception on failure
   */
  @Test
  public void testWrapperReadsBack() throws Exception {
    assertEquals("a\"b", SieveScriptPolicy.wrappedScript(SieveScriptPolicy.wrapper("a\"b", true)));
    assertEquals(FOREIGN, SieveScriptPolicy.wrappedScript(SieveScriptPolicy.wrapper(FOREIGN, false)));
    assertThrows(ServerRuleConflictException.class, () -> SieveScriptPolicy.wrappedScript("keep;"));
    assertThrows(ServerRuleConflictException.class,
                 () -> SieveScriptPolicy.wrappedScript(SieveScriptPolicy.wrapper(WRAPPER_NAME, true)));
  }

  /**
   * The write guard refuses every name but eXo's two. Called directly: no path of
   * {@code publish} hands it a foreign name today — the guard is the defence against a
   * future one, and the invariant check after every scenario is what watches the paths
   * that exist.
   */
  @Test
  public void testWriteGuard() {
    assertEquals(SCRIPT_NAME, SieveScriptPolicy.own(SCRIPT_NAME));
    assertEquals(WRAPPER_NAME, SieveScriptPolicy.own(WRAPPER_NAME));
    assertThrows(IllegalStateException.class, () -> SieveScriptPolicy.own(FOREIGN));
    assertThrows(IllegalStateException.class, () -> SieveScriptPolicy.own(null));
  }

  /**
   * Publishes through an authenticated client, then logs out.
   *
   * @param script the script
   * @return the outcome
   * @throws Exception on failure
   */
  private PublishOutcome publish(ExoSieveScript script) throws Exception {
    ManageSieveClient client = server.authenticatedClient();
    try {
      return policy.publish(client, script);
    } finally {
      client.logout();
    }
  }

  /**
   * Asserts that no script was stored, activated or deleted.
   */
  private void assertNoWrite() {
    assertEquals(List.of(), server.getCommands("PUTSCRIPT"));
    assertEquals(List.of(), server.getCommands("SETACTIVE"));
    assertEquals(List.of(), server.getCommands("DELETESCRIPT"));
  }

  /**
   * The commands with one of the verbs, PUTSCRIPT reduced to its verb.
   *
   * @param verbs the verbs
   * @return the commands, in order
   */
  private List<String> verbs(String... verbs) {
    List<String> wanted = List.of(verbs);
    return server.getCommands()
                 .stream()
                 .filter(command -> wanted.contains(command.split(" ")[0]))
                 .map(command -> command.startsWith("PUTSCRIPT") || command.startsWith("CHECKSCRIPT") ? command.split(" ")[0]
                                                                                                      : command)
                 .toList();
  }

  /**
   * A reply switched on, no window.
   *
   * @return the script
   */
  private static ExoSieveScript replyOn() {
    return ExoSieveScript.empty().withVacation(new Vacation(true, null, null, null, "Away", "Back on Monday", "exo-vacation-1", 7));
  }

  /**
   * The same reply switched off.
   *
   * @return the script
   */
  private static ExoSieveScript replyOff() {
    return ExoSieveScript.empty()
                         .withVacation(new Vacation(false, null, null, null, "Away", "Back on Monday", "exo-vacation-1", 7));
  }
}
