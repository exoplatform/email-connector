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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Delayed;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import javax.mail.PasswordAuthentication;
import javax.net.ssl.SSLContext;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.Timeout.ThreadMode;

import org.exoplatform.emailConnector.constant.ManageSieveErrorKind;

/**
 * The ManageSieve client against a real socket: the TLS sequence, SASL PLAIN, every
 * command it sends, literal framing, and the limits that keep a hostile server from
 * hanging or flooding it.
 */
class ManageSieveClientTest {

  /** The read timeout of the tests that watch how long a close takes. */
  private static final int READ_TIMEOUT_MILLIS = 2000;

  /** What a close may take beyond the read timeout it follows, far under a second read timeout. */
  private static final int CLOSE_MARGIN_MILLIS = 1000;

  private FakeManageSieveServer server;

  /**
   * Starts a fresh server.
   *
   * @throws Exception when it cannot start
   */
  @BeforeEach
  void startServer() throws Exception {
    server = new FakeManageSieveServer();
  }

  /**
   * Stops the server.
   */
  @AfterEach
  void stopServer() {
    server.close();
  }

  /**
   * The capabilities kept are those re-issued after TLS: before TLS the server offers
   * only OAUTHBEARER, after it PLAIN too, and authentication succeeds with PLAIN.
   *
   * @throws Exception on failure
   */
  @Test
  void testUsesTheCapabilitiesReissuedAfterStarttls() throws Exception {
    try (ManageSieveClient client = connect()) {
      assertTrue(client.getCapabilities().supportsSasl("PLAIN"));
      assertFalse(client.getCapabilities().starttls());
      assertTrue(client.getCapabilities().hasExtension("VACATION"));
      assertTrue(client.getCapabilities().supportsCheckScript());
      client.authenticatePlain(new PasswordAuthentication(FakeManageSieveServer.LOGIN, FakeManageSieveServer.PASSWORD));
    }
    assertEquals(List.of("STARTTLS", "AUTHENTICATE PLAIN <redacted>"), server.getCommands());
  }

  /**
   * A server that does not offer STARTTLS is refused before anything is sent: no
   * credential ever travels in clear.
   *
   * @throws Exception on failure
   */
  @Test
  void testRefusesAServerWithoutStarttls() throws Exception {
    server.offerStarttls(false);
    ManageSieveException e = assertThrows(ManageSieveException.class, this::connect);
    assertEquals(ManageSieveErrorKind.TLS_REQUIRED, e.getKind());
    assertEquals(List.of(), server.getCommands());
  }

  /**
   * Bytes pushed in clear after the STARTTLS OK are refused rather than read as if they
   * came through the tunnel.
   *
   * @throws Exception on failure
   */
  @Test
  void testRefusesDataInjectedAheadOfTheTlsHandshake() throws Exception {
    server.injectAfterStarttls();
    ManageSieveException e = assertThrows(ManageSieveException.class, this::connect);
    assertEquals(ManageSieveErrorKind.PROTOCOL, e.getKind());
  }

  /**
   * A certificate the client does not trust fails the connection.
   *
   * @throws Exception on failure
   */
  @Test
  void testRefusesAnUntrustedCertificate() throws Exception {
    ManageSieveException e = assertThrows(ManageSieveException.class,
                                          () -> ManageSieveClient.connect("localhost",
                                                                          server.getPort(),
                                                                          SSLContext.getDefault().getSocketFactory(),
                                                                          5000,
                                                                          5000));
    assertEquals(ManageSieveErrorKind.UNAVAILABLE, e.getKind());
  }

  /**
   * A trusted certificate that names another host fails the connection: the client
   * checks the host name, not only the chain.
   *
   * @throws Exception on failure
   */
  @Test
  void testRefusesACertificateForAnotherHost() throws Exception {
    try (FakeManageSieveServer otherHost = new FakeManageSieveServer(FakeManageSieveServer.OTHER_HOST_KEYSTORE)) {
      ManageSieveException e =
                             assertThrows(ManageSieveException.class,
                                          () -> ManageSieveClient.connect("localhost",
                                                                          otherHost.getPort(),
                                                                          FakeManageSieveServer.clientTlsFactory(FakeManageSieveServer.OTHER_HOST_KEYSTORE),
                                                                          5000,
                                                                          5000));
      assertEquals(ManageSieveErrorKind.UNAVAILABLE, e.getKind());
    }
  }

