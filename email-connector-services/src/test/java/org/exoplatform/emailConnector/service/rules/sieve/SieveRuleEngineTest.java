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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import javax.mail.PasswordAuthentication;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.exoplatform.commons.exception.ObjectNotFoundException;
import org.exoplatform.emailConnector.exception.ServerRuleConflictException;
import org.exoplatform.emailConnector.exception.ServerRuleUnavailableException;
import org.exoplatform.emailConnector.exception.ServerRuleUnsupportedException;
import org.exoplatform.emailConnector.model.EmailConnector;
import org.exoplatform.emailConnector.model.ForwardingSetting;
import org.exoplatform.emailConnector.model.ForwardingState;
import org.exoplatform.emailConnector.model.HopRef;
import org.exoplatform.emailConnector.model.ReconcileReport;
import org.exoplatform.emailConnector.model.ServerRule;
import org.exoplatform.emailConnector.model.ServerRuleCapabilities;
import org.exoplatform.emailConnector.model.ServerRuleSet;
import org.exoplatform.emailConnector.model.ServerRulesState;
import org.exoplatform.emailConnector.model.ServerVacation;
import org.exoplatform.emailConnector.model.VacationSetting;
import org.exoplatform.emailConnector.model.VacationState;
import org.exoplatform.emailConnector.provider.EmailCredentialsResolver;
import org.exoplatform.emailConnector.service.acl.MailboxAclSession;

/**
 * The Sieve engine against a ManageSieve server over a real socket and TLS: the reply's
 * read and write, the handle's lifetime, the states it reports, and the failures it maps
 * to codes a user can act on.
 */
public class SieveRuleEngineTest {

  private static final long     CONNECTOR_ID = 91L;

  private static final String   FOREIGN      = "roundcube";

  private FakeManageSieveServer server;

  private SieveRuleEngine       engine;

  private MailboxAclSession     session;

  private long                  now          = 1_790_000_000_000L;

  /**
   * Starts a server, points the preset at it, and builds the caller's session with the
   * server's login and password as its mail credentials.
   *
   * @throws Exception when the server cannot start
   */
  @BeforeEach
  public void setUp() throws Exception {
    server = new FakeManageSieveServer();
    start(FakeManageSieveServer.clientTlsFactory());
  }

  /**
   * Builds the engine over a connector with a TLS layer.
   *
   * @param factory the client's TLS layer
   */
  private void start(javax.net.ssl.SSLSocketFactory factory) {
    System.setProperty(ManageSieveEndpoint.HOST_PROPERTY + "." + CONNECTOR_ID, "localhost");
    System.setProperty(ManageSieveEndpoint.PORT_PROPERTY + "." + CONNECTOR_ID, String.valueOf(server.getPort()));
    ManageSieveConnector connector = new ManageSieveConnector(mock(EmailCredentialsResolver.class));
    connector.configure(factory, 5000, 5000, 10000);
    engine = new SieveRuleEngine(connector, new SieveScriptPolicy());
    engine.setClock(new Clock() {
      /**
       * The zone, UTC.
       *
       * @return UTC
       */
      @Override
      public ZoneOffset getZone() {
        return ZoneOffset.UTC;
      }

      /**
       * The same clock: the zone does not matter to a handle.
       *
       * @param zone ignored
       * @return this clock
       */
      @Override
      public Clock withZone(java.time.ZoneId zone) {
        return this;
      }

      /**
       * The test's current instant, which a test moves forward.
       *
       * @return the instant
       */
      @Override
      public Instant instant() {
        return Instant.ofEpochMilli(now);
      }
    });
    EmailConnector preset = new EmailConnector();
    preset.setId(CONNECTOR_ID);
    preset.setAuthProviderName("personal");
    session = new MailboxAclSession(preset,
                                    "alice",
                                    FakeManageSieveServer.LOGIN,
                                    null,
                                    null,
                                    () -> new PasswordAuthentication(FakeManageSieveServer.LOGIN,
                                                                     FakeManageSieveServer.PASSWORD));
  }

  /**
   * Stops the server and clears the properties.
   */
  @AfterEach
  public void tearDown() {
    server.close();
    System.clearProperty(ManageSieveEndpoint.HOST_PROPERTY + "." + CONNECTOR_ID);
    System.clearProperty(ManageSieveEndpoint.PORT_PROPERTY + "." + CONNECTOR_ID);
  }

  /**
   * An account with no script holds no reply.
   *
   * @throws Exception on failure
   */
  @Test
  public void testNothingOnTheServerReadsNone() throws Exception {
    ServerVacation read = engine.readVacation(session);
    assertEquals(VacationState.NONE, read.state());
    assertNull(read.vacation());
  }

  /**
   * Switching a reply on stores eXo's script, activates it, and reads back as eXo's own,
   * the hash being that of the text the server stores.
   *
   * @throws Exception on failure
   */
  @Test
  public void testSwitchingOnPublishesAndReadsBackAsOwn() throws Exception {
    ServerVacation written = engine.writeVacation(session, reply(true, "Back on Monday"), 7, null);
    assertEquals(VacationState.OWN, written.state());
    assertEquals("exo-rules", server.getActive());
    String stored = server.getScripts().get("exo-rules");
    assertTrue(stored.contains("vacation :days 7"), stored);
    assertTrue(stored.contains(":zone \"+0200\""), stored);
    assertEquals(ExoSieveScript.sha256(stored), written.scriptHash());
    assertEquals("Back on Monday", written.vacation().getText());
    assertEquals(VacationSetting.Source.EXO, written.vacation().getSource());
    assertEquals(VacationState.OWN, engine.readVacation(session).state());
  }

