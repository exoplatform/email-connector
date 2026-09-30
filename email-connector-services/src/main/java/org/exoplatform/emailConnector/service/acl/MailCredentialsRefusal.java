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

import org.exoplatform.services.connector.credentials.ConnectorCredentialsChannel;

/**
 * What a {@link MailboxAclSession} does when a server refuses the credential material it
 * resolved for its caller: tell the provider, so the next production does not hand the
 * same material out again, and say whether one more attempt on fresh material is worth
 * it. Wired by the service to the caller's own connector and username, exactly as the
 * session's resolvers are: an engine that holds the session reaches the credentials
 * contract through it, and never needs the host's credentials resolver, which stays
 * private to the host's context.
 * <p>
 * The contract's rule is the resolver's (EXO-89649): once, then one more attempt with
 * fresh material, never a loop; and only for a provider that produces its material
 * itself, since one carrying what the user typed would hand the same password back and
 * the second refusal would count against the account's lockout.
 */
public interface MailCredentialsRefusal {

  /**
   * Tells the provider that the material it produced for the session's caller was
   * refused on that channel. Never throws: it runs inside failure handling, where an
   * exception of its own would hide the refusal it reports.
   *
   * @param channel the channel the material was refused on
   */
  void invalidate(ConnectorCredentialsChannel channel);

  /**
   * Whether a refused credential of the session's connector is worth one more attempt
   * after {@link #invalidate(ConnectorCredentialsChannel)}.
   *
   * @return true when the provider may produce new material
   */
  boolean retriesAfterRefusal();
}