  /**
   * Without PLAIN after TLS the client refuses to authenticate and sends nothing.
   *
   * @throws Exception on failure
   */
  @Test
  void testRefusesToAuthenticateWithoutPlainAfterTls() throws Exception {
    server.postTlsSasl("OAUTHBEARER");
    try (ManageSieveClient client = connect()) {
      ManageSieveException e = assertThrows(ManageSieveException.class,
                                            () -> client.authenticatePlain(new PasswordAuthentication(FakeManageSieveServer.LOGIN,
                                                                                                      FakeManageSieveServer.PASSWORD)));
      assertEquals(ManageSieveErrorKind.UNSUPPORTED_MECHANISM, e.getKind());
    }
    assertEquals(0, server.getAuthentications());
  }

  /**
   * A refused password is an AUTHENTICATION failure whose message does not carry it.
   *
   * @throws Exception on failure
   */
  @Test
  void testRefusedCredentialsAreAnAuthenticationFailureWithoutThePassword() throws Exception {
    try (ManageSieveClient client = connect()) {
      ManageSieveException e = assertThrows(ManageSieveException.class,
                                            () -> client.authenticatePlain(new PasswordAuthentication(FakeManageSieveServer.LOGIN,
                                                                                                      "wrong-password")));
      assertEquals(ManageSieveErrorKind.AUTHENTICATION, e.getKind());
      assertFalse(e.getMessage().contains("wrong-password"));
    }
  }

  /**
   * No credentials at all: refused locally, nothing sent.
   *
   * @throws Exception on failure
   */
  @Test
  void testNoCredentialsSendNothing() throws Exception {
    try (ManageSieveClient client = connect()) {
      assertEquals(ManageSieveErrorKind.NO_CREDENTIALS,
                   assertThrows(ManageSieveException.class, () -> client.authenticatePlain(null)).getKind());
    }
    assertEquals(0, server.getAuthentications());
  }

  /**
   * PUTSCRIPT then GETSCRIPT returns the text byte for byte — comments, CRLF, quotes,
   * backslashes and non-ASCII included — because literals count octets.
   *
   * @throws Exception on failure
   */
  @Test
  void testPutAndGetScriptRoundTripByteForByte() throws Exception {
    String text = """
        # exo-managed-v1: {"v":1}\r
        require ["vacation"];\r
        vacation "Absent — de retour le 15 \\"octobre\\" \\\\ ça va";\r
        """;
    try (ManageSieveClient client = server.authenticatedClient()) {
      client.putScript("exo-rules", text);
      assertEquals(text, client.getScript("exo-rules"));
    }
    assertEquals(text, server.getScripts().get("exo-rules"));
  }

  /**
   * LISTSCRIPTS reports every script and marks the active one; SETACTIVE and
   * DELETESCRIPT act on the name given, quotes in names escaped.
   *
   * @throws Exception on failure
   */
  @Test
  void testListActivateAndDeleteScripts() throws Exception {
    server.script("roundcube \"main\"", "keep;", true).script("old", "keep;", false);
    try (ManageSieveClient client = server.authenticatedClient()) {
      assertEquals(List.of(new SieveScriptInfo("roundcube \"main\"", true), new SieveScriptInfo("old", false)),
                   client.listScripts());
      client.setActive("old");
      client.deleteScript("roundcube \"main\"");
      assertEquals(List.of(new SieveScriptInfo("old", true)), client.listScripts());
    }
  }

  /**
   * A NO carries the server's response code, so a caller can tell a missing script from
   * a refusal.
   *
   * @throws Exception on failure
   */
  @Test
  void testNoAnswerCarriesTheResponseCode() throws Exception {
    try (ManageSieveClient client = server.authenticatedClient()) {
      ManageSieveException e = assertThrows(ManageSieveException.class, () -> client.getScript("missing"));
      assertEquals(ManageSieveErrorKind.REFUSED, e.getKind());
      assertEquals("NONEXISTENT", e.getResponseCode());
    }
  }