  /**
   * Editing a reply that stays on keeps its handle, so senders already answered are not
   * answered again; switching it off then on gives it a new one, so they are.
   *
   * @throws Exception on failure
   */
  @Test
  public void testTheHandleLivesAsLongAsTheReplyStaysOn() throws Exception {
    engine.writeVacation(session, reply(true, "First text"), 7, null);
    String first = handleOf(server.getScripts().get("exo-rules"));
    now += 60_000;
    engine.writeVacation(session, reply(true, "Edited text"), 7, null);
    assertEquals(first, handleOf(server.getScripts().get("exo-rules")));
    now += 60_000;
    engine.writeVacation(session, reply(false, "Edited text"), 7, null);
    assertEquals(first, handleOf(server.getScripts().get("exo-rules")));
    now += 60_000;
    engine.writeVacation(session, reply(true, "Edited text"), 7, null);
    assertNotEquals(first, handleOf(server.getScripts().get("exo-rules")));
  }

  /**
   * A reply switched off keeps its text in eXo's script and emits no vacation.
   *
   * @throws Exception on failure
   */
  @Test
  public void testSwitchingOffKeepsTheTextAndSendsNothing() throws Exception {
    engine.writeVacation(session, reply(true, "Away"), 7, null);
    ServerVacation off = engine.writeVacation(session, reply(false, "Away"), 7, null);
    String stored = server.getScripts().get("exo-rules");
    assertFalse(stored.contains("vacation :days"), stored);
    assertEquals(VacationState.OWN, off.state());
    assertFalse(off.vacation().isEnabled());
    assertEquals("Away", off.vacation().getText());
  }

  /**
   * Switching a reply off while another client's script runs stores eXo's script without
   * activating anything: no wrapper is created around the other script for a script that
   * sends nothing, and the other script stays the active one.
   *
   * @throws Exception on failure
   */
  @Test
  public void testSwitchingOffWhileAnotherScriptRunsNeverWraps() throws Exception {
    String exoScript = ExoSieveScript.empty()
                                     .withVacation(new ExoSieveScript.Vacation(true, null, null, null, "Out", "Away", "exo-vacation-1", 7))
                                     .toScript();
    server.script("exo-rules", exoScript, false);
    server.script(FOREIGN, "require [\"fileinto\"];\r\nfileinto \"Lists\";\r\n", true);
    ServerVacation before = engine.readVacation(session);
    assertEquals(VacationState.INACTIVE, before.state());
    ServerVacation off = engine.writeVacation(session, reply(false, "Away"), 7, null);
    assertEquals(FOREIGN, server.getActive());
    assertFalse(server.getScripts().containsKey("exo-main"));
    assertTrue(server.getCommands("SETACTIVE").isEmpty());
    assertFalse(off.vacation().isEnabled());
  }

  /**
   * Another client's active script that carries a vacation is reported as managed
   * elsewhere, named, and switching eXo's on is refused without a write.
   *
   * @throws Exception on failure
   */
  @Test
  public void testAForeignReplyIsManagedElsewhere() throws Exception {
    server.script(FOREIGN, "require [\"vacation\"];\r\nvacation \"Away\";\r\n", true);
    ServerVacation read = engine.readVacation(session);
    assertEquals(VacationState.ELSEWHERE, read.state());
    assertEquals(FOREIGN, read.foreignScriptName());
    ServerRuleConflictException e = assertThrows(ServerRuleConflictException.class,
                                                 () -> engine.writeVacation(session, reply(true, "Mine"), 7, null));
    assertEquals(ServerRuleConflictException.MANAGED_ELSEWHERE, e.getMessage());
    assertTrue(server.getCommands("PUTSCRIPT").isEmpty());
  }

  /**
   * A foreign active script without a vacation is not "managed elsewhere": eXo's reply is
   * published next to it through the wrapper.
   *
   * @throws Exception on failure
   */
  @Test
  public void testAForeignScriptWithoutReplyIsWrapped() throws Exception {
    server.script(FOREIGN, "require [\"fileinto\"];\r\nfileinto \"Lists\";\r\n", true);
    assertEquals(VacationState.NONE, engine.readVacation(session).state());
    ServerVacation written = engine.writeVacation(session, reply(true, "Mine"), 7, null);
    assertEquals("exo-main", server.getActive());
    assertEquals(VacationState.OWN, written.state());
  }

  /**
   * A script eXo last wrote with another hash is not overwritten without "Re-publish".
   *
   * @throws Exception on failure
   */
  @Test
  public void testAScriptChangedOutsideIsNotOverwrittenUnlessRepublished() throws Exception {
    ServerVacation written = engine.writeVacation(session, reply(true, "Mine"), 7, null);
    String edited = server.getScripts().get("exo-rules") + "# edited in another client\r\n";
    server.script("exo-rules", edited, true);
    ServerRuleConflictException e = assertThrows(ServerRuleConflictException.class,
                                                 () -> engine.writeVacation(session,
                                                                            reply(true, "Mine again"),
                                                                            7,
                                                                            written.scriptHash()));
    assertEquals(ServerRuleConflictException.MODIFIED_OUTSIDE, e.getMessage());
    assertEquals(edited, server.getScripts().get("exo-rules"));
    ServerVacation republished = engine.writeVacation(session, reply(true, "Mine again"), 7, null);
    assertEquals("Mine again", republished.vacation().getText());
  }

  /**
   * The same hash as eXo last wrote lets the write through.
   *
   * @throws Exception on failure
   */
  @Test
  public void testTheExpectedHashLetsTheWriteThrough() throws Exception {
    ServerVacation written = engine.writeVacation(session, reply(true, "Mine"), 7, null);
    ServerVacation again = engine.writeVacation(session, reply(true, "Mine, edited"), 7, written.scriptHash());
    assertEquals("Mine, edited", again.vacation().getText());
  }

