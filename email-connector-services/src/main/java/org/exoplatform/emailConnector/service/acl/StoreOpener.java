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

import javax.mail.MessagingException;
import javax.mail.Store;
import org.exoplatform.services.connector.credentials.ConnectorCredentialsException;

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