  /**
   * CHECKSCRIPT is sent when VERSION is advertised, and a compile error is a refusal.
   *
   * @throws Exception on failure
   */
  @Test
  void testCheckScriptIsSentWhenAdvertisedAndItsRefusalPropagates() throws Exception {
    try (ManageSieveClient client = server.authenticatedClient()) {
      assertTrue(client.checkScript("keep;"));
      server.refuse("CHECKSCRIPT", "NO \"line 1: unknown command\"");
      ManageSieveException e = assertThrows(ManageSieveException.class, () -> client.checkScript("kep;"));
      assertEquals(ManageSieveErrorKind.REFUSED, e.getKind());
    }
    assertEquals(2, server.getCommands("CHECKSCRIPT").size());
  }

  /**
   * On a server without VERSION, CHECKSCRIPT is never sent.
   *
   * @throws Exception on failure
   */
  @Test
  void testCheckScriptIsSkippedWhenNotAdvertised() throws Exception {
    server.advertiseVersion(false);
    try (ManageSieveClient client = server.authenticatedClient()) {
      assertFalse(client.checkScript("keep;"));
    }
    assertEquals(List.of(), server.getCommands("CHECKSCRIPT"));
  }

  /**
   * A server that never answers times out instead of hanging the caller.
   *
   * @throws Exception on failure
   */
  @Test
  void testASilentServerTimesOut() throws Exception {
    server.silent();
    long start = System.nanoTime();
    ManageSieveException e = assertThrows(ManageSieveException.class,
                                          () -> ManageSieveClient.connect("localhost",
                                                                          server.getPort(),
                                                                          FakeManageSieveServer.clientTlsFactory(),
                                                                          2000,
                                                                          300));
    assertEquals(ManageSieveErrorKind.UNAVAILABLE, e.getKind());
    assertTrue((System.nanoTime() - start) / 1_000_000 < 5000);
  }

  /**
   * A literal announced larger than the cap is refused before anything is allocated.
   *
   * @throws Exception on failure
   */
  @Test
  void testRefusesAnOversizedLiteral() throws Exception {
    server.rawGetScriptAnswer("{" + (ManageSieveClient.MAX_LITERAL_OCTETS + 1) + "}\r\n");
    try (ManageSieveClient client = server.authenticatedClient()) {
      ManageSieveException e = assertThrows(ManageSieveException.class, () -> client.getScript("exo-rules"));
      assertEquals(ManageSieveErrorKind.PROTOCOL, e.getKind());
    }
  }

  /**
   * A server that trickles an endless answer, each read within the read timeout, hits
   * the operation deadline instead of holding the caller's thread.
   *
   * @throws Exception on failure
   */
  @Test
  @Timeout(value = 10, threadMode = ThreadMode.SEPARATE_THREAD)
  void testATricklingServerHitsTheOperationDeadline() throws Exception {
    server.trickleGetScript(50);
    ManageSieveClient client = ManageSieveClient.connect("localhost",
                                                         server.getPort(),
                                                         FakeManageSieveServer.clientTlsFactory(),
                                                         5000,
                                                         1000,
                                                         1500);
    client.authenticatePlain(new PasswordAuthentication(FakeManageSieveServer.LOGIN, FakeManageSieveServer.PASSWORD));
    long start = System.nanoTime();
    ManageSieveException e = assertThrows(ManageSieveException.class, () -> client.getScript("exo-rules"));
    assertEquals(ManageSieveErrorKind.UNAVAILABLE, e.getKind());
    assertTrue((System.nanoTime() - start) / 1_000_000 < 4000);
  }

  /**
   * A server that trickles its TLS handshake, each read within the read timeout, hits the
   * operation deadline too: the JDK reads the handshake without the client's own read,
   * so only the watchdog that closes the socket at the deadline bounds it.
   *
   * @throws Exception on failure
   */
  @Test
  @Timeout(value = 10, threadMode = ThreadMode.SEPARATE_THREAD)
  void testATricklingHandshakeHitsTheOperationDeadline() throws Exception {
    server.trickleHandshake(200);
    long start = System.nanoTime();
    ManageSieveException e = assertThrows(ManageSieveException.class,
                                          () -> ManageSieveClient.connect("localhost",
                                                                          server.getPort(),
                                                                          FakeManageSieveServer.clientTlsFactory(),
                                                                          5000,
                                                                          1000,
                                                                          1500));
    assertEquals(ManageSieveErrorKind.UNAVAILABLE, e.getKind());
    assertTrue(e.getMessage().contains("timed out"), e.getMessage());
    assertTrue((System.nanoTime() - start) / 1_000_000 < 4000);
  }