  /**
   * A server without {@code vacation} cannot hold a reply: refused before any write, and
   * the probe says so per element.
   *
   * @throws Exception on failure
   */
  @Test
  public void testAServerWithoutVacationIsUnsupported() throws Exception {
    server.sieveExtensions("fileinto include");
    assertThrows(ServerRuleUnsupportedException.class, () -> engine.writeVacation(session, reply(true, "Mine"), 7, null));
    assertTrue(server.getCommands("PUTSCRIPT").isEmpty());
    ServerRuleCapabilities capabilities = engine.probe(session);
    assertFalse(capabilities.isSupported(ServerRuleCapabilities.VACATION));
  }

  /**
   * The probe reports the vacation elements and whether another client's script runs.
   *
   * @throws Exception on failure
   */
  @Test
  public void testTheProbeReportsTheReplyAndTheConflict() throws Exception {
    ServerRuleCapabilities alone = engine.probe(session);
    assertTrue(alone.isSupported(ServerRuleCapabilities.VACATION));
    assertTrue(alone.isSupported(ServerRuleCapabilities.VACATION_DATE_WINDOW));
    assertFalse(alone.publishConflict());
    server.script(FOREIGN, "keep;\r\n", true);
    assertTrue(engine.probe(session).publishConflict());
  }

  /**
   * A certificate for another host name is reported as such, so the administrator knows
   * to set {@code email.connector.sieve.host}, rather than as "unreachable".
   *
   * @throws Exception on failure
   */
  @Test
  public void testACertificateForAnotherHostSaysSo() throws Exception {
    server.close();
    server = new FakeManageSieveServer(FakeManageSieveServer.OTHER_HOST_KEYSTORE);
    start(FakeManageSieveServer.clientTlsFactory(FakeManageSieveServer.OTHER_HOST_KEYSTORE));
    ServerRuleUnavailableException e = assertThrows(ServerRuleUnavailableException.class, () -> engine.readVacation(session));
    assertEquals(ServerRuleUnavailableException.TLS_HOST_NAME, e.getMessage());
  }

  /**
   * A server that cannot be reached is "unreachable", never a certificate problem.
   *
   * @throws Exception on failure
   */
  @Test
  public void testAClosedServerIsUnreachable() throws Exception {
    server.close();
    ServerRuleUnavailableException e = assertThrows(ServerRuleUnavailableException.class, () -> engine.readVacation(session));
    assertEquals(ServerRuleUnavailableException.SERVER_UNREACHABLE, e.getMessage());
  }

  /**
   * Refused credentials, and no credentials at all, are an authentication failure.
   *
   * @throws Exception on failure
   */
  @Test
  public void testRefusedCredentialsAreAnAuthenticationFailure() throws Exception {
    server.acceptPassword("another-password");
    ServerRuleUnavailableException e = assertThrows(ServerRuleUnavailableException.class, () -> engine.readVacation(session));
    assertEquals(ServerRuleUnavailableException.AUTHENTICATION, e.getMessage());
    MailboxAclSession noMaterial = new MailboxAclSession(session.connector(), "alice", "alice", null, null, () -> null);
    e = assertThrows(ServerRuleUnavailableException.class, () -> engine.readVacation(noMaterial));
    assertEquals(ServerRuleUnavailableException.AUTHENTICATION, e.getMessage());
  }

  /**
   * A text at the limit once each line break counts as CRLF round-trips as eXo's own;
   * one character more is refused before anything is written, so eXo never publishes a
   * header it cannot read back.
   *
   * @throws Exception on failure
   */
  @Test
  public void testATextAtTheStoredLimitReadsBackAsOwn() throws Exception {
    String line = "x".repeat(38) + "\n";
    String atLimit = line.repeat(100) + "x".repeat(4000 - 100 * 40);
    ServerVacation written = engine.writeVacation(session, reply(true, atLimit), 7, null);
    assertEquals(VacationState.OWN, written.state());
    assertEquals(atLimit, written.vacation().getText());
    int puts = server.getCommands("PUTSCRIPT").size();
    assertThrows(IllegalArgumentException.class, () -> engine.writeVacation(session, reply(true, atLimit + "x"), 7, null));
    assertEquals(puts, server.getCommands("PUTSCRIPT").size());
  }

  /**
   * A nameless active script -- what Stalwart's JMAP out-of-office creates (V0 spike,
   * row 22) -- can be neither read nor included by name: it is a reply managed
   * elsewhere whatever the server answers for it, recall first. The fake answers a
   * reading without the token, so only the name decides.
   *
   * @throws Exception on failure
   */
  @Test
  public void testANamelessActiveScriptIsManagedElsewhere() throws Exception {
    server.script("", "keep;\r\n", true);
    ServerVacation read = engine.readVacation(session);
    assertEquals(VacationState.ELSEWHERE, read.state());
    assertEquals("", read.foreignScriptName());
  }

  /**
   * eXo's wrapper around a script that was deleted fails at delivery: eXo's reply reads
   * as no longer running.
   *
   * @throws Exception on failure
   */
  @Test
  public void testAWrapperAroundADeletedScriptIsInactive() throws Exception {
    server.script(FOREIGN, "require [\"fileinto\"];\r\nfileinto \"Lists\";\r\n", true);
    engine.writeVacation(session, reply(true, "Mine"), 7, null);
    assertEquals("exo-main", server.getActive());
    server.removeScript(FOREIGN);
    assertEquals(VacationState.INACTIVE, engine.readVacation(session).state());
  }

  /**
   * eXo's wrapper changed outside eXo reads as modified, naming the wrapper.
   *
   * @throws Exception on failure
   */
  @Test
  public void testAModifiedWrapperIsNamed() throws Exception {
    server.script(FOREIGN, "require [\"fileinto\"];\r\nfileinto \"Lists\";\r\n", true);
    engine.writeVacation(session, reply(true, "Mine"), 7, null);
    server.script("exo-main", server.getScripts().get("exo-main") + "keep;\r\n", true);
    ServerVacation read = engine.readVacation(session);
    assertEquals(VacationState.MODIFIED, read.state());
    assertEquals("exo-main", read.foreignScriptName());
  }

