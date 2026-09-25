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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import javax.mail.Authenticator;
import javax.mail.PasswordAuthentication;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import org.exoplatform.emailConnector.exception.MailboxAclException;
import org.exoplatform.emailConnector.model.EmailConnector;
import org.exoplatform.emailConnector.provider.EmailCredentialsResolver;
import org.exoplatform.emailConnector.service.acl.MailboxAclSession;
import org.exoplatform.emailConnector.service.rules.sieve.ManageSieveException.Kind;
import org.exoplatform.services.connector.credentials.ConnectorCredentialsChannel;

/**
 * The connector opens an authenticated conversation with the IMAP channel's material
 * from the credentials contract, at the endpoint the administrator set, and follows the
 * contract's refusal rule.
 */
@ExtendWith(MockitoExtension.class)
public class ManageSieveConnectorTest {

  private static final long        CONNECTOR_ID = 77L;

  private static final String      PROVIDER     = "personal";

  private static final String      USERNAME     = "alice";

  @Mock
  private EmailCredentialsResolver resolver;

  private FakeManageSieveServer    server;

  private ManageSieveConnector     connector;

  private EmailConnector           preset;

  /**
   * Starts a server and points the preset's properties at it.
   *
   * @throws Exception when it cannot start
   */
  @BeforeEach
  public void setUp() throws Exception {
    server = new FakeManageSieveServer();
    System.setProperty(ManageSieveEndpoint.HOST_PROPERTY + "." + CONNECTOR_ID, "localhost");
    System.setProperty(ManageSieveEndpoint.PORT_PROPERTY + "." + CONNECTOR_ID, String.valueOf(server.getPort()));
    connector = new ManageSieveConnector(resolver);
    connector.configure(FakeManageSieveServer.clientTlsFactory(), 5000, 5000, 10000);
    preset = new EmailConnector();
    preset.setId(CONNECTOR_ID);
    preset.setImapUrl("imap.unreachable.invalid");
    preset.setAuthProviderName(PROVIDER);
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
   * The IMAP channel's login and password authenticate the conversation.
   *
   * @throws Exception on failure
   */
  @Test
  public void testAuthenticatesWithTheImapChannelMaterial() throws Exception {
    when(resolver.authenticator(CONNECTOR_ID, PROVIDER, USERNAME, ConnectorCredentialsChannel.IMAP))
                                                                                                    .thenReturn(authenticator(FakeManageSieveServer.PASSWORD));
    ManageSieveClient client = connector.open(preset, USERNAME);
    client.listScripts();
    client.logout();
    assertEquals(1, server.getAuthentications());
    verify(resolver, never()).invalidate(anyLong(), anyString(), anyString(), any());
  }

  /**
   * A refused typed password is reported once and never retried: a second refusal would
   * count against the account's lockout.
   *
   * @throws Exception on failure
   */
  @Test
  public void testARefusedTypedPasswordIsNotRetried() throws Exception {
    when(resolver.authenticator(CONNECTOR_ID, PROVIDER, USERNAME, ConnectorCredentialsChannel.IMAP))
                                                                                                    .thenReturn(authenticator("stale"));
    when(resolver.retriesAfterRefusal(PROVIDER)).thenReturn(false);
    ManageSieveException e = assertThrows(ManageSieveException.class, () -> connector.open(preset, USERNAME));
    assertEquals(Kind.AUTHENTICATION, e.getKind());
    assertEquals(1, server.getAuthentications());
    verify(resolver).invalidate(CONNECTOR_ID, PROVIDER, USERNAME, ConnectorCredentialsChannel.IMAP);
  }

  /**
   * A provider that produces its own material gets exactly one more attempt, with fresh
   * material.
   *
   * @throws Exception on failure
   */
  @Test
  public void testAProducedCredentialIsRetriedOnceOnFreshMaterial() throws Exception {
    when(resolver.authenticator(CONNECTOR_ID, PROVIDER, USERNAME, ConnectorCredentialsChannel.IMAP))
                                                                                                    .thenReturn(authenticator("stale-session"),
                                                                                                                authenticator(FakeManageSieveServer.PASSWORD));
    when(resolver.retriesAfterRefusal(PROVIDER)).thenReturn(true);
    ManageSieveClient client = connector.open(preset, USERNAME);
    client.logout();
    assertEquals(2, server.getAuthentications());
    verify(resolver, times(1)).invalidate(CONNECTOR_ID, PROVIDER, USERNAME, ConnectorCredentialsChannel.IMAP);
  }

  /**
   * Through the caller's session, the conversation authenticates with the material the
   * session resolves on the IMAP channel -- the same wiring the delegation service builds
   * -- and a produced credential the server refused is invalidated once and re-resolved
   * through the session.
   *
   * @throws Exception on failure
   */
  @Test
  public void testTheSessionPathUsesTheSessionMaterialAndTheRefusalRule() throws Exception {
    when(resolver.authenticator(CONNECTOR_ID, PROVIDER, USERNAME, ConnectorCredentialsChannel.IMAP))
                                                                                                    .thenReturn(authenticator("stale-session"),
                                                                                                                authenticator(FakeManageSieveServer.PASSWORD));
    when(resolver.retriesAfterRefusal(PROVIDER)).thenReturn(true);
    MailboxAclSession session = new MailboxAclSession(preset,
                                                      USERNAME,
                                                      FakeManageSieveServer.LOGIN,
                                                      null,
                                                      null,
                                                      () -> MailboxAclSession.passwordAuthentication(resolver.authenticator(CONNECTOR_ID,
                                                                                                                            PROVIDER,
                                                                                                                            USERNAME,
                                                                                                                            ConnectorCredentialsChannel.IMAP)));
    ManageSieveClient client = connector.open(session);
    client.logout();
    assertEquals(2, server.getAuthentications());
    verify(resolver, times(1)).invalidate(CONNECTOR_ID, PROVIDER, USERNAME, ConnectorCredentialsChannel.IMAP);
  }

  /**
   * A session without mail material opens nothing: its resolver's absence is reported,
   * never a connection with empty credentials.
   *
   * @throws Exception on failure
   */
  @Test
  public void testASessionWithoutMailMaterialOpensNothing() throws Exception {
    MailboxAclSession noResolver = new MailboxAclSession(preset, USERNAME, FakeManageSieveServer.LOGIN, null, null);
    assertThrows(MailboxAclException.class, () -> connector.open(noResolver));
    MailboxAclSession nothing = new MailboxAclSession(preset, USERNAME, FakeManageSieveServer.LOGIN, null, null, () -> null);
    assertEquals(Kind.NO_CREDENTIALS, assertThrows(ManageSieveException.class, () -> connector.open(nothing)).getKind());
    assertEquals(0, server.getAuthentications());
    verify(resolver, never()).invalidate(anyLong(), anyString(), anyString(), any());
  }

  /**
   * No material at all is its own failure: no connection is opened, and the provider is
   * not told that material was refused, since the server never saw any.
   *
   * @throws Exception on failure
   */
  @Test
  public void testNoMaterialOpensNoConnectionAndInvalidatesNothing() throws Exception {
    when(resolver.authenticator(CONNECTOR_ID, PROVIDER, USERNAME, ConnectorCredentialsChannel.IMAP)).thenReturn(null);
    ManageSieveException e = assertThrows(ManageSieveException.class, () -> connector.open(preset, USERNAME));
    assertEquals(Kind.NO_CREDENTIALS, e.getKind());
    assertEquals(List.of(), server.getCommands());
    verify(resolver, never()).invalidate(anyLong(), anyString(), anyString(), any());
  }

  /**
   * A server asking to try later is unavailable, not a refused credential: nothing is
   * invalidated and nothing retried.
   *
   * @throws Exception on failure
   */
  @Test
  public void testTryLaterIsNotARefusedCredential() throws Exception {
    when(resolver.authenticator(CONNECTOR_ID, PROVIDER, USERNAME, ConnectorCredentialsChannel.IMAP))
                                                                                                    .thenReturn(authenticator(FakeManageSieveServer.PASSWORD));
    server.refuse("AUTHENTICATE", "NO (TRYLATER) \"Backend busy\"");
    ManageSieveException e = assertThrows(ManageSieveException.class, () -> connector.open(preset, USERNAME));
    assertEquals(Kind.UNAVAILABLE, e.getKind());
    assertEquals("TRYLATER", e.getResponseCode());
    verify(resolver, never()).invalidate(anyLong(), anyString(), anyString(), any());
    assertEquals(1, server.getCommands("AUTHENTICATE").size());
  }

  /**
   * The endpoint: per-preset property, else global, else the IMAP host and 4190.
   */
  @Test
  public void testEndpointResolution() {
    assertEquals(new ManageSieveEndpoint("localhost", server.getPort()), ManageSieveEndpoint.forConnector(preset));
    EmailConnector other = new EmailConnector();
    other.setId(78L);
    other.setImapUrl("imap.example.org");
    assertEquals(new ManageSieveEndpoint("imap.example.org", 4190), ManageSieveEndpoint.forConnector(other));
    System.setProperty(ManageSieveEndpoint.HOST_PROPERTY, "sieve.example.org");
    System.setProperty(ManageSieveEndpoint.PORT_PROPERTY, "14190");
    try {
      assertEquals(new ManageSieveEndpoint("sieve.example.org", 14190), ManageSieveEndpoint.forConnector(other));
      System.setProperty(ManageSieveEndpoint.PORT_PROPERTY, "not-a-port");
      assertThrows(IllegalStateException.class, () -> ManageSieveEndpoint.forConnector(other));
    } finally {
      System.clearProperty(ManageSieveEndpoint.HOST_PROPERTY);
      System.clearProperty(ManageSieveEndpoint.PORT_PROPERTY);
    }
    other.setImapUrl(null);
    assertThrows(IllegalStateException.class, () -> ManageSieveEndpoint.forConnector(other));
  }

  /**
   * An authenticator answering a fixed login and password, as a provider's does.
   *
   * @param password the password
   * @return the authenticator
   */
  private static Authenticator authenticator(String password) {
    return new Authenticator() {
      /**
       * The fixed material.
       *
       * @return the login and password
       */
      @Override
      protected PasswordAuthentication getPasswordAuthentication() {
        return new PasswordAuthentication(FakeManageSieveServer.LOGIN, password);
      }
    };
  }
}
