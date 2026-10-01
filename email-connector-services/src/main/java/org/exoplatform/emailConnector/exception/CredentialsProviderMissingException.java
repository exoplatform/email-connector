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
package org.exoplatform.emailConnector.exception;

/**
 * A connect through a connector's provider was refused before anything was asked of the
 * mail server, because no credentials provider of that name is registered: an add-on's,
 * while that add-on is not installed or has not started yet.
 * <p>
 * An {@link IllegalStateException} on purpose, like the other refusals of the one-click
 * connect: it travels the same path, which the REST layer answers with <b>500</b>, and
 * lets a caller that meets it at every attempt -- managed mode, at every login of every
 * governed user -- tell it apart from a refusal of this account and stay quiet about it,
 * the resolver having said it once for the provider's name.
 */
public class CredentialsProviderMissingException extends IllegalStateException {

  private static final long serialVersionUID = 1L;

  private final String      providerName;

  /**
   * A refusal naming the provider that is not registered.
   *
   * @param providerName the provider the connector is configured with
   */
  public CredentialsProviderMissingException(String providerName) {
    super("No credentials provider named " + providerName + " is registered");
    this.providerName = providerName;
  }

  /**
   * The provider the connector is configured with.
   *
   * @return its name
   */
  public String getProviderName() {
    return providerName;
  }
}
