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
package org.exoplatform.emailConnector.service.bluemind;

/**
 * The one port through which this add-on talks to a BlueMind server's core REST API,
 * shared by every BlueMind engine: the automatic reply today, the mailbox ACL engine and
 * the server rules engine when they land, each adding the calls it needs here rather than
 * a second port.
 * <p>
 * Values in, values out: no credentials service, no cache, no Spring type in the
 * contract. The caller resolves the login and password from the caller's own session
 * and hands them to {@link #login}; every other call takes the {@link BlueMindSession}
 * that login answered. The calls on the session's own settings -- the automatic reply and
 * the forward -- address <b>the mailbox of that login and no other</b>: the mailbox and
 * domain uids come from the login answer, never from a request, so these calls take no
 * mailbox parameter at all. A call that must name another mailbox (the ACL calls of the
 * delegation engine) is added with its own parameter and its own review.
 * <p>
 * <b>What this port can never do.</b> It offers no write of {@code _filter} (the whole
 * {@code MailFilter}, rules and forward included) and no write of {@code _forwarding}.
 * The automatic reply is written through {@code _vacation} only, so another client's
 * rules and forward cannot be clobbered by a reply saved in eXo. A server-rules engine
 * that needs the rule calls adds them under their own review.
 * <p>
 * <b>The one implementation to come</b> is an adapter to {@code bluemind-commons}, the
 * platform's BlueMind client library, not published yet: one class implementing this
 * interface over that library, for every engine. Until it exists, this add-on ships no
 * implementation, and an engine that finds none answers its probe unsupported with
 * {@code emailConnector.rules.bluemind.transportMissing} without a network call. The
 * adapter carries the wire rules (the login's JSON-string body under
 * {@code application/json}, the session key in {@code X-BM-ApiKey}, the mailbox calls
 * under {@code mailboxes/{domainUid}/{mailboxUid}/}, no redirect followed, bounded
 * answers, the key never logged).
 */
public interface BlueMindMailboxTransport {

  /**
   * Opens a session on the core API as the given account.
   *
   * @param apiRoot the core API root, e.g. {@code https://mail.example.com/api}, resolved
   *          by the caller from the administrator's configuration
   * @param login the account's login, as the IMAP channel knows it
   * @param password the account's password, or the session id a credentials provider
   *          presents as one
   * @return the session: who it acts as, and the key the other calls carry
   * @throws BlueMindTransportException {@code AUTHENTICATION} when the server refuses the
   *           login, {@code NOT_FOUND} when no login endpoint answers at that root (the
   *           configured core URL is not BlueMind's), {@code UNREACHABLE} when it cannot be
   *           reached, {@code PROTOCOL} when its answer does not name the account's mailbox
   */
  BlueMindSession login(String apiRoot, String login, String password) throws BlueMindTransportException;

  /**
   * Closes a session. Best-effort: a session that could not be closed expires on the
   * server's own clock, so this never fails the caller.
   *
   * @param session the session, possibly null
   */
  void logout(BlueMindSession session);

  /**
   * The automatic reply of the session's own mailbox ({@code GET _vacation}).
   *
   * @param session the session
   * @return what the server holds; a value with every member empty when nothing was ever
   *         set
   * @throws BlueMindTransportException when the server refuses or fails
   */
  BlueMindVacation getVacation(BlueMindSession session) throws BlueMindTransportException;

  /**
   * Replaces the automatic reply of the session's own mailbox ({@code POST _vacation}).
   * Touches neither the rules nor the forward.
   *
   * @param session the session
   * @param vacation the reply
   * @throws BlueMindTransportException when the server refuses or fails
   */
  void setVacation(BlueMindSession session, BlueMindVacation vacation) throws BlueMindTransportException;

  /**
   * The forward of the session's own mailbox ({@code GET _forwarding}), read only.
   *
   * @param session the session
   * @return what the server holds
   * @throws BlueMindTransportException when the server refuses or fails
   */
  BlueMindForwarding getForwarding(BlueMindSession session) throws BlueMindTransportException;
}