  // ---------------------------------------------------------------------------------
  // The forward, read only (EXO-90650): the running foreign script and its includes are
  // scanned for redirect, never parsed, and nothing is ever written.
  // ---------------------------------------------------------------------------------

  /** The ManageSieve verbs that change what the server holds. */
  private static final List<String> WRITE_VERBS = List.of("PUTSCRIPT", "SETACTIVE", "DELETESCRIPT", "RENAMESCRIPT");

  /**
   * Asserts the server received no command that writes, over the whole conversation.
   */
  private void assertNothingWritten() {
    for (String verb : WRITE_VERBS) {
      assertTrue(server.getCommands(verb).isEmpty(), verb + " was sent: " + server.getCommands());
    }
  }

  /**
   * How many commands that write the server received so far.
   *
   * @return the count, over every write verb
   */
  private int writeCount() {
    return WRITE_VERBS.stream().mapToInt(verb -> server.getCommands(verb).size()).sum();
  }

  /**
   * Another client's active script holding a redirect answers "a forward may be
   * configured by" that script, without the destination, and writes nothing.
   *
   * @throws Exception on failure
   */
  @Test
  public void testAForeignRedirectMayForward() throws Exception {
    server.script(FOREIGN, "require [\"copy\"];\nredirect :copy \"bob@stalwart.local\";\n", true);
    ForwardingSetting forwarding = engine.readForwarding(session);
    assertEquals(ForwardingState.MAY_FORWARD_BY_SCRIPT, forwarding.state());
    assertEquals(FOREIGN, forwarding.scriptName());
    assertTrue(forwarding.destinations().isEmpty());
    assertNull(forwarding.keepCopy());
    assertNothingWritten();
  }

  /**
   * A foreign script without a redirect, or with the word only in a comment, forwards
   * nothing; a script that is stored but not active is not what runs, so it is not
   * scanned.
   *
   * @throws Exception on failure
   */
  @Test
  public void testNoRedirectForwardsNothing() throws Exception {
    server.script(FOREIGN, "require [\"fileinto\"];\n# redirect \"x@y\";\nfileinto \"Archive\";\n", true);
    server.script("dormant", "redirect \"bob@stalwart.local\";\n", false);
    assertEquals(ForwardingState.NONE, engine.readForwarding(session).state());
    assertNothingWritten();
  }

  /**
   * No active script forwards nothing.
   *
   * @throws Exception on failure
   */
  @Test
  public void testNoActiveScriptForwardsNothing() throws Exception {
    assertEquals(ForwardingSetting.none(), engine.readForwarding(session));
    assertNothingWritten();
  }

  /**
   * A redirect in a personal script the active one includes is found too, one level
   * down, and the active script is the one named.
   *
   * @throws Exception on failure
   */
  @Test
  public void testARedirectOneIncludeDownMayForward() throws Exception {
    server.script("forward", "redirect \"bob@stalwart.local\";\n", false);
    server.script(FOREIGN, "require [\"include\"];\ninclude :personal \"forward\";\n", true);
    ForwardingSetting forwarding = engine.readForwarding(session);
    assertEquals(ForwardingState.MAY_FORWARD_BY_SCRIPT, forwarding.state());
    assertEquals(FOREIGN, forwarding.scriptName());
    assertNothingWritten();
  }

  /**
   * Next to eXo's reply, in eXo's wrapper, the script scanned and named is the other
   * client's one the wrapper includes -- not eXo's, not the wrapper.
   *
   * @throws Exception on failure
   */
  @Test
  public void testBehindEXosWrapperTheIncludedScriptIsNamed() throws Exception {
    server.script(FOREIGN, "require [\"copy\"];\nredirect :copy \"bob@stalwart.local\";\n", true);
    engine.writeVacation(session, reply(true, "Back on Monday"), 7, null);
    assertEquals(ExoSieveScript.WRAPPER_NAME, server.getActive());
    int writes = writeCount();
    ForwardingSetting forwarding = engine.readForwarding(session);
    assertEquals(ForwardingState.MAY_FORWARD_BY_SCRIPT, forwarding.state());
    assertEquals(FOREIGN, forwarding.scriptName());
    assertEquals(writes, writeCount());
  }

  /**
   * Only eXo's own script running forwards nothing: its generator emits no redirect, and
   * the read does not scan it -- a user's reply that mentions the word is not a forward.
   *
   * @throws Exception on failure
   */
  @Test
  public void testEXosOwnScriptNeverForwards() throws Exception {
    engine.writeVacation(session, reply(true, "Mail is not redirected while I am away"), 7, null);
    assertEquals(ExoSieveScript.SCRIPT_NAME, server.getActive());
    assertEquals(ForwardingState.NONE, engine.readForwarding(session).state());
  }

  /**
   * eXo's generator never emits a redirect command in this phase: whatever the user types,
   * once comments and quoted strings are set aside, eXo's script holds no redirect.
   */
  @Test
  public void testTheGeneratorEmitsNoRedirect() {
    ExoSieveScript script = ExoSieveScript.empty()
                                          .withVacation(new ExoSieveScript.Vacation(true,
                                                                                    java.time.LocalDate.of(2026, 10, 1),
                                                                                    java.time.LocalDate.of(2026, 10, 15),
                                                                                    "Europe/Paris",
                                                                                    "\"; redirect \"x@evil.example\"; #",
                                                                                    "redirect \"x@evil.example\";\n/* */ redirect",
                                                                                    "exo-vacation-1",
                                                                                    7));
    String code = SieveTokenScan.withoutComments(script.toScript()).replaceAll("\"(?:[^\"\\\\]|\\\\.)*\"", "\"\"");
    assertFalse(SieveTokenScan.containsWord(code, SieveScriptPolicy.REDIRECT_TOKEN), code);
    assertTrue(SieveTokenScan.containsWord(code, SieveScriptPolicy.VACATION_TOKEN), code);
  }

