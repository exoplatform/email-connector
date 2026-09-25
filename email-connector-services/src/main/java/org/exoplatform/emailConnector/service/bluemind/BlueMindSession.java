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
 * A session on a BlueMind core API, as its login answered it: the root it was opened at,
 * the account it acts as, and the key the other calls carry in {@code X-BM-ApiKey}.
 * <p>
 * The key is a credential. It is never logged, never placed in a URL, never part of an
 * exception message; {@link #toString()} leaves it out.
 *
 * @param apiRoot the core API root the session was opened at
 * @param userUid the uid of the account the login authenticated, which is also its
 *          mailbox uid
 * @param domainUid the uid of that account's domain
 * @param apiKey the session key
 * @param timeZone the account's own time zone as BlueMind's settings hold it -- in the
 *          login answer under {@code authUser.settings.timezone}, not at its top level;
 *          possibly null
 */
public record BlueMindSession(String apiRoot, String userUid, String domainUid, String apiKey, String timeZone) {

  /**
   * The session without its key.
   *
   * @return a description safe to log
   */
  @Override
  public String toString() {
    return "BlueMindSession[apiRoot=" + apiRoot + ", userUid=" + userUid + ", domainUid=" + domainUid + ", timeZone=" + timeZone
        + "]";
  }
}