  /**
   * A server that stops reading what the client writes holds a write blocked inside the
   * TLS layer, and the operation deadline still ends it: the watchdog closes the TCP
   * socket under that layer, where closing the TLS socket would wait for the blocked
   * write and hold the one watchdog thread every client shares. The script is larger
   * than what the loopback buffers take in, the server's receive buffer being cut to a
   * few kilobytes.
   *
   * @throws Exception on failure
   */
  @Test
  @Timeout(value = 10, threadMode = ThreadMode.SEPARATE_THREAD)
  void testAWriteTheServerStopsReadingHitsTheOperationDeadline() throws Exception {
    server.stopReadingAfterAuthentication();
    ManageSieveClient client = ManageSieveClient.connect("localhost",
                                                         server.getPort(),
                                                         FakeManageSieveServer.clientTlsFactory(),
                                                         5000,
                                                         1000,
                                                         1500);
    client.authenticatePlain(new PasswordAuthentication(FakeManageSieveServer.LOGIN, FakeManageSieveServer.PASSWORD));
    String script = "#".repeat(16 * 1024 * 1024);
    long start = System.nanoTime();
    ManageSieveException e = assertThrows(ManageSieveException.class, () -> client.putScript("exo-rules", script));
    assertEquals(ManageSieveErrorKind.UNAVAILABLE, e.getKind());
    assertTrue(e.getMessage().contains("timed out"), e.getMessage());
    assertTrue((System.nanoTime() - start) / 1_000_000 < 4000);
  }

  /**
   * Each operation's deadline falls within the conversation's. A server trickles an
   * answer, each read within the read timeout and the operation far from its own
   * {@value ManageSieveClient#DEFAULT_OPERATION_TIMEOUT_MILLIS} ms deadline: the operation
   * still fails as timed out once the conversation's time is up, and its watchdog is armed
   * for what was left of the conversation. The driven watchdog never fires, so the
   * client's own read deadline is what ends the operation.
   *
   * @throws Exception on failure
   */
  @Test
  @Timeout(value = 10, threadMode = ThreadMode.SEPARATE_THREAD)
  void testTheConversationDeadlineCapsEachOperation() throws Exception {
    DrivenWatchdog watchdog = new DrivenWatchdog();
    try (ManageSieveClient client = connect(watchdog)) {
      client.authenticatePlain(new PasswordAuthentication(FakeManageSieveServer.LOGIN, FakeManageSieveServer.PASSWORD));
      server.trickleGetScript(50);
      client.endConversationWithin(1500);
      long start = System.nanoTime();
      ManageSieveException e = assertThrows(ManageSieveException.class, () -> client.getScript("exo-rules"));
      assertEquals(ManageSieveErrorKind.UNAVAILABLE, e.getKind());
      assertTrue(e.getMessage().contains("timed out"), e.getMessage());
      assertTrue((System.nanoTime() - start) / 1_000_000 < 4000);
      assertEquals(3, watchdog.delaysMillis.size());
      assertTrue(watchdog.delaysMillis.get(2) <= 1500, watchdog.delaysMillis.toString());
    }
  }

  /**
   * No operation starts once the conversation's deadline has passed: the command fails as
   * timed out before anything is sent, and no watchdog is armed for it.
   *
   * @throws Exception on failure
   */
  @Test
  void testNoOperationStartsAfterTheConversationDeadline() throws Exception {
    DrivenWatchdog watchdog = new DrivenWatchdog();
    try (ManageSieveClient client = connect(watchdog)) {
      client.authenticatePlain(new PasswordAuthentication(FakeManageSieveServer.LOGIN, FakeManageSieveServer.PASSWORD));
      client.endConversationWithin(0);
      ManageSieveException e = assertThrows(ManageSieveException.class, client::listScripts);
      assertEquals(ManageSieveErrorKind.UNAVAILABLE, e.getKind());
      assertTrue(e.getMessage().contains("timed out"), e.getMessage());
      assertEquals(2, watchdog.tasks.size());
      assertEquals(List.of("STARTTLS", "AUTHENTICATE PLAIN <redacted>"), server.getCommands());
    }
  }

