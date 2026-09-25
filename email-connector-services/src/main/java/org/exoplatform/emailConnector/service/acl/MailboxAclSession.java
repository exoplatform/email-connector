/**
 * Copyright (C) 2026 eXo Platform SAS
 *
 *  This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <gnu.org/licenses>.
 */
package org.exoplatform.emailConnector.service.acl;

import java.util.Properties;

import javax.mail.Authenticator;
import javax.mail.MessagingException;
import javax.mail.PasswordAuthentication;
import javax.mail.Session;
import javax.mail.Store;

import org.exoplatform.emailConnector.exception.MailboxAclException;
import org.exoplatform.emailConnector.model.EmailConnector;
import org.exoplatform.services.connector.credentials.ConnectorCredentialsException;
import org.exoplatform.services.log.ExoLogger;
import org.exoplatform.services.log.Log;

/**
 * The caller's own standing on their mail server, handed to a {@link MailboxAclEngine}
 * in place of a bare IMAP {@link Store}: the connector preset, the eXo user acting,
 * the identifier of <b>their own</b> mailbox, and lazy access to the two transports an
 * engine may need to act as that user -- an IMAP store, or the HTTP material of the
 * platform's credentials contract for a vendor REST API. The engine owns its
 * transport: it opens what it needs, when it needs it, and nothing is opened for an
 * engine that never asks (a REST engine costs no IMAP connection; a no-op costs
 * nothing at all).
 * The server-rule engines (automatic reply, server rules) take the same session: their
 * protocols authenticate with the IMAP channel's login and password, which
 * {@link #mailCredentials()} resolves the same way, for the same caller.
 * <p>
 * <b>The security invariant this type carries, and must keep carrying.</b> A session
 * acts as <b>the caller, and nobody else</b>. It is built by {@code EmailDelegationService}
 * from the caller's own connected setting, and every credential it can resolve is
 * resolved through the same platform contract the sync and the sends use --
 * {@code UserEmailSettingService.connect} for the store, the HTTP channel of the
 * credentials resolver for a REST call -- for the username it was built with. It has
 * no setter, no way to name another user, and no field for an administrator or
 * {@code su} key: a vendor engine that needed one to act would be the unattended
 * identity switch this design refuses (delegation plan, sections 4.5 and 8 -- the
 * deployment-wide key alternative is rejected there on blast radius, with nothing
 * left to trade against it). BlueMind's REST API logs in with the mailbox's own IMAP
 * password (verified in phase 0, section 13.B.1), so no engine this add-on foresees
 * needs more than what this type resolves. An implementer who finds one that does
 * must stop and raise it, not widen this type: the widening is the Architects Lead's
 * call, never a convenience.
 * <p>
 * Anything an engine obtains here is for the call at hand: never stored, never logged,
 * never placed in a URL (the three properties the CalDAV add-on's BlueMind session
 * already keeps, plan section 3.3). The session is closed by whoever built it, in a
 * {@code finally}.
 */
public final class MailboxAclSession implements AutoCloseable {

  private static final Log LOG = ExoLogger.getLogger(MailboxAclSession.class);

  /**
   * Opens the caller's own IMAP store, on demand. Wired by the service to the
   * caller's own connector and username; the engine never sees how.
   */
  @FunctionalInterface
  public interface StoreOpener {

    /**
     * @return the connected store
     * @throws MessagingException when the mailbox cannot be reached
     * @throws ConnectorCredentialsException when the configured provider cannot
     *           produce credentials for the caller
     */
    Store open() throws MessagingException, ConnectorCredentialsException;
  }

  /**
   * Resolves the caller's own HTTP credential material, on demand -- the
   * {@code Authorization} header value the platform's credentials contract produces
   * for this connector and user on its HTTP channel, the same one the CardDAV sync
   * uses. What a vendor REST engine mints its per-call session from; never stored by
   * anyone.
   */
  @FunctionalInterface
  public interface HttpAuthorizationResolver {

