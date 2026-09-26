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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import javax.mail.PasswordAuthentication;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.exoplatform.emailConnector.exception.ForwardingRefusedException;
import org.exoplatform.emailConnector.exception.ServerRuleConflictException;
import org.exoplatform.emailConnector.exception.ServerRuleUnsupportedException;
import org.exoplatform.emailConnector.model.EmailConnector;
import org.exoplatform.emailConnector.model.ForwardingSetting;
import org.exoplatform.emailConnector.model.ForwardingState;
import org.exoplatform.emailConnector.model.ServerAbsence;
import org.exoplatform.emailConnector.model.ServerForwarding;
import org.exoplatform.emailConnector.model.ServerRule;
import org.exoplatform.emailConnector.model.ServerRule.Action;
import org.exoplatform.emailConnector.model.ServerRule.Condition;
import org.exoplatform.emailConnector.model.VacationSetting;
import org.exoplatform.emailConnector.model.VacationState;
import org.exoplatform.emailConnector.provider.EmailCredentialsResolver;
import org.exoplatform.emailConnector.service.acl.MailboxAclSession;
import org.exoplatform.emailConnector.service.rules.ForwardingGuard;

/**
 * eXo's forward on a Sieve server, over a real socket and TLS (EXO-90656): written only
 * as {@code redirect :copy}, only to a destination the {@link ForwardingGuard} authorizes
 * for the caller, never next to another client's forward, never where the server would
 * not keep a copy or allows fewer redirects than one mail could meet -- and read back in
 * the same conversation as the reply.
 */
public class SieveRuleEngineForwardTest {

  private static final long     CONNECTOR_ID = 92L;

  private static final String   FOREIGN      = "roundcube";

  private static final String   BOB          = "bob@stalwart.local";

  /** The ManageSieve verbs that change what the server holds. */
  private static final List<String> WRITE_VERBS = List.of("PUTSCRIPT", "SETACTIVE", "DELETESCRIPT", "RENAMESCRIPT");

  private FakeManageSieveServer server;

  private SieveRuleEngine       engine;

  private MailboxAclSession     session;

  /** The destinations the guard authorizes, which a test changes. */
  private final Set<String>     authorized   = new HashSet<>(Set.of(BOB));