  /**
   * {@code LOGOUT} is an operation like any command: once the conversation's deadline has
   * passed it is not sent, and the connection is closed all the same, so a command run
   * after it fails even with time to spare.
   *
   * @throws Exception on failure
   */
  @Test
  void testALogoutAfterTheConversationDeadlineSendsNothing() throws Exception {
    try (ManageSieveClient client = connect(new DrivenWatchdog())) {
      client.authenticatePlain(new PasswordAuthentication(FakeManageSieveServer.LOGIN, FakeManageSieveServer.PASSWORD));
      client.endConversationWithin(0);
      client.logout();
      assertEquals(List.of("STARTTLS", "AUTHENTICATE PLAIN <redacted>"), server.getCommands());
      client.endConversationWithin(ManageSieveClient.DEFAULT_CONVERSATION_TIMEOUT_MILLIS);
      ManageSieveException e = assertThrows(ManageSieveException.class, client::listScripts);
      assertEquals(ManageSieveErrorKind.UNAVAILABLE, e.getKind());
    }
  }

  /**
   * A {@code LOGOUT} the server never answers ends by the conversation's deadline: the
   * watchdog armed for it closes the socket, where the read would otherwise wait for the
   * whole read timeout.
   *
   * @throws Exception on failure
   */
  @Test
  @Timeout(value = 20, threadMode = ThreadMode.SEPARATE_THREAD)
  void testALogoutTheServerNeverAnswersEndsByTheConversationDeadline() throws Exception {
    server.stopReadingAfterAuthentication();
    try (ManageSieveClient client = connect()) {
      client.authenticatePlain(new PasswordAuthentication(FakeManageSieveServer.LOGIN, FakeManageSieveServer.PASSWORD));
      client.endConversationWithin(1000);
      long start = System.nanoTime();
      client.logout();
      long elapsedMillis = (System.nanoTime() - start) / 1_000_000;
      assertTrue(elapsedMillis < 3000, elapsedMillis + " ms");
    }
  }

  /**
   * A command whose read times out leaves the JDK's TLS socket open, and closing that
   * socket would wait up to one more read timeout for the server's {@code close_notify}
   * ({@code TLSv1.3}): the failed command, and the {@code close} after it, end within the
   * one read timeout, because the TCP socket under the TLS layer is the one closed. The
   * server stops reading after the login, so it never answers.
   *
   * @throws Exception on failure
   */
  @Test
  @Timeout(value = 20, threadMode = ThreadMode.SEPARATE_THREAD)
  void testACommandThatTimedOutClosesWithoutWaitingForTheServer() throws Exception {
    server.stopReadingAfterAuthentication();
    ManageSieveClient client = ManageSieveClient.connect("localhost",
                                                         server.getPort(),
                                                         FakeManageSieveServer.clientTlsFactory(),
                                                         5000,
                                                         READ_TIMEOUT_MILLIS);
    client.authenticatePlain(new PasswordAuthentication(FakeManageSieveServer.LOGIN, FakeManageSieveServer.PASSWORD));
    long start = System.nanoTime();
    ManageSieveException e = assertThrows(ManageSieveException.class, client::listScripts);
    client.close();
    long elapsedMillis = (System.nanoTime() - start) / 1_000_000;
    assertEquals(ManageSieveErrorKind.UNAVAILABLE, e.getKind());
    assertTrue(e.getMessage().contains("timed out"), e.getMessage());
    assertTrue(elapsedMillis < READ_TIMEOUT_MILLIS + CLOSE_MARGIN_MILLIS, elapsedMillis + " ms");
  }

  /**
   * A {@code LOGOUT} past the conversation's deadline sends nothing and closes the
   * connection through its TCP socket: the TLS socket would wait up to one read timeout for
   * the {@code close_notify} of a server that stopped reading, after the deadline.
   *
   * @throws Exception on failure
   */
  @Test
  @Timeout(value = 20, threadMode = ThreadMode.SEPARATE_THREAD)
  void testALogoutPastTheConversationDeadlineClosesWithoutWaitingForTheServer() throws Exception {
    server.stopReadingAfterAuthentication();
    ManageSieveClient client = ManageSieveClient.connect("localhost",
                                                         server.getPort(),
                                                         FakeManageSieveServer.clientTlsFactory(),
                                                         5000,
                                                         READ_TIMEOUT_MILLIS);
    client.authenticatePlain(new PasswordAuthentication(FakeManageSieveServer.LOGIN, FakeManageSieveServer.PASSWORD));
    client.endConversationWithin(0);
    long start = System.nanoTime();
    client.logout();
    long elapsedMillis = (System.nanoTime() - start) / 1_000_000;
    assertEquals(List.of("STARTTLS", "AUTHENTICATE PLAIN <redacted>"), server.getCommands());
    assertTrue(elapsedMillis < CLOSE_MARGIN_MILLIS, elapsedMillis + " ms");
  }