    /**
     * @return the header value
     * @throws ConnectorCredentialsException when the configured provider cannot
     *           produce HTTP material for the caller
     */
    String resolve() throws ConnectorCredentialsException;
  }

  /**
   * Resolves the caller's own mail credential material, on demand -- the login and the
   * password (or the session id a provider presents as one) that the platform's
   * credentials contract produces for this connector and user on the IMAP channel. What
   * a protocol authenticating against the IMAP directory (ManageSieve's SASL
   * {@code PLAIN}) sends; never stored by anyone.
   */
  @FunctionalInterface
  public interface MailCredentialsResolver {

    /**
     * Resolves the material.
     *
     * @return the login and password, or null when the provider produced none
     * @throws ConnectorCredentialsException when the configured provider cannot
     *           produce material for the caller
     */
    PasswordAuthentication resolve() throws ConnectorCredentialsException;
  }

  private final EmailConnector            connector;

  private final String                    username;

  private final String                    mailboxIdentifier;

  private final StoreOpener               storeOpener;

  private final HttpAuthorizationResolver httpAuthorizationResolver;

  private final MailCredentialsResolver   mailCredentialsResolver;

  private Store                           store;

  /**
   * A session for one caller. Built by the service from the caller's own connected
   * setting -- see the class comment for what the two resolvers must be wired to.
   *
   * @param connector the connector preset the caller is connected on
   * @param username the eXo user acting
   * @param mailboxIdentifier the caller's own mailbox identifier, as the ACL and the
   *          namespace name it
   * @param storeOpener opens the caller's own IMAP store; null when no IMAP transport
   *          exists for this caller
   * @param httpAuthorizationResolver resolves the caller's own HTTP material; null
   *          when the platform's credentials contract is not available
   */
  public MailboxAclSession(EmailConnector connector,
                           String username,
                           String mailboxIdentifier,
                           StoreOpener storeOpener,
                           HttpAuthorizationResolver httpAuthorizationResolver) {
    this(connector, username, mailboxIdentifier, storeOpener, httpAuthorizationResolver, null);
  }

  /**
   * A session for one caller, with the caller's own mail credential material as well.
   * Built by the service from the caller's own connected setting -- see the class
   * comment for what the three resolvers must be wired to.
   *
   * @param connector the connector preset the caller is connected on
   * @param username the eXo user acting
   * @param mailboxIdentifier the caller's own mailbox identifier, as the ACL and the
   *          namespace name it
   * @param storeOpener opens the caller's own IMAP store; null when no IMAP transport
   *          exists for this caller
   * @param httpAuthorizationResolver resolves the caller's own HTTP material; null
   *          when the platform's credentials contract is not available
   * @param mailCredentialsResolver resolves the caller's own IMAP-channel login and
   *          password; null when the platform's credentials contract is not available
   */
  public MailboxAclSession(EmailConnector connector,
                           String username,
                           String mailboxIdentifier,
                           StoreOpener storeOpener,
                           HttpAuthorizationResolver httpAuthorizationResolver,
                           MailCredentialsResolver mailCredentialsResolver) {
    this.connector = connector;
    this.username = username;
    this.mailboxIdentifier = mailboxIdentifier;
    this.storeOpener = storeOpener;
    this.httpAuthorizationResolver = httpAuthorizationResolver;
    this.mailCredentialsResolver = mailCredentialsResolver;
  }

  /**
   * @return the connector preset the caller is connected on -- where a vendor engine
   *         reads its endpoint from ({@code webMailUrl} for BlueMind's core API, never
   *         {@code imapUrl}: on the deployment observed they are different hosts)
   */
  public EmailConnector connector() {
    return connector;
  }

  /**
   * @return the eXo user this session acts as
   */
  public String username() {
    return username;
  }

  /**
   * @return the caller's own mailbox identifier, as the ACL and the namespace name it
   */
  public String mailboxIdentifier() {
    return mailboxIdentifier;
  }