  /**
   * The first rule on an empty account gets reference 1, is published and activated, and
   * reads back as eXo's own, running, with the hash of the stored text.
   *
   * @throws Exception on failure
   */
  @Test
  public void testTheFirstRuleIsPublished() throws Exception {
    ServerRuleSet written = engine.saveRule(session, ExoSieveScriptTest.acmeInvoices().withRef(null), null);
    assertEquals("exo-rules", server.getActive());
    String stored = server.getScripts().get("exo-rules");
    assertTrue(stored.contains("# exo-rule 1\r\n"), stored);
    // The fake advertises Stalwart's line, mailbox included: the filing is guarded.
    assertTrue(stored.contains("  if mailboxexists \"Accounting\" {\r\n    fileinto \"Accounting\";\r\n  }\r\n  stop;\r\n}"), stored);
    assertTrue(stored.contains("require [\"fileinto\", \"mailbox\", \"imap4flags\", \"encoded-character\"];"), stored);
    assertEquals(1, server.getCommands("CHECKSCRIPT").size());
    assertEquals(ServerRulesState.OWN, written.state());
    assertEquals(List.of("1"), written.rules().stream().map(ServerRule::ref).toList());
    assertEquals(ExoSieveScript.sha256(stored), written.scriptHash());
    assertEquals(written, engine.listRules(session));
  }

  /**
   * The reply and the rules live in one script: saving a rule keeps the reply, its
   * handle and its text; writing the reply -- on, edited, off -- keeps the rules section
   * byte for byte, where the reply's write used to refuse a script holding rules.
   *
   * @throws Exception on failure
   */
  @Test
  public void testTheReplyAndTheRulesKeepEachOther() throws Exception {
    ServerVacation on = engine.writeVacation(session, reply(true, "Away"), 7, null);
    String handle = handleOf(server.getScripts().get("exo-rules"));
    ServerRuleSet rules = engine.saveRule(session, ExoSieveScriptTest.acmeInvoices().withRef(null), on.scriptHash());
    String withRules = server.getScripts().get("exo-rules");
    assertEquals(handle, handleOf(withRules));
    assertTrue(withRules.indexOf("# exo-vacation") < withRules.indexOf("# exo-rule 1"), withRules);
    assertTrue(withRules.contains("require [\"vacation\", \"date\", \"relational\", \"fileinto\", \"mailbox\", \"imap4flags\""), withRules);
    String section = withRules.substring(withRules.indexOf(ExoSieveScript.RULES_MARKER));
    ServerVacation edited = engine.writeVacation(session, reply(true, "Away, edited"), 7, rules.scriptHash());
    String afterEdit = server.getScripts().get("exo-rules");
    assertEquals(section, afterEdit.substring(afterEdit.indexOf(ExoSieveScript.RULES_MARKER)));
    assertEquals(VacationState.OWN, edited.state());
    assertEquals("Away, edited", edited.vacation().getText());
    engine.writeVacation(session, reply(false, "Away, edited"), 7, edited.scriptHash());
    String afterOff = server.getScripts().get("exo-rules");
    assertEquals(section, afterOff.substring(afterOff.indexOf(ExoSieveScript.RULES_MARKER)));
    assertFalse(afterOff.contains("vacation :days"), afterOff);
    assertEquals(1, engine.listRules(session).rules().size());
    assertEquals("exo-rules", server.getActive());
  }

  /**
   * A rule saved under its reference keeps its place; a deleted one is gone and the rest
   * is written back; an unknown reference is not found, and nothing is written.
   *
   * @throws Exception on failure
   */
  @Test
  public void testReplaceInPlaceAndDelete() throws Exception {
    engine.saveRule(session, ExoSieveScriptTest.acmeInvoices().withRef(null), null);
    engine.saveRule(session, ExoSieveScriptTest.listsRead().withRef(null), null);
    ServerRule renamed = new ServerRule("1",
                                        "Acme, renamed",
                                        true,
                                        true,
                                        ExoSieveScriptTest.acmeInvoices().conditions(),
                                        ExoSieveScriptTest.acmeInvoices().actions(),
                                        false);
    ServerRuleSet saved = engine.saveRule(session, renamed, null);
    assertEquals(List.of("Acme, renamed", "Lists read"), saved.rules().stream().map(ServerRule::name).toList());
    ServerRuleSet deleted = engine.deleteRule(session, "1", null);
    assertEquals(List.of("2"), deleted.rules().stream().map(ServerRule::ref).toList());
    assertFalse(server.getScripts().get("exo-rules").contains("fileinto"));
    int puts = server.getCommands("PUTSCRIPT").size();
    assertThrows(ObjectNotFoundException.class, () -> engine.deleteRule(session, "7", null));
    assertEquals(puts, server.getCommands("PUTSCRIPT").size());
    assertEquals("3", SieveRuleEngine.nextRef(List.of(ExoSieveScriptTest.listsRead())));
  }

  /**
   * eXo's script edited outside eXo is not overwritten by a rule's write, unless
   * re-published; a header eXo cannot read is not either.
   *
   * @throws Exception on failure
   */
  @Test
  public void testAScriptChangedOutsideRefusesARuleWrite() throws Exception {
    ServerRuleSet written = engine.saveRule(session, ExoSieveScriptTest.acmeInvoices().withRef(null), null);
    String edited = server.getScripts().get("exo-rules") + "# edited\r\n";
    server.script("exo-rules", edited, true);
    ServerRuleConflictException e = assertThrows(ServerRuleConflictException.class,
                                                 () -> engine.saveRule(session, ExoSieveScriptTest.listsRead(), written.scriptHash()));
    assertEquals(ServerRuleConflictException.MODIFIED_OUTSIDE, e.getMessage());
    assertThrows(ServerRuleConflictException.class, () -> engine.deleteRule(session, "1", written.scriptHash()));
    assertEquals(edited, server.getScripts().get("exo-rules"));
    ServerRuleSet republished = engine.publishRules(session, null);
    assertFalse(server.getScripts().get("exo-rules").contains("# edited"));
    assertEquals(1, republished.rules().size());
    server.script("exo-rules", "# exo-managed-v1: {\"v\":1,\"rules\":[{\"id\":1}]}\r\n", true);
    String unreadableHash = ExoSieveScript.sha256("# exo-managed-v1: {\"v\":1,\"rules\":[{\"id\":1}]}\r\n");
    assertEquals(ServerRulesState.UNREADABLE, engine.listRules(session).state());
    assertThrows(ServerRuleConflictException.class, () -> engine.saveRule(session, ExoSieveScriptTest.listsRead(), unreadableHash));
  }

