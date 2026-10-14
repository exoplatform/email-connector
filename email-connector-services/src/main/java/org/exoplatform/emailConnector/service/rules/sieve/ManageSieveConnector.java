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

import java.security.NoSuchAlgorithmException;
import java.util.Properties;

import javax.mail.Authenticator;
import javax.mail.PasswordAuthentication;
import javax.mail.Session;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import org.exoplatform.emailConnector.model.EmailConnector;
import org.exoplatform.emailConnector.provider.EmailCredentialsResolver;
import org.exoplatform.emailConnector.service.rules.sieve.ManageSieveException.Kind;
import org.exoplatform.services.connector.credentials.ConnectorCredentialsChannel;
import org.exoplatform.services.connector.credentials.ConnectorCredentialsException;

/**
 * Opens an authenticated ManageSieve conversation for a user on their connector preset.
 * <p>
 * The credentials come from the connector credentials contract, on the <b>IMAP</b>
 * channel — {@link EmailCredentialsResolver#authenticator} — and never from the stored
 * setting: ManageSieve authenticates against the directory IMAP does, so the IMAP
 * material is the right material, and a provider that presents a session id as the
 * password (the BlueMind technical-account provider) keeps working unchanged. There is
 * deliberately no {@code SIEVE} channel. The {@code Authenticator} the contract answers
 * is asked for its {@link PasswordAuthentication} through
 * {@link Session#requestPasswordAuthentication}, the public door JavaMail itself uses.
 * <p>
 * A refused credential follows the contract's rule: the provider is told once, and
 * only a provider that produces its material itself gets one more attempt — a typed
 * password would be refused again and count against the account's lockout.
 */
@Component
public class ManageSieveConnector {

  /** Resolves the credentials of every mail conversation of this add-on. */
  private final EmailCredentialsResolver emailCredentialsResolver;

  /** The TLS layer; the JVM's default trust store unless a test replaces it. */
  private SSLSocketFactory               tlsSocketFactory;

  /** The TCP connect timeout. */
  private int                            connectTimeoutMillis = ManageSieveClient.DEFAULT_CONNECT_TIMEOUT_MILLIS;

  /** The timeout of every read. */
  private int                            readTimeoutMillis    = ManageSieveClient.DEFAULT_READ_TIMEOUT_MILLIS;

  /** The time budget of every operation. */
  private int                            operationTimeoutMillis = ManageSieveClient.DEFAULT_OPERATION_TIMEOUT_MILLIS;

  /**
   * The connector, with the platform's credentials resolver.
   *
   * @param emailCredentialsResolver the resolver
   */
  @Autowired
  public ManageSieveConnector(EmailCredentialsResolver emailCredentialsResolver) {
    this.emailCredentialsResolver = emailCredentialsResolver;
  }

  /**
   * Replaces the TLS layer and the timeouts; tests point it at an in-JVM server with its
   * own certificate.
   *
   * @param factory the TLS socket factory
   * @param connectTimeout the connect timeout, in milliseconds
   * @param readTimeout the read timeout, in milliseconds
   * @param operationTimeout the budget of one operation, in milliseconds
   */
  void configure(SSLSocketFactory factory, int connectTimeout, int readTimeout, int operationTimeout) {
    this.tlsSocketFactory = factory;
    this.connectTimeoutMillis = connectTimeout;
    this.readTimeoutMillis = readTimeout;
    this.operationTimeoutMillis = operationTimeout;
  }

