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

import org.exoplatform.services.connector.credentials.ConnectorCredentialsException;

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