  /**
   * Next to another client's script, the wrapper runs theirs first as soon as eXo's
   * script files or stops, and eXo's first while it only replies or flags -- including
   * when a filing rule joins a reply that was wrapped first.
   *
   * @throws Exception on failure
   */
  @Test
  public void testTheWrapperOrderFollowsWhatTheRulesDo() throws Exception {
    server.script(FOREIGN, "require [\"fileinto\"];\r\nfileinto \"Lists\";\r\n", true);
    engine.writeVacation(session, reply(true, "Away"), 7, null);
    assertEquals("exo-main", server.getActive());
    assertTrue(server.getScripts().get("exo-main").endsWith("include :personal \"exo-rules\";\r\ninclude :personal \"roundcube\";\r\n"));
    engine.saveRule(session, ExoSieveScriptTest.listsRead().withRef(null), null);
    assertTrue(server.getScripts().get("exo-main").endsWith("include :personal \"exo-rules\";\r\ninclude :personal \"roundcube\";\r\n"));
    ServerRuleSet filing = engine.saveRule(session, ExoSieveScriptTest.acmeInvoices().withRef(null), null);
    assertTrue(server.getScripts().get("exo-main").endsWith("include :personal \"roundcube\";\r\ninclude :personal \"exo-rules\";\r\n"),
               server.getScripts().get("exo-main"));
    assertEquals(ServerRulesState.OWN, filing.state());
    assertEquals(FOREIGN, filing.foreignScriptName());
    assertEquals("exo-main", server.getActive());
  }

  /**
   * A rule saved disabled while another client's script runs is stored and nothing is
   * activated; with eXo's script not running, the rules read as inactive, and
   * "Re-activate" wraps.
   *
   * @throws Exception on failure
   */
  @Test
  public void testADisabledRuleNeverActivatesAndReactivateWraps() throws Exception {
    server.script(FOREIGN, "keep;\r\n", true);
    ServerRule off = new ServerRule(null,
                                    "Off",
                                    false,
                                    true,
                                    ExoSieveScriptTest.acmeInvoices().conditions(),
                                    ExoSieveScriptTest.acmeInvoices().actions(),
                                    true);
    ServerRuleSet stored = engine.saveRule(session, off, null);
    assertEquals(FOREIGN, server.getActive());
    assertTrue(server.getCommands("SETACTIVE").isEmpty());
    assertFalse(server.getScripts().containsKey("exo-main"));
    assertEquals(ServerRulesState.INACTIVE, stored.state());
    assertEquals(FOREIGN, stored.foreignScriptName());
    ServerRuleSet active = engine.publishRules(session, stored.scriptHash());
    assertEquals("exo-main", server.getActive());
    assertEquals(ServerRulesState.OWN, active.state());
  }

  /**
   * Another client's active script on a server without {@code include}: an enabled rule
   * is refused, and nothing is written.
   *
   * @throws Exception on failure
   */
  @Test
  public void testWithoutIncludeAForeignScriptRefusesARule() throws Exception {
    server.sieveExtensions("fileinto imap4flags");
    server.script(FOREIGN, "keep;\r\n", true);
    ServerRuleConflictException e = assertThrows(ServerRuleConflictException.class,
                                                 () -> engine.saveRule(session, ExoSieveScriptTest.acmeInvoices().withRef(null), null));
    assertEquals(ServerRuleConflictException.SERVER_CONFLICT, e.getMessage());
    assertEquals(FOREIGN, e.getScriptName());
    assertTrue(server.getCommands("PUTSCRIPT").isEmpty());
  }

  /**
   * A server that does not advertise {@code fileinto} cannot hold a move: refused with
   * the extension's reason, before any write.
   *
   * @throws Exception on failure
   */
  @Test
  public void testARuleNeedsItsExtensions() throws Exception {
    server.sieveExtensions("imap4flags include");
    ServerRuleUnsupportedException e = assertThrows(ServerRuleUnsupportedException.class,
                                                    () -> engine.saveRule(session, ExoSieveScriptTest.acmeInvoices().withRef(null), null));
    assertEquals("emailConnector.rules.unsupported.sieveExtension.fileinto", e.getMessage());
    assertTrue(server.getCommands("PUTSCRIPT").isEmpty());
    engine.saveRule(session, ExoSieveScriptTest.listsRead().withRef(null), null);
    assertEquals(1, server.getCommands("PUTSCRIPT").size());
  }