  /**
   * Each operation disarms its watchdog as it ends, so the watchdog's queue holds only
   * the operations in flight, however long the conversation.
   *
   * @throws Exception on failure
   */
  @Test
  void testAnOperationDisarmsItsWatchdogAsItEnds() throws Exception {
    DrivenWatchdog watchdog = new DrivenWatchdog();
    try (ManageSieveClient client = connect(watchdog)) {
      client.authenticatePlain(new PasswordAuthentication(FakeManageSieveServer.LOGIN, FakeManageSieveServer.PASSWORD));
      client.listScripts();
      assertEquals(3, watchdog.tasks.size());
      assertTrue(watchdog.tasks.stream().allMatch(ScheduledFuture::isCancelled));
    }
  }

  /**
   * A watchdog that fires once its operation has ended — one already running when the
   * operation disarmed it — closes nothing: the next command runs on the same
   * connection.
   *
   * @throws Exception on failure
   */
  @Test
  void testAWatchdogFiringAfterItsOperationEndedClosesNothing() throws Exception {
    DrivenWatchdog watchdog = new DrivenWatchdog();
    try (ManageSieveClient client = connect(watchdog)) {
      client.authenticatePlain(new PasswordAuthentication(FakeManageSieveServer.LOGIN, FakeManageSieveServer.PASSWORD));
      client.listScripts();
      watchdog.fireAll();
      assertEquals(List.of(), client.listScripts());
    }
  }

  /**
   * A watchdog that fires late, once the next operation is in flight, closes nothing: a
   * watchdog closes the socket only for the operation it was armed for, so the next
   * command runs to its answer. Each operation's watchdog fires as the next one is armed,
   * the next operation's number already current.
   *
   * @throws Exception on failure
   */
  @Test
  void testAWatchdogFiringUnderTheNextOperationClosesNothing() throws Exception {
    DrivenWatchdog watchdog = new DrivenWatchdog();
    try (ManageSieveClient client = connect(watchdog)) {
      watchdog.firePreviousAsNextIsArmed = true;
      client.authenticatePlain(new PasswordAuthentication(FakeManageSieveServer.LOGIN, FakeManageSieveServer.PASSWORD));
      assertEquals(List.of(), client.listScripts());
      assertEquals(2, watchdog.fired);
      assertEquals(List.of("STARTTLS", "AUTHENTICATE PLAIN <redacted>", "LISTSCRIPTS"), server.getCommands());
    }
  }

  /**
   * A watchdog that fires just as its operation ends, the answer already read, fails
   * that operation as timed out rather than letting it succeed on a connection the next
   * command would find closed.
   *
   * @throws Exception on failure
   */
  @Test
  void testAWatchdogFiringAsItsOperationEndsFailsIt() throws Exception {
    DrivenWatchdog watchdog = new DrivenWatchdog();
    try (ManageSieveClient client = connect(watchdog)) {
      client.authenticatePlain(new PasswordAuthentication(FakeManageSieveServer.LOGIN, FakeManageSieveServer.PASSWORD));
      watchdog.fireAsOperationsEnd = true;
      ManageSieveException e = assertThrows(ManageSieveException.class, client::listScripts);
      assertEquals(ManageSieveErrorKind.UNAVAILABLE, e.getKind());
      assertTrue(e.getMessage().contains("timed out"), e.getMessage());
      assertEquals(List.of("STARTTLS", "AUTHENTICATE PLAIN <redacted>", "LISTSCRIPTS"), server.getCommands());
    }
  }

  /**
   * The server's text reaches a message, and its response code an exception, without a
   * control, line separator, paragraph separator or format character: NEL (U+0085), a
   * line separator (U+2028) and a right-to-left override (U+202E) all become spaces.
   *
   * @throws Exception on failure
   */
  @Test
  void testServerTextIsMadePrintable() throws Exception {
    assertEquals("a b c d", ManageSieveClient.printable("a\u0085b\u2028c\u202Ed"));
    server.refuse("GETSCRIPT", "NO (TRY\u0085LATER) \"x\u2028y\u202Ez\"");
    try (ManageSieveClient client = server.authenticatedClient()) {
      ManageSieveException e = assertThrows(ManageSieveException.class, () -> client.getScript("exo-rules"));
      assertFalse(e.getResponseCode().matches("(?s).*[\\p{Cc}\\p{Zl}\\p{Zp}\\p{Cf}].*"), e.getResponseCode());
      assertTrue(e.getMessage().endsWith(": x y z"), e.getMessage());
    }
  }

