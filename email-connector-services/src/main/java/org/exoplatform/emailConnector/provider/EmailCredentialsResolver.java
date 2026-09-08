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
package org.exoplatform.emailConnector.provider;

import javax.mail.Authenticator;

import org.springframework.stereotype.Component;

import org.exoplatform.services.connector.credentials.ConnectorCredentialsChannel;
import org.exoplatform.services.connector.credentials.ConnectorCredentialsContext;
import org.exoplatform.services.connector.credentials.ConnectorCredentialsException;
import org.exoplatform.services.connector.credentials.ConnectorCredentialsService;
import org.exoplatform.services.connector.credentials.HttpConnectorCredentials;
import org.exoplatform.services.connector.credentials.MailConnectorCredentials;

/**
 * The email connector's side of the shared credentials contract: the one place
 * that knows how this connector addresses a provider, so that the two questions
 * a caller ever asks — <i>which address would the material send as</i> and
 * <i>what authenticates the session</i> — are answered from a single context.
 * <p>
 * It exists because there are several callers on two channels: the outgoing path
 * builds an SMTP session per send, and the mailbox reads open IMAP stores.
 * Each building its own context is the divergence the contract was designed to
 * remove — a connector kind or a channel drifting in one of them fails as a
 * wrong provider resolved, not as a compile error.
 * <p>
 * The channel is a parameter here, unlike the CalDAV resolver where it is always
 * HTTP: IMAP and SMTP share this connector's stored credentials but are two
 * distinct conversations, and a provider may legitimately answer one and refuse
 * the other.
 */
@Component
public class EmailCredentialsResolver {

  /**
   * The kind this connector is known by platform-wide, which is how a provider
   * shared across connectors finds its email-specific adapter.
   */
  public static final String                CONNECTOR_KIND = "email";

  /** The platform-wide resolution service every question is asked through. */
  private final ConnectorCredentialsService connectorCredentialsService;

  public EmailCredentialsResolver(ConnectorCredentialsService resolutionService) {
    this.connectorCredentialsService = resolutionService;
  }

  /**
   * What authenticates a mail session, from the provider the connector preset is
   * configured with.
   * <p>
   * The cast holds because the resolution service refuses a provider that does
   * not declare the requested channel, and a mail-declaring provider answers
   * mail material. A failure there is a provider breaking its own contract,
   * which is why it is not caught into something friendlier.
   *
   * @param connectorId the connector preset the account is bound to
   * @param providerName provider the preset is configured with
   * @param username the eXo login the session is authenticated for
   * @param channel IMAP for a mailbox read, SMTP for a send
   * @return the authenticator to hand to {@code Session.getInstance}
   * @throws ConnectorCredentialsException when no provider of that name can
   *           produce material for this account
   */
  public Authenticator authenticator(Long connectorId,
                                     String providerName,
                                     String username,
                                     ConnectorCredentialsChannel channel) throws ConnectorCredentialsException {
    ConnectorCredentialsContext context = context(connectorId, providerName, username, channel);
    return ((MailConnectorCredentials) connectorCredentialsService.produce(context)).getAuthenticator();
  }

  /**
   * The {@code Authorization} header value a CardDAV conversation carries, from the
   * provider the connector preset is configured with.
   * <p>
   * The cast holds for the same reason the mail one does: the resolution service
   * refuses a provider that does not declare the requested channel, so an
   * HTTP-declaring provider answers HTTP material. A provider that supports this
   * connector's mail channels but not HTTP therefore fails here as a named
   * refusal rather than as a silently skipped contact sync.
   *
   * @param connectorId the connector preset the account is bound to
   * @param providerName provider the preset is configured with
   * @param username the eXo login the material is resolved for
   * @return the header value to send on every request of that conversation
   * @throws ConnectorCredentialsException when no provider of that name can
   *           produce HTTP material for this account
   */
  public String authorization(Long connectorId,
                              String providerName,
                              String username) throws ConnectorCredentialsException {
    ConnectorCredentialsContext context = context(connectorId, providerName, username, ConnectorCredentialsChannel.HTTP);
    return ((HttpConnectorCredentials) connectorCredentialsService.produce(context)).getAuthorizationHeaderValue();
  }

  /**
   * The account an address-book URL addresses, as the configured provider names it
   * — the user's own for Personal, someone else's for a provider that
   * authenticates as a technical account.
   * <p>
   * Asked on the HTTP channel, like {@link #authorization(Long, String, String)}:
   * it is the same conversation, and a provider that does not serve HTTP has no
   * address book to name an account in.
   * <p>
   * Answering null is a legitimate answer and not a failure — the caller decides
   * whether it can build its URL without one.
   *
   * @param connectorId the connector preset the account is bound to
   * @param providerName provider the preset is configured with
   * @param username the eXo login the target is resolved for
   * @return the account to place in the URL, or null when the provider names none
   * @throws ConnectorCredentialsException when no provider of that name can
   *           answer
   */
  public String targetAccount(Long connectorId,
                              String providerName,
                              String username) throws ConnectorCredentialsException {
    return connectorCredentialsService.resolveTargetIdentity(context(connectorId,
                                                                     providerName,
                                                                     username,
                                                                     ConnectorCredentialsChannel.HTTP));
  }

  /**
   * The address a message is sent as, as the configured provider names it — the
   * user's own for Personal, someone else's for a provider that authenticates as
   * a technical account.
   * <p>
   * Answering null is a legitimate answer and not a failure: a provider that has
   * no target to name leaves the caller with the address it already had.
   *
   * @param connectorId the connector preset the account is bound to
   * @param providerName provider the preset is configured with
   * @param username the eXo login the target is resolved for
   * @return the address to send as, or null when the provider names none
   * @throws ConnectorCredentialsException when no provider of that name can
   *           answer
   */
  public String senderAddress(Long connectorId,
                              String providerName,
                              String username) throws ConnectorCredentialsException {
    return connectorCredentialsService.resolveTargetIdentity(context(connectorId,
                                                                     providerName,
                                                                     username,
                                                                     ConnectorCredentialsChannel.SMTP));
  }

  /**
   * The context every question about this connector's credentials is asked in.
   *
   * @param connectorId the connector preset the account is bound to, 0 when it
   *          names none
   * @param providerName provider the preset is configured with
   * @param username the eXo login the material is resolved for
   * @param channel the conversation the material is for
   * @return the context to hand to the resolution service
   */
  private ConnectorCredentialsContext context(Long connectorId,
                                              String providerName,
                                              String username,
                                              ConnectorCredentialsChannel channel) {
    return new ConnectorCredentialsContext(connectorId == null ? 0L : connectorId,
                                           providerName,
                                           username,
                                           channel,
                                           CONNECTOR_KIND);
  }

}