  /**
   * Reconciliation keeps exactly the hops it is given: a missing or changed one is
   * written, one no longer given is removed, a user's rule stays in its place, and nothing
   * is written when nothing differs.
   *
   * @throws Exception on failure
   */
  @Test
  public void testReconcileKeepsExactlyTheHops() throws Exception {
    engine.saveRule(session, ExoSieveScriptTest.listsRead().withRef(null), null);
    HopRef first = new HopRef("hop-1", "Invoices", true, ExoSieveScriptTest.acmeInvoices().conditions(), "exo-filter-1", false);
    HopRef second = new HopRef("hop-2", "Reports", false, ExoSieveScriptTest.listsRead().conditions(), "exo-filter-2", true);
    ReconcileReport added = engine.reconcile(session, List.of(first, second), null);
    assertEquals(List.of("hop-1", "hop-2"), added.published());
    assertEquals(List.of(), added.removed());
    assertEquals(List.of("1", "hop-1", "hop-2"), added.rules().rules().stream().map(ServerRule::ref).toList());
    assertTrue(server.getScripts().get("exo-rules").contains("addflag \"exo-filter-1\";"));
    HopRef changed = new HopRef("hop-1", "Invoices", true, ExoSieveScriptTest.acmeInvoices().conditions(), "exo-filter-1", true);
    ReconcileReport update = engine.reconcile(session, List.of(changed), null);
    assertEquals(List.of("hop-1"), update.published());
    assertEquals(List.of("hop-2"), update.removed());
    assertEquals(List.of("1", "hop-1"), update.rules().rules().stream().map(ServerRule::ref).toList());
    int puts = server.getCommands("PUTSCRIPT").size();
    ReconcileReport same = engine.reconcile(session, List.of(changed), update.rules().scriptHash());
    assertFalse(same.changed());
    assertEquals(puts, server.getCommands("PUTSCRIPT").size());
  }

  /**
   * A filter that changes where it runs is swapped in one write: the write that
   * publishes a hop removes the user's rule it was, the write that removes a hop appends
   * the user's rule it becomes under a new reference, and a hop is never added as a
   * user's rule.
   *
   * @throws Exception on failure
   */
  @Test
  public void testASwapIsOneWrite() throws Exception {
    engine.saveRule(session, ExoSieveScriptTest.listsRead().withRef(null), null);
    HopRef hop = new HopRef("hop-1", "Invoices", true, ExoSieveScriptTest.acmeInvoices().conditions(), "exo-filter-1", false);
    int puts = server.getCommands("PUTSCRIPT").size();
    ReconcileReport toHop = engine.reconcile(session, List.of(hop), null, "1", null);
    assertEquals(puts + 1, server.getCommands("PUTSCRIPT").size(), "one write");
    assertEquals(List.of("hop-1"), toHop.rules().rules().stream().map(ServerRule::ref).toList());
    assertEquals(List.of("1"), toHop.removed());
    ReconcileReport toRule = engine.reconcile(session, List.of(), ExoSieveScriptTest.acmeInvoices().withRef(null), null, null);
    assertEquals(puts + 2, server.getCommands("PUTSCRIPT").size(), "one write");
    assertEquals(List.of("1"), toRule.rules().rules().stream().map(ServerRule::ref).toList(), "the next free reference");
    assertEquals(List.of("1"), toRule.published());
    assertEquals(List.of("hop-1"), toRule.removed());
    assertThrows(IllegalArgumentException.class, () -> engine.reconcile(session, List.of(), hop.toRule().withRef(null), null, null));
    assertEquals(puts + 2, server.getCommands("PUTSCRIPT").size());
  }

  /**
   * The user's rules and the hops keep to their own names: a reference the script does
   * not hold is not found, a hop is neither written nor replaced by a user's save, a
   * reconciliation never replaces a user's rule, and a hop's name starts with hop-.
   *
   * @throws Exception on failure
   */
  @Test
  public void testUserRulesAndHopsKeepApart() throws Exception {
    engine.saveRule(session, ExoSieveScriptTest.listsRead().withRef(null), null);
    assertThrows(ObjectNotFoundException.class, () -> engine.saveRule(session, ExoSieveScriptTest.acmeInvoices().withRef("7"), null));
    HopRef hop = new HopRef("hop-1", "Invoices", true, ExoSieveScriptTest.acmeInvoices().conditions(), "exo-filter-1", false);
    engine.reconcile(session, List.of(hop), null);
    int puts = server.getCommands("PUTSCRIPT").size();
    assertThrows(IllegalArgumentException.class, () -> engine.saveRule(session, ExoSieveScriptTest.listsRead().withRef("hop-1"), null));
    assertThrows(IllegalArgumentException.class, () -> engine.saveRule(session, hop.toRule().withRef(null), null));
    assertEquals(puts, server.getCommands("PUTSCRIPT").size());
    assertThrows(IllegalArgumentException.class, () -> new HopRef("2", "x", true, List.of(), "exo-filter-9", false).toRule());
    server.script("exo-rules",
                  ExoSieveScript.empty().withRules(List.of(ExoSieveScriptTest.listsRead().withRef("hop-2"))).toScript(),
                  true);
    HopRef clash = new HopRef("hop-2", "x", true, ExoSieveScriptTest.listsRead().conditions(), "exo-filter-2", false);
    assertThrows(IllegalArgumentException.class, () -> engine.reconcile(session, List.of(clash), null));
    assertEquals(puts, server.getCommands("PUTSCRIPT").size());
  }

  /**
   * Rule (5) through the rules path: with eXo's reply on, a rule's save next to another
   * client's script that carries a vacation is refused, and nothing is written.
   *
   * @throws Exception on failure
   */
  @Test
  public void testARuleSaveNextToAForeignReplyIsRefused() throws Exception {
    ServerVacation on = engine.writeVacation(session, reply(true, "Away"), 7, null);
    server.script(FOREIGN, "require [\"vacation\"];\r\nvacation \"Theirs\";\r\n", true);
    int puts = server.getCommands("PUTSCRIPT").size();
    ServerRuleConflictException e = assertThrows(ServerRuleConflictException.class,
                                                 () -> engine.saveRule(session, ExoSieveScriptTest.acmeInvoices().withRef(null), on.scriptHash()));
    assertEquals(ServerRuleConflictException.MANAGED_ELSEWHERE, e.getMessage());
    assertEquals(puts, server.getCommands("PUTSCRIPT").size());
    assertEquals(FOREIGN, server.getActive());
  }