  /**
   * Each size limit of a response is enforced by its own guard, told apart by its
   * message: whole-response octets (counted on every octet, and on a literal before it
   * is allocated), lines, tokens per line, atom length, quoted-string length, and a
   * bare CR.
   *
   * @throws Exception on failure
   */
  @Test
  void testResponseLimits() throws Exception {
    String literal = "{900000}\r\n" + "x".repeat(900000) + "\r\n";
    String quoted = "\"" + "x".repeat(4000) + "\"\r\n";
    Map<String, String> answers = new LinkedHashMap<>();
    answers.put(quoted.repeat(600) + "OK\r\n", "response larger than");
    answers.put(literal + literal + "{900000}\r\n" + "x".repeat(900000), "response larger than");
    answers.put("\"a\"\r\n".repeat(ManageSieveClient.MAX_RESPONSE_LINES + 1) + "OK\r\n", "response is too long");
    answers.put("a ".repeat(ManageSieveClient.MAX_TOKENS_PER_LINE + 1) + "\r\nOK\r\n", "response line is too long");
    answers.put("x".repeat(ManageSieveClient.MAX_ATOM_OCTETS + 1) + "\r\nOK\r\n", "atom too long");
    answers.put("\"" + "x".repeat(ManageSieveClient.MAX_SERVER_QUOTED_OCTETS + 1) + "\"\r\nOK\r\n", "quoted string too long");
    answers.put("OK\rX\r\n", "Bare CR");
    for (Map.Entry<String, String> answer : answers.entrySet()) {
      server.rawGetScriptAnswer(answer.getKey());
      ManageSieveClient client = ManageSieveClient.connect("localhost",
                                                           server.getPort(),
                                                           FakeManageSieveServer.clientTlsFactory(),
                                                           5000,
                                                           1000);
      try {
        client.authenticatePlain(new PasswordAuthentication(FakeManageSieveServer.LOGIN, FakeManageSieveServer.PASSWORD));
        ManageSieveException e = assertThrows(ManageSieveException.class, () -> client.getScript("exo-rules"));
        assertEquals(ManageSieveErrorKind.PROTOCOL, e.getKind(), answer.getValue());
        assertTrue(e.getMessage().contains(answer.getValue()), e.getMessage());
      } finally {
        client.close();
      }
    }
  }

  /**
   * A script with an empty name — what Stalwart's JMAP out-of-office creates — lists
   * without breaking the client.
   *
   * @throws Exception on failure
   */
  @Test
  void testAnEmptyScriptNameLists() throws Exception {
    server.script("", "vacation \"Away\";", true).script("exo-rules", "keep;", false);
    try (ManageSieveClient client = server.authenticatedClient()) {
      assertEquals(List.of(new SieveScriptInfo("", true), new SieveScriptInfo("exo-rules", false)), client.listScripts());
    }
  }

  /**
   * The server's text reaches an exception message on one line and truncated.
   *
   * @throws Exception on failure
   */
  @Test
  void testServerTextIsSanitisedInMessages() throws Exception {
    String text = "line one\r\nforged: log line " + "y".repeat(500);
    server.refuse("DELETESCRIPT", "NO {" + text.length() + "}\r\n" + text);
    try (ManageSieveClient client = server.authenticatedClient()) {
      ManageSieveException e = assertThrows(ManageSieveException.class, () -> client.deleteScript("x"));
      assertFalse(e.getMessage().contains("\n") || e.getMessage().contains("\r"));
      assertTrue(e.getMessage().length() < 300);
      assertTrue(e.getMessage().contains("line one  forged"));
    }
  }

  /**
   * LOGOUT is sent and the connection closed.
   *
   * @throws Exception on failure
   */
  @Test
  void testLogout() throws Exception {
    ManageSieveClient client = server.authenticatedClient();
    client.logout();
    assertTrue(server.getCommands().contains("LOGOUT"));
  }

