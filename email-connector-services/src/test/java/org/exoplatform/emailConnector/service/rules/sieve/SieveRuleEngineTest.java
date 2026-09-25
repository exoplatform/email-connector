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

import org.exoplatform.emailConnector.exception.ServerRuleConflictException;
import org.exoplatform.emailConnector.exception.ServerRuleUnavailableException;
import org.exoplatform.emailConnector.exception.ServerRuleUnsupportedException;
import org.exoplatform.emailConnector.model.EmailConnector;
import org.exoplatform.emailConnector.model.ForwardingSetting;
import org.exoplatform.emailConnector.model.ForwardingState;
import org.exoplatform.emailConnector.model.ServerRuleCapabilities;
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