  /**
   * Re-publish never writes an empty script over a header eXo cannot read -- the reply it
   * may hold would be lost -- and cannot rewrite a wrapper another client changed; both
   * answer "modified outside" and write nothing.
   *
   * @throws Exception on failure
   */
  @Test
  public void testRepublishNeverErasesWhatItCannotRead() throws Exception {
    String unreadable = "# exo-managed-v1: {\"v\":1,\"rules\":[{\"id\":1}]}\r\nrequire [\"vacation\"];\r\nvacation \"x\";\r\n";
    server.script("exo-rules", unreadable, true);
    assertEquals(ServerRulesState.UNREADABLE, engine.listRules(session).state());
    assertThrows(ServerRuleConflictException.class, () -> engine.publishRules(session, null));
    assertThrows(ServerRuleConflictException.class, () -> engine.saveRule(session, ExoSieveScriptTest.listsRead().withRef(null), null));
    assertEquals(unreadable, server.getScripts().get("exo-rules"));
    server.script("exo-rules", ExoSieveScript.empty().withRules(List.of(ExoSieveScriptTest.listsRead())).toScript(), false);
    server.script(FOREIGN, "keep;\r\n", false);
    server.script("exo-main", "require [\"include\"];\r\ninclude :personal \"roundcube\";\r\n", true);
    ServerRuleSet read = engine.listRules(session);
    assertEquals(ServerRulesState.MODIFIED, read.state());
    assertEquals("exo-main", read.foreignScriptName());
    int puts = server.getCommands("PUTSCRIPT").size();
    ServerRuleConflictException e = assertThrows(ServerRuleConflictException.class, () -> engine.publishRules(session, null));
    assertEquals("exo-main", e.getScriptName());
    assertEquals(puts, server.getCommands("PUTSCRIPT").size());
  }

  /**
   * A hop is the server half of one of eXo's own rules: deleting it by its reference is
   * refused like saving it, and nothing is written; a user's rule beside it still goes.
   *
   * @throws Exception on failure
   */
  @Test
  public void testAHopIsNotDeletedByItsReference() throws Exception {
    engine.saveRule(session, ExoSieveScriptTest.listsRead().withRef(null), null);
    HopRef hop = new HopRef("hop-1", "Invoices", true, ExoSieveScriptTest.acmeInvoices().conditions(), "exo-filter-1", false);
    engine.reconcile(session, List.of(hop), null);
    int puts = server.getCommands("PUTSCRIPT").size();
    IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> engine.deleteRule(session, "hop-1", null));
    assertEquals(ServerRule.INVALID_ACTION, e.getMessage());
    assertEquals(puts, server.getCommands("PUTSCRIPT").size());
    assertEquals(List.of("1", "hop-1"), engine.listRules(session).rules().stream().map(ServerRule::ref).toList());
    engine.deleteRule(session, "1", null);
    assertEquals(List.of("hop-1"), engine.listRules(session).rules().stream().map(ServerRule::ref).toList());
  }

  /**
   * The automatic reply never writes over a header eXo cannot read, not even on
   * "Re-publish": the rules it may hold would be dropped. The reply reads as changed
   * outside eXo in eXo's own script, named, so no "Re-publish" is offered, and a write is
   * refused under its own code, with nothing written.
   *
   * @throws Exception on failure
   */
  @Test
  public void testTheReplyNeverWritesOverAnUnreadableScript() throws Exception {
    String unreadable = "# exo-managed-v1: {\"v\":1,\"rules\":[{\"id\":1}]}\r\nrequire [\"fileinto\"];\r\nif true { fileinto \"Lists\"; }\r\n";
    server.script("exo-rules", unreadable, true);
    ServerVacation read = engine.readVacation(session);
    assertEquals(VacationState.MODIFIED, read.state());
    assertEquals("exo-rules", read.foreignScriptName());
    int puts = server.getCommands("PUTSCRIPT").size();
    ServerRuleConflictException e = assertThrows(ServerRuleConflictException.class,
                                                 () -> engine.writeVacation(session, reply(true, "Away"), 7, null));
    assertEquals(ServerRuleConflictException.UNREADABLE, e.getMessage(), "its own code: a readable script changed outside eXo keeps Re-publish");
    assertEquals("exo-rules", e.getScriptName());
    assertEquals(puts, server.getCommands("PUTSCRIPT").size());
    assertEquals(unreadable, server.getScripts().get("exo-rules"));
  }

  /**
   * The rules' states, read: none, a header eXo cannot read, and eXo's script not the one
   * running.
   *
   * @throws Exception on failure
   */
  @Test
  public void testTheRulesStates() throws Exception {
    assertEquals(ServerRuleSet.none(), engine.listRules(session));
    server.script("exo-rules", "require [\"fileinto\"];\r\n", true);
    assertEquals(ServerRulesState.UNREADABLE, engine.listRules(session).state());
    server.script("exo-rules", ExoSieveScript.empty().withRules(List.of(ExoSieveScriptTest.listsRead())).toScript(), false);
    server.script(FOREIGN, "keep;\r\n", true);
    ServerRuleSet inactive = engine.listRules(session);
    assertEquals(ServerRulesState.INACTIVE, inactive.state());
    assertEquals(FOREIGN, inactive.foreignScriptName());
    assertEquals(1, inactive.rules().size());
  }

  /**
   * A reply form value.
   *
   * @param enabled whether on
   * @param text the text
   * @return the value
   */
  private static VacationSetting reply(boolean enabled, String text) {
    return new VacationSetting(enabled, "2026-10-01", "2026-10-15", "Europe/Paris", "Out of office", text, 0, null);
  }

  /**
   * The handle a script's header carries.
   *
   * @param script the script text
   * @return the handle
   */
  private static String handleOf(String script) {
    return ExoSieveScript.parse(script).orElseThrow().getVacation().orElseThrow().handle();
  }
}