  /**
   * A server that advertises {@code copy}, as the live Stalwart does, and an engine whose
   * guard authorizes the test's destinations.
   *
   * @throws Exception when the server cannot start
   */
  @BeforeEach
  public void setUp() throws Exception {
    server = new FakeManageSieveServer().sieveExtensions(FakeManageSieveServer.STALWART_SIEVE + " copy");
    System.setProperty(ManageSieveEndpoint.HOST_PROPERTY + "." + CONNECTOR_ID, "localhost");
    System.setProperty(ManageSieveEndpoint.PORT_PROPERTY + "." + CONNECTOR_ID, String.valueOf(server.getPort()));
    ManageSieveConnector connector = new ManageSieveConnector(mock(EmailCredentialsResolver.class));
    connector.configure(FakeManageSieveServer.clientTlsFactory(), 5000, 5000, 10000);
    ForwardingGuard guard = mock(ForwardingGuard.class);
    when(guard.authorizedDestinations(any())).thenAnswer(invocation -> Set.copyOf(authorized));
    engine = new SieveRuleEngine(connector, new SieveScriptPolicy(), guard);
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
   * An authorized forward is published as {@code redirect :copy}, eXo's script active,
   * and reads back as eXo's own forward, a copy kept.
   *
   * @throws Exception on failure
   */
  @Test
  public void testAnAuthorizedForwardIsARedirectCopy() throws Exception {
    ServerForwarding written = engine.writeForwarding(session, BOB, null);
    String stored = server.getScripts().get(ExoSieveScript.SCRIPT_NAME);
    assertTrue(stored.contains("# exo-forward\r\nredirect :copy \"" + BOB + "\";\r\n"), stored);
    assertFalse(stored.replace("redirect :copy", "").contains("redirect"), stored);
    assertEquals(ExoSieveScript.SCRIPT_NAME, server.getActive());
    assertEquals(ExoSieveScript.sha256(stored), written.scriptHash());
    ForwardingSetting read = engine.readForwarding(session);
    assertEquals(ForwardingState.SERVER_FORWARD, read.state());
    assertEquals(List.of(BOB), read.destinations());
    assertEquals(Boolean.TRUE, read.keepCopy());
    assertTrue(read.managedByExo());
    assertNull(read.scriptName());
    assertEquals(read, written.forwarding());
  }

  /**
   * A destination the guard does not authorize is refused, and nothing reaches the
   * server -- whatever the caller passed.
   *
   * @throws Exception on failure
   */
  @Test
  public void testAnUnauthorizedDestinationWritesNothing() throws Exception {
    authorized.clear();
    assertEquals(ForwardingRefusedException.NOT_AUTHORIZED,
                 assertThrows(ForwardingRefusedException.class, () -> engine.writeForwarding(session, BOB, null)).getMessage());
    assertNothingWritten();
  }

  /**
   * A server that does not advertise {@code copy} holds no forward of eXo's: a plain
   * redirect would take the mail out of the mailbox.
   *
   * @throws Exception on failure
   */
  @Test
  public void testWithoutCopyNoForward() throws Exception {
    server.sieveExtensions(FakeManageSieveServer.STALWART_SIEVE);
    assertEquals(ServerRuleUnsupportedException.FORWARDING_UNSUPPORTED,
                 assertThrows(ServerRuleUnsupportedException.class, () -> engine.writeForwarding(session, BOB, null)).getMessage());
    assertNothingWritten();
  }

  /**
   * Another client's running script that redirects: eXo adds no forward next to it.
   *
   * @throws Exception on failure
   */
  @Test
  public void testNoForwardNextToAnotherClientsRedirect() throws Exception {
    server.script(FOREIGN, "require [\"copy\"];\r\nredirect :copy \"carol@stalwart.local\";\r\n", true);
    ServerRuleConflictException conflict = assertThrows(ServerRuleConflictException.class,
                                                        () -> engine.writeForwarding(session, BOB, null));
    assertEquals(ServerRuleConflictException.FORWARDED_ELSEWHERE, conflict.getMessage());
    assertEquals(FOREIGN, conflict.getScriptName());
    assertNothingWritten();
  }

  /**
   * A {@code notify} to a {@code mailto:} address also sends mail elsewhere: read as a
   * forward that may be configured by that script, and no forward of eXo's added next to
   * it.
   *
   * @throws Exception on failure
   */
  @Test
  public void testANotifyMayForward() throws Exception {
    server.script(FOREIGN, "require [\"enotify\"];\r\nnotify :method \"mailto:carol@elsewhere.example\" \"New mail\";\r\n", true);
    ForwardingSetting read = engine.readForwarding(session);
    assertEquals(ForwardingState.MAY_FORWARD_BY_SCRIPT, read.state());
    assertEquals(FOREIGN, read.scriptName());
    assertEquals(ServerRuleConflictException.FORWARDED_ELSEWHERE,
                 assertThrows(ServerRuleConflictException.class, () -> engine.writeForwarding(session, BOB, null)).getMessage());
    assertNothingWritten();
  }

  /**
   * A nameless active script (what Stalwart's JMAP clients create) can be neither read
   * nor included: no forward of eXo's next to it.
   *
   * @throws Exception on failure
   */
  @Test
  public void testNoForwardNextToANamelessScript() throws Exception {
    server.script("", "require [\"fileinto\"];\r\nfileinto \"Lists\";\r\n", true);
    assertEquals(ServerRuleConflictException.FORWARDED_ELSEWHERE,
                 assertThrows(ServerRuleConflictException.class, () -> engine.writeForwarding(session, BOB, null)).getMessage());
    assertNothingWritten();
  }

  /**
   * Next to another client's script that forwards nothing, the forward is wrapped with
   * it, eXo's script first: {@code redirect :copy} files nothing, so no {@code stop} of
   * theirs can skip it.
   *
   * @throws Exception on failure
   */
  @Test
  public void testNextToAForeignScriptTheForwardRunsFirst() throws Exception {
    server.script(FOREIGN, "require [\"fileinto\"];\r\nfileinto \"Lists\";\r\nstop;\r\n", true);
    engine.writeForwarding(session, BOB, null);
    assertEquals(ExoSieveScript.WRAPPER_NAME, server.getActive());
    assertEquals(SieveScriptPolicy.wrapper(FOREIGN, true), server.getScripts().get(ExoSieveScript.WRAPPER_NAME));
    ForwardingSetting read = engine.readForwarding(session);
    assertTrue(read.managedByExo());
    assertNull(read.scriptName());
  }

  /**
   * The reply's write keeps the forward, generated again with the caller's authorized
   * destinations; once the destination is no longer authorized (its domain dropped from
   * the allowed ones), no write can carry it on.
   *
   * @throws Exception on failure
   */
  @Test
  public void testOtherWritesKeepTheForwardOnlyWhileAuthorized() throws Exception {
    engine.writeForwarding(session, BOB, null);
    engine.writeVacation(session, reply("Back soon"), 7, null);
    String stored = server.getScripts().get(ExoSieveScript.SCRIPT_NAME);
    assertTrue(stored.contains("redirect :copy \"" + BOB + "\";"), stored);
    assertTrue(stored.contains("vacation :days 7"), stored);
    authorized.clear();
    int writes = writeCount();
    assertThrows(ForwardingRefusedException.class, () -> engine.writeVacation(session, reply("Back later"), 7, null));
    assertEquals(writes, writeCount());
    // Removing the forward is always possible, and keeps the reply.
    engine.writeForwarding(session, null, null);
    stored = server.getScripts().get(ExoSieveScript.SCRIPT_NAME);
    assertFalse(stored.contains("redirect"), stored);
    assertTrue(stored.contains("vacation :days 7"), stored);
    assertEquals(ForwardingState.NONE, engine.readForwarding(session).state());
  }

  /**
   * A server that stops advertising {@code copy} while eXo's forward is on: no write can
   * carry the forward on there -- the reply's write is refused as unsupported, nothing
   * written.
   *
   * @throws Exception on failure
   */
  @Test
  public void testAServerThatDropsCopyCarriesNoForwardOn() throws Exception {
    engine.writeForwarding(session, BOB, null);
    server.sieveExtensions(FakeManageSieveServer.STALWART_SIEVE);
    int writes = writeCount();
    assertEquals(ServerRuleUnsupportedException.FORWARDING_UNSUPPORTED,
                 assertThrows(ServerRuleUnsupportedException.class, () -> engine.writeVacation(session, reply("Back soon"), 7, null))
                                                                                                                                  .getMessage());
    assertEquals(writes, writeCount());
  }

  /**
   * Removing a forward that is not there writes nothing.
   *
   * @throws Exception on failure
   */
  @Test
  public void testRemovingNothingWritesNothing() throws Exception {
    ServerForwarding written = engine.writeForwarding(session, null, null);
    assertEquals(ForwardingState.NONE, written.forwarding().state());
    assertNothingWritten();
  }

  /**
   * One mail may meet the forward and a rule that forwards: more redirects than the
   * server allows in one run (one, when it does not say) is refused, nothing written.
   *
   * @throws Exception on failure
   */
  @Test
  public void testNoMoreRedirectsThanTheServerAllows() throws Exception {
    authorized.add("carol@stalwart.local");
    engine.writeForwarding(session, BOB, null);
    int writes = writeCount();
    assertEquals(ServerRuleUnsupportedException.TOO_MANY_REDIRECTS,
                 assertThrows(ServerRuleUnsupportedException.class,
                              () -> engine.saveRule(session, forwardRule("carol@stalwart.local"), null)).getMessage());
    assertEquals(writes, writeCount());
  }

  /**
   * A rule that forwards is written as {@code redirect :copy} to its authorized
   * destination, and read back among the rules that forward; an unauthorized one is
   * refused by the generator and nothing is written.
   *
   * @throws Exception on failure
   */
  @Test
  public void testARuleForwardsOnlyWhereAuthorized() throws Exception {
    engine.saveRule(session, forwardRule(BOB), null);
    String stored = server.getScripts().get(ExoSieveScript.SCRIPT_NAME);
    assertTrue(stored.contains("  redirect :copy \"" + BOB + "\";\r\n"), stored);
    ForwardingSetting read = engine.readForwarding(session);
    assertEquals(ForwardingState.NONE, read.state());
    assertEquals(List.of(new ForwardingSetting.RuleForward("Acme to Bob", BOB)), read.ruleForwards());
    int writes = writeCount();
    // The same rule, now forwarding elsewhere.
    ServerRule elsewhere = forwardRule("mallory@stalwart.local").withRef("1");
    assertThrows(ForwardingRefusedException.class, () -> engine.saveRule(session, elsewhere, null));
    assertEquals(writes, writeCount());
  }

  /**
   * The settings' read asks the server once for the reply and the forward together: one
   * authentication, one conversation.
   *
   * @throws Exception on failure
   */
  @Test
  public void testTheReplyAndTheForwardAreReadInOneConversation() throws Exception {
    engine.writeVacation(session, reply("Back soon"), 7, null);
    engine.writeForwarding(session, BOB, null);
    int before = server.getAuthentications();
    ServerAbsence read = engine.readAbsence(session, null, true);
    assertEquals(before + 1, server.getAuthentications());
    assertEquals(VacationState.OWN, read.vacation().state());
    assertTrue(read.forwarding().managedByExo());
    assertEquals(List.of(BOB), read.forwarding().destinations());
  }

  /**
   * Asserts the server received no command that writes.
   */
  private void assertNothingWritten() {
    for (String verb : WRITE_VERBS) {
      assertTrue(server.getCommands(verb).isEmpty(), verb + " was sent: " + server.getCommands());
    }
  }

  /**
   * How many commands that write the server received so far.
   *
   * @return the count
   */
  private int writeCount() {
    return WRITE_VERBS.stream().mapToInt(verb -> server.getCommands(verb).size()).sum();
  }

  /**
   * A reply switched on.
   *
   * @param text its text
   * @return the reply
   */
  private static VacationSetting reply(String text) {
    return new VacationSetting(true, null, null, null, "Out of office", text, 0, null);
  }

  /**
   * A rule that forwards Acme's mail.
   *
   * @param destination where
   * @return the rule
   */
  private static ServerRule forwardRule(String destination) {
    return new ServerRule(null,
                          "Acme to Bob",
                          true,
                          true,
                          List.of(new Condition("FROM", "MATCHES_DOMAIN", null, "acme.com")),
                          List.of(new Action(ServerRule.FORWARD, null, null, null, destination)),
                          false);
  }
}