  /**
   * Opens a conversation as {@code username} on the ManageSieve server of their preset,
   * over TLS, authenticated. The caller closes it, ideally with
   * {@link ManageSieveClient#logout()}.
   *
   * @param connector the user's connector preset
   * @param username the eXo user the conversation acts as, resolved by the caller from
   *          the session and never from a request parameter
   * @return the authenticated client
   * @throws ManageSieveException when the server is unreachable, refuses TLS, or refuses
   *           the credentials
   * @throws ConnectorCredentialsException when the preset's provider cannot produce
   *           credentials for this user
   */
  public ManageSieveClient open(EmailConnector connector,
                                String username) throws ManageSieveException, ConnectorCredentialsException {
    ManageSieveEndpoint endpoint = ManageSieveEndpoint.forConnector(connector);
    try {
      return authenticated(endpoint, connector, username);
    } catch (ManageSieveException e) {
      if (e.getKind() != Kind.AUTHENTICATION) {
        throw e;
      }
      emailCredentialsResolver.invalidate(connector.getId(),
                                          connector.getAuthProviderName(),
                                          username,
                                          ConnectorCredentialsChannel.IMAP);
      if (!emailCredentialsResolver.retriesAfterRefusal(connector.getAuthProviderName())) {
        throw e;
      }
      return authenticated(endpoint, connector, username);
    }
  }

  /**
   * One attempt: resolve fresh material, then connect and authenticate — no connection is
   * opened when there is nothing to authenticate with.
   *
   * @param endpoint where the server listens
   * @param connector the preset
   * @param username the eXo user
   * @return the authenticated client
   * @throws ManageSieveException when any step fails
   * @throws ConnectorCredentialsException when no material can be produced
   */
  private ManageSieveClient authenticated(ManageSieveEndpoint endpoint,
                                          EmailConnector connector,
                                          String username) throws ManageSieveException, ConnectorCredentialsException {
    PasswordAuthentication credentials = credentials(connector, username, endpoint);
    ManageSieveClient client = ManageSieveClient.connect(endpoint.host(),
                                                         endpoint.port(),
                                                         tlsSocketFactory(),
                                                         connectTimeoutMillis,
                                                         readTimeoutMillis,
                                                         operationTimeoutMillis);
    try {
      client.authenticatePlain(credentials);
      return client;
    } catch (ManageSieveException | RuntimeException e) {
      client.close();
      throw e;
    }
  }

  /**
   * The IMAP channel's login and password for this user, from the credentials contract.
   *
   * @param connector the preset
   * @param username the eXo user
   * @param endpoint the server the material is for, passed to the authenticator
   * @return the login and password
   * @throws ConnectorCredentialsException when the provider cannot produce material
   * @throws ManageSieveException {@link Kind#NO_CREDENTIALS} when the material is empty
   */
  private PasswordAuthentication credentials(EmailConnector connector,
                                             String username,
                                             ManageSieveEndpoint endpoint) throws ConnectorCredentialsException,
                                                                           ManageSieveException {
    Authenticator authenticator = emailCredentialsResolver.authenticator(connector.getId(),
                                                                         connector.getAuthProviderName(),
                                                                         username,
                                                                         ConnectorCredentialsChannel.IMAP);
    PasswordAuthentication credentials = authenticator == null ? null
                                                               : Session.getInstance(new Properties(), authenticator)
                                                                        .requestPasswordAuthentication(null,
                                                                                                       endpoint.port(),
                                                                                                       "sieve",
                                                                                                       null,
                                                                                                       null);
    if (credentials == null || credentials.getUserName() == null || credentials.getPassword() == null) {
      throw new ManageSieveException(Kind.NO_CREDENTIALS, "The credentials provider produced no login and password");
    }
    return credentials;
  }

  /**
   * The TLS layer: the configured one, else the JVM default.
   *
   * @return the factory
   * @throws ManageSieveException when the JVM has no default TLS context
   */
  private SSLSocketFactory tlsSocketFactory() throws ManageSieveException {
    if (tlsSocketFactory != null) {
      return tlsSocketFactory;
    }
    try {
      return SSLContext.getDefault().getSocketFactory();
    } catch (NoSuchAlgorithmException e) {
      throw new ManageSieveException(Kind.UNAVAILABLE, "No default TLS context", e);
    }
  }
}
