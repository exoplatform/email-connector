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
package org.exoplatform.emailConnector.service;

import java.util.List;

import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import org.exoplatform.emailConnector.model.EmailConnector;
import org.exoplatform.emailConnector.model.EmailManagedMode;
import org.exoplatform.emailConnector.provider.EmailCredentialsResolver;
import org.exoplatform.emailConnector.storage.EmailConnectorStorage;
import org.exoplatform.services.connector.credentials.managed.ManagedConnectorService;

/**
 * Whether the instance chooses the mail connector for its users instead of
 * letting each of them connect an account of their own.
 * <p>
 * The decision itself — which connector is designated for the {@code email}
 * kind, which groups it does not reach, whether it applies to a given user — is
 * held and answered by {@link ManagedConnectorService} in commons-exo, the same
 * for calendars and mailboxes. What this class adds is the knowledge commons-exo
 * does not have: that the designated id must be a connector that exists and is
 * active, which provider it is configured with (the eligibility criterion
 * commons-exo enforces), and the name the screens print instead of the id.
 * <p>
 * The dependency runs from {@link EmailConnectorService} to this class and never
 * back. This one reads the connector storage directly — the same storage that
 * service reads — precisely so that the connector service can call
 * {@link #checkConnectorNotManaged(long)} on the two writes that would otherwise
 * leave the mode pointing at a connector nobody can be attached to. Injecting
 * the connector service here instead would close a bean cycle.
 */
@Service
public class EmailManagedModeService {

  /** What a save is refused with when the chosen connector cannot be managed. */
  private static final String     NOT_ELIGIBLE = "emailConnector.managed.connectorNotEligible";

  /** What a connector write is refused with when it targets the managed one. */
  private static final String     IN_USE       = "emailConnector.managed.connectorInUse";

  /** What an edit of the managed connector is refused with when its new provider asks the user. */
  private static final String     PROVIDER_NOT_ELIGIBLE = "emailConnector.managed.providerNotEligible";

  @Autowired
  private ManagedConnectorService managedConnectorService;

  @Autowired
  private EmailConnectorStorage   emailConnectorStorage;

  /**
   * The connector the instance points everybody at, when managed mode is on.
   *
   * @return the managed connector's id, null when managed mode is off
   */
  public Long getManagedConnectorId() {
    return managedConnectorService.designationOf(EmailCredentialsResolver.CONNECTOR_KIND);
  }

  /**
   * The groups the instance's choice does not reach.
   *
   * @return the excluded eXo group ids, empty when none
   */
  public List<String> getExcludedGroups() {
    return managedConnectorService.exclusionsOf(EmailCredentialsResolver.CONNECTOR_KIND);
  }

  /**
   * Whether this user is governed by the instance's choice: managed mode is on
   * and they belong to none of the excluded groups.
   * <p>
   * Nobody is managed on nobody's behalf: an anonymous caller has no account to
   * govern.
   *
   * @param username the eXo login, null or blank for an anonymous caller
   * @return true when the choice applies to them
   */
  public boolean isManagedFor(String username) {
    if (StringUtils.isBlank(username)) {
      return false;
    }
    return managedConnectorService.designatedConnectorFor(EmailCredentialsResolver.CONNECTOR_KIND, username) != null;
  }

  /**
   * What the instance decided, and whether it applies to this caller.
   * <p>
   * The connector is named, not merely identified: every screen showing this
   * prints a name. A connector deleted out from under the designation leaves the
   * name null rather than failing — the connector service refuses that deletion,
   * so this is the belt to that braces.
   *
   * @param username the eXo login of the caller, null or blank when anonymous
   * @return the mode as it stands for that caller
   */
  public EmailManagedMode getManagedMode(String username) {
    Long connectorId = getManagedConnectorId();
    if (connectorId == null) {
      return new EmailManagedMode(null, null, List.of(), false);
    }
    EmailConnector connector = emailConnectorStorage.getEmailConnector(connectorId);
    return new EmailManagedMode(connectorId,
                                connector == null ? null : connector.getName(),
                                getExcludedGroups(),
                                isManagedFor(username));
  }

  /**
   * Points the whole instance at one connector, minus the given groups.
   * <p>
   * This class refuses a connector that does not exist or is deactivated — the one
   * nobody can be attached to, which only this add-on can recognise. Everything else is
   * commons-exo's, in one call so that nothing is written when anything is refused: that
   * the caller is an administrator, that the connector's provider asks the user for
   * nothing (Personal cannot be designated, because it would produce an instance
   * connector nobody can connect through), and the order of the two writes.
   *
   * @param connectorId technical identifier of the connector
   * @param excludedGroups the eXo group ids the choice must not reach, null for none
   * @param username the eXo login of the caller
   * @throws IllegalAccessException when the caller is not an administrator
   * @throws IllegalArgumentException carrying {@code emailConnector.managed.connectorNotEligible}
   *           when the connector is unknown or deactivated, or the commons-exo code when
   *           its provider asks the user for something
   */
  public void saveManagedConnector(long connectorId, List<String> excludedGroups, String username) throws IllegalAccessException {
    EmailConnector connector = emailConnectorStorage.getEmailConnector(connectorId);
    if (connector == null || !connector.isActive()) {
      throw new IllegalArgumentException(NOT_ELIGIBLE);
    }
    managedConnectorService.designate(EmailCredentialsResolver.CONNECTOR_KIND,
                                      connectorId,
                                      connector.getAuthProviderName(),
                                      excludedGroups,
                                      username);
  }

  /**
   * Switches managed mode off: users choose their own connector again, and the
   * exclusions go with the designation they qualified. Accounts already connected are
   * untouched — nobody is detached and nothing synchronised is removed by this; what an
   * administrator's change does to the users it attached is EXO-89654's.
   *
   * @param username the eXo login of the caller
   * @throws IllegalAccessException when the caller is not an administrator
   */
  public void clearManagedConnector(String username) throws IllegalAccessException {
    managedConnectorService.clearDesignation(EmailCredentialsResolver.CONNECTOR_KIND, username);
  }

  /**
   * Refuses the edit that would leave the managed connector on a provider asking each
   * user for something — the very thing designating it refused. Any other connector may
   * change provider freely.
   *
   * @param connectorId the connector being edited
   * @param providerName the provider it would be configured with after the edit
   * @throws IllegalArgumentException carrying {@code emailConnector.managed.providerNotEligible}
   *           when the connector is the managed one and the provider is not eligible
   */
  public void checkProviderChangeAllowed(long connectorId, String providerName) {
    Long managedConnectorId = getManagedConnectorId();
    if (managedConnectorId == null || managedConnectorId != connectorId) {
      return;
    }
    try {
      managedConnectorService.requireEligible(providerName);
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException(PROVIDER_NOT_ELIGIBLE, e);
    }
  }

  /**
   * Refuses the connector writes that would strand the mode.
   *
   * @param connectorId the connector about to be deactivated or deleted
   * @throws IllegalArgumentException carrying {@code emailConnector.managed.connectorInUse}
   *           when managed mode points at that connector
   */
  public void checkConnectorNotManaged(long connectorId) {
    Long managedConnectorId = getManagedConnectorId();
    if (managedConnectorId != null && managedConnectorId == connectorId) {
      throw new IllegalArgumentException(IN_USE);
    }
  }
}
