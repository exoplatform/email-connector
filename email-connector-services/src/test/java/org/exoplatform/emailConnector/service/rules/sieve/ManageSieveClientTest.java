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

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.mail.PasswordAuthentication;
import javax.net.ssl.SSLContext;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.Timeout.ThreadMode;

import org.exoplatform.emailConnector.service.rules.sieve.ManageSieveException.Kind;

/**
 * The ManageSieve client against a real socket: the TLS sequence, SASL PLAIN, every
 * command it sends, literal framing, and the limits that keep a hostile server from
 * hanging or flooding it.
 */
public class ManageSieveClientTest {

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
   * Stops the server.
   */
  @AfterEach
  public void stopServer() {
    server.close();
  }

  /**
   * The capabilities kept are those re-issued after TLS: before TLS the server offers
   * only OAUTHBEARER, after it PLAIN too, and authentication succeeds with PLAIN.
   *
   * @throws Exception on failure
   */
  @Test
  public void testUsesTheCapabilitiesReissuedAfterStarttls() throws Exception {
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
  public void testRefusesAServerWithoutStarttls() throws Exception {
    server.offerStarttls(false);
    ManageSieveException e = assertThrows(ManageSieveException.class, this::connect);
    assertEquals(Kind.TLS_REQUIRED, e.getKind());
    assertEquals(List.of(), server.getCommands());
  }

  /**
   * Bytes pushed in clear after the STARTTLS OK are refused rather than read as if they
   * came through the tunnel.
   *
   * @throws Exception on failure
   */
  @Test
  public void testRefusesDataInjectedAheadOfTheTlsHandshake() throws Exception {
    server.injectAfterStarttls();
    ManageSieveException e = assertThrows(ManageSieveException.class, this::connect);
    assertEquals(Kind.PROTOCOL, e.getKind());
  }

  /**
   * A certificate the client does not trust fails the connection.
   *
   * @throws Exception on failure
   */
  @Test
  public void testRefusesAnUntrustedCertificate() throws Exception {
    ManageSieveException e = assertThrows(ManageSieveException.class,
                                          () -> ManageSieveClient.connect("localhost",
                                                                          server.getPort(),
                                                                          SSLContext.getDefault().getSocketFactory(),
                                                                          5000,
                                                                          5000));
    assertEquals(Kind.UNAVAILABLE, e.getKind());
  }

  /**
   * A trusted certificate that names another host fails the connection: the client
   * checks the host name, not only the chain.
   *
   * @throws Exception on failure
   */
  @Test
  public void testRefusesACertificateForAnotherHost() throws Exception {
    try (FakeManageSieveServer otherHost = new FakeManageSieveServer(FakeManageSieveServer.OTHER_HOST_KEYSTORE)) {
      ManageSieveException e =
                             assertThrows(ManageSieveException.class,
                                          () -> ManageSieveClient.connect("localhost",
                                                                          otherHost.getPort(),
                                                                          FakeManageSieveServer.clientTlsFactory(FakeManageSieveServer.OTHER_HOST_KEYSTORE),
                                                                          5000,
                                                                          5000));
      assertEquals(Kind.UNAVAILABLE, e.getKind());
    }
  }

  /**
   * Without PLAIN after TLS the client refuses to authenticate and sends nothing.
   *
   * @throws Exception on failure
   */
  @Test
  public void testRefusesToAuthenticateWithoutPlainAfterTls() throws Exception {
    server.postTlsSasl("OAUTHBEARER");
    try (ManageSieveClient client = connect()) {
      ManageSieveException e = assertThrows(ManageSieveException.class,
                                            () -> client.authenticatePlain(new PasswordAuthentication(FakeManageSieveServer.LOGIN,
                                                                                                      FakeManageSieveServer.PASSWORD)));
      assertEquals(Kind.UNSUPPORTED_MECHANISM, e.getKind());
    }
    assertEquals(0, server.getAuthentications());
  }

  /**
   * A refused password is an AUTHENTICATION failure whose message does not carry it.
   *
   * @throws Exception on failure
   */
  @Test
  public void testRefusedCredentialsAreAnAuthenticationFailureWithoutThePassword() throws Exception {
    try (ManageSieveClient client = connect()) {
      ManageSieveException e = assertThrows(ManageSieveException.class,
                                            () -> client.authenticatePlain(new PasswordAuthentication(FakeManageSieveServer.LOGIN,
                                                                                                      "wrong-password")));
      assertEquals(Kind.AUTHENTICATION, e.getKind());
      assertFalse(e.getMessage().contains("wrong-password"));
    }
  }

  /**
   * No credentials at all: refused locally, nothing sent.
   *
   * @throws Exception on failure
   */
  @Test
  public void testNoCredentialsSendNothing() throws Exception {
    try (ManageSieveClient client = connect()) {
      assertEquals(Kind.NO_CREDENTIALS,
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
  public void testPutAndGetScriptRoundTripByteForByte() throws Exception {
    String text = "# exo-managed-v1: {\"v\":1}\r\nrequire [\"vacation\"];\r\n"
        + "vacation \"Absent — de retour le 15 \\\"octobre\\\" \\\\ ça va\";\r\n";
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
  public void testListActivateAndDeleteScripts() throws Exception {
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
  public void testNoAnswerCarriesTheResponseCode() throws Exception {
    try (ManageSieveClient client = server.authenticatedClient()) {
      ManageSieveException e = assertThrows(ManageSieveException.class, () -> client.getScript("missing"));
      assertEquals(Kind.REFUSED, e.getKind());
      assertEquals("NONEXISTENT", e.getResponseCode());
    }
  }

  /**
   * CHECKSCRIPT is sent when VERSION is advertised, and a compile error is a refusal.
   *
   * @throws Exception on failure
   */
  @Test
  public void testCheckScriptIsSentWhenAdvertisedAndItsRefusalPropagates() throws Exception {
    try (ManageSieveClient client = server.authenticatedClient()) {
      assertTrue(client.checkScript("keep;"));
      server.refuse("CHECKSCRIPT", "NO \"line 1: unknown command\"");
      ManageSieveException e = assertThrows(ManageSieveException.class, () -> client.checkScript("kep;"));
      assertEquals(Kind.REFUSED, e.getKind());
    }
    assertEquals(2, server.getCommands("CHECKSCRIPT").size());
  }

  /**
   * On a server without VERSION, CHECKSCRIPT is never sent.
   *
   * @throws Exception on failure
   */
  @Test
  public void testCheckScriptIsSkippedWhenNotAdvertised() throws Exception {
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
  public void testASilentServerTimesOut() throws Exception {
    server.silent();
    long start = System.nanoTime();
    ManageSieveException e = assertThrows(ManageSieveException.class,
                                          () -> ManageSieveClient.connect("localhost",
                                                                          server.getPort(),
                                                                          FakeManageSieveServer.clientTlsFactory(),
                                                                          2000,
                                                                          300));
    assertEquals(Kind.UNAVAILABLE, e.getKind());
    assertTrue((System.nanoTime() - start) / 1_000_000 < 5000);
  }

  /**
   * A literal announced larger than the cap is refused before anything is allocated.
   *
   * @throws Exception on failure
   */
  @Test
  public void testRefusesAnOversizedLiteral() throws Exception {
    server.rawGetScriptAnswer("{" + (ManageSieveClient.MAX_LITERAL_OCTETS + 1) + "}\r\n");
    try (ManageSieveClient client = server.authenticatedClient()) {
      ManageSieveException e = assertThrows(ManageSieveException.class, () -> client.getScript("exo-rules"));
      assertEquals(Kind.PROTOCOL, e.getKind());
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
  public void testATricklingServerHitsTheOperationDeadline() throws Exception {
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
    assertEquals(Kind.UNAVAILABLE, e.getKind());
    assertTrue((System.nanoTime() - start) / 1_000_000 < 4000);
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
  public void testResponseLimits() throws Exception {
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
        assertEquals(Kind.PROTOCOL, e.getKind(), answer.getValue());
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
  public void testAnEmptyScriptNameLists() throws Exception {
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
  public void testServerTextIsSanitisedInMessages() throws Exception {
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
  public void testLogout() throws Exception {
    ManageSieveClient client = server.authenticatedClient();
    client.logout();
    assertTrue(server.getCommands().contains("LOGOUT"));
  }

  /**
   * String arguments: quoted when short, a non-synchronizing literal when multi-line or
   * longer than 1024 octets, NUL refused.
   */
  @Test
  public void testStringEncoding() {
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
}
