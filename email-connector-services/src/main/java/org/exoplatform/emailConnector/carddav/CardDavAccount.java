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
package org.exoplatform.emailConnector.carddav;

/**
 * Whose address book a CardDAV conversation is with, and through which provider
 * it authenticates: the connector row, the provider that row is configured with,
 * and the eXo login the material is resolved for.
 * <p>
 * <b>What it deliberately does not carry is the material itself.</b> Handing the
 * client a produced {@code Authorization} value would fix that value for the whole
 * conversation, and a Digest provider cannot work that way — its header hashes the
 * request method and URI, which are known at the request and nowhere earlier. So
 * the client carries what it needs to <i>ask</i>, and asks once per request. This
 * is the same per-request seam {@code CalDavEndpoint} keeps on the calendar side,
 * for the same reason.
 * <p>
 * Its only constructor is package-private, the same containment style as
 * {@code CalDavEndpoint}: no caller outside this package can mint one, so a client
 * cannot be handed an account somebody assembled by hand. The service builds it
 * through {@link CardDavClient#accountOf(Long, String, String)}.
 */
public final class CardDavAccount {

  private final Long   connectorId;

  private final String providerName;

  private final String username;

  /**
   * An account, minted from a resolved connector row.
   *
   * @param connectorId identifier of the connector preset the account is bound
   *          to
   * @param providerName name of the credentials provider that preset is
   *          configured with
   * @param username the eXo login the material is resolved for — the key the
   *          provider derives the remote account from, and not something the
   *          stored setting carries
   */
  CardDavAccount(Long connectorId, String providerName, String username) {
    this.connectorId = connectorId;
    this.providerName = providerName;
    this.username = username;
  }

  /**
   * The connector preset this account is bound to.
   *
   * @return the preset id
   */
  public Long getConnectorId() {
    return connectorId;
  }

  /**
   * The credentials provider the preset is configured with.
   *
   * @return the provider name
   */
  public String getProviderName() {
    return providerName;
  }

  /**
   * The eXo login every request of this conversation resolves its material for.
   *
   * @return the login
   */
  public String getUsername() {
    return username;
  }

}