  /**
   * String arguments: quoted when short, a non-synchronizing literal when multi-line or
   * longer than 1024 octets, NUL refused.
   */
  @Test
  void testStringEncoding() {
    assertEquals("\"a\\\"b\\\\c\"", ManageSieveClient.string("a\"b\\c"));
    assertEquals("{3+}\r\na\nb", ManageSieveClient.string("a\nb"));
    assertTrue(ManageSieveClient.string("x".repeat(1025)).startsWith("{1025+}\r\n"));
    assertEquals("{2+}\r\né", ManageSieveClient.literal("é"));
    assertThrows(IllegalArgumentException.class, () -> ManageSieveClient.string("a\0b"));
  }

  /**
   * Connects to the fake server over trusted TLS, not authenticated.
   *
   * @return the client
   * @throws Exception on failure
   */
  private ManageSieveClient connect() throws Exception {
    return ManageSieveClient.connect("localhost", server.getPort(), FakeManageSieveServer.clientTlsFactory(), 5000, 5000);
  }

  /**
   * Connects to the fake server over trusted TLS, each operation's watchdog run by the
   * test.
   *
   * @param watchdog the watchdog the test drives
   * @return the client, not authenticated
   * @throws Exception on failure
   */
  private ManageSieveClient connect(DrivenWatchdog watchdog) throws Exception {
    return ManageSieveClient.connect("localhost",
                                     server.getPort(),
                                     FakeManageSieveServer.clientTlsFactory(),
                                     5000,
                                     5000,
                                     ManageSieveClient.DEFAULT_OPERATION_TIMEOUT_MILLIS,
                                     watchdog);
  }

  /**
   * A watchdog the test drives: it keeps each task it is given, and the delay it was armed
   * with, instead of running it after that delay, and runs the tasks when the test fires them, each one as its
   * operation disarms it while {@link #fireAsOperationsEnd} is set, or each one as the
   * next is armed while {@link #firePreviousAsNextIsArmed} is set.
   */
  private static final class DrivenWatchdog extends ScheduledThreadPoolExecutor {

    /** Every task scheduled, in order. */
    private final List<DrivenTask> tasks = new ArrayList<>();

    /** The delay each task was armed with, in milliseconds, in order. */
    private final List<Long>       delaysMillis = new ArrayList<>();

    /** Whether a task runs as its operation disarms it, the watchdog firing at that instant. */
    private boolean                fireAsOperationsEnd;

    /** Whether arming a task first runs the one armed before it, a watchdog firing late. */
    private boolean                firePreviousAsNextIsArmed;

    /** How many tasks ran as the next one was armed. */
    private int                    fired;

    /**
     * A watchdog with no task.
     */
    private DrivenWatchdog() {
      super(1);
    }

    @Override
    public ScheduledFuture<?> schedule(Runnable command, long delay, TimeUnit unit) {
      if (firePreviousAsNextIsArmed && !tasks.isEmpty()) {
        tasks.get(tasks.size() - 1).command.run();
        fired++;
      }
      DrivenTask task = new DrivenTask(command);
      tasks.add(task);
      delaysMillis.add(unit.toMillis(delay));
      return task;
    }

    /**
     * Runs every task scheduled so far, cancelled or not, as a watchdog that was already
     * running when its operation ended.
     */
    private void fireAll() {
      tasks.forEach(task -> task.command.run());
    }

    /**
     * One kept task.
     */
    private final class DrivenTask implements ScheduledFuture<Object> {

      /** What the watchdog runs. */
      private final Runnable command;

      /** Whether the operation disarmed it. */
      private boolean        cancelled;

      /**
       * A kept task.
       *
       * @param command what the watchdog runs
       */
      private DrivenTask(Runnable command) {
        this.command = command;
      }

      @Override
      public boolean cancel(boolean mayInterruptIfRunning) {
        if (fireAsOperationsEnd) {
          command.run();
        }
        cancelled = true;
        return true;
      }

      @Override
      public boolean isCancelled() {
        return cancelled;
      }

      @Override
      public boolean isDone() {
        return isCancelled();
      }

      @Override
      public Object get() {
        return null;
      }

      @Override
      public Object get(long timeout, TimeUnit unit) {
        return null;
      }

      @Override
      public long getDelay(TimeUnit unit) {
        return 0;
      }

      @Override
      public int compareTo(Delayed other) {
        return Long.compare(getDelay(TimeUnit.NANOSECONDS), other.getDelay(TimeUnit.NANOSECONDS));
      }
    }
  }
}