  /**
   * The caller's own IMAP store, opened on the first call and reused after. An engine
   * that speaks IMAP calls this; one that does not never pays for a connection.
   *
   * @return the connected store
   * @throws MailboxAclException {@code NOT_IMAP} when this caller has no IMAP transport,
   *           {@code UNREACHABLE} when the mailbox cannot be connected -- the library's
   *           text goes to the DEBUG log, never into the code
   */
  public Store store() {
    if (store != null) {
      return store;
    }
    if (storeOpener == null) {
      throw new MailboxAclException(MailboxAclException.NOT_IMAP, "no IMAP transport for " + username);
    }
    try {
      store = storeOpener.open();
      return store;
    } catch (MessagingException | ConnectorCredentialsException e) {
      LOG.debug("Mailbox of {} could not be connected for a delegation operation: {}", username, e.getMessage());
      throw new MailboxAclException(MailboxAclException.UNREACHABLE, e);
    }
  }

  /**
   * @return whether {@link #store()} has been called and answered -- for the closing,
   *         and for a test that pins "nothing was opened"
   */
  public boolean hasOpenStore() {
    return store != null;
  }

  /**
   * The caller's own HTTP credential material, resolved on demand through the
   * platform's credentials contract -- what a vendor REST engine mints its per-call
   * session key from. Used for the call, then dropped.
   *
   * @return the {@code Authorization} header value for the caller
   * @throws MailboxAclException {@code UNREACHABLE} when the contract is not available
   *           or the provider produces no HTTP material for the caller
   */
  public String httpAuthorization() {
    if (httpAuthorizationResolver == null) {
      throw new MailboxAclException(MailboxAclException.UNREACHABLE, "no HTTP credential material for " + username);
    }
    try {
      return httpAuthorizationResolver.resolve();
    } catch (ConnectorCredentialsException e) {
      LOG.debug("HTTP credentials of {} could not be resolved for a delegation operation: {}", username, e.getMessage());
      throw new MailboxAclException(MailboxAclException.UNREACHABLE, e);
    }
  }

  /**
   * The caller's own mail credential material, resolved on demand through the
   * platform's credentials contract on the IMAP channel -- what a protocol that
   * authenticates against the IMAP directory sends (ManageSieve's SASL {@code PLAIN}),
   * and what a vendor REST login takes as its password. Resolved afresh on every call,
   * so a caller that told the provider the material was refused gets new material; used
   * for the call, then dropped.
   *
   * @return the login and password, or null when the provider produced none
   * @throws MailboxAclException {@code UNREACHABLE} when the contract is not available
   *           or the provider cannot produce material for the caller
   */
  public PasswordAuthentication mailCredentials() {
    if (mailCredentialsResolver == null) {
      throw new MailboxAclException(MailboxAclException.UNREACHABLE, "no mail credential material for " + username);
    }
    try {
      return mailCredentialsResolver.resolve();
    } catch (ConnectorCredentialsException e) {
      LOG.debug("Mail credentials of {} could not be resolved: {}", username, e.getMessage());
      throw new MailboxAclException(MailboxAclException.UNREACHABLE, e);
    }
  }

  /**
   * The login and password an {@link Authenticator} of the credentials contract hands
   * out, asked through {@link Session#requestPasswordAuthentication}, the public door
   * JavaMail itself uses.
   *
   * @param authenticator the authenticator, possibly null
   * @return its login and password, or null when there is no authenticator or it
   *         produced nothing
   */
  public static PasswordAuthentication passwordAuthentication(Authenticator authenticator) {
    if (authenticator == null) {
      return null;
    }
    return Session.getInstance(new Properties(), authenticator).requestPasswordAuthentication(null, 0, "imap", null, null);
  }

  /**
   * Closes the store if one was opened, swallowing the close's own failure. Nothing
   * else is held.
   */
  @Override
  public void close() {
    if (store == null) {
      return;
    }
    try {
      store.close();
    } catch (MessagingException e) {
      LOG.debug("Store close failed: {}", e.getMessage());
    } finally {
      store = null;
    }
  }
}
