/**
 * Copyright (C) 2025 eXo Platform SAS
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
package org.exoplatform.emailConnector.plugin;

import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import org.exoplatform.container.PortalContainer;
import org.exoplatform.emailConnector.model.EmailContact;
import org.exoplatform.emailConnector.storage.EmailContactStorage;
import org.exoplatform.portal.config.UserACL;
import org.exoplatform.services.security.Identity;

import io.meeds.portal.plugin.AclPlugin;
import jakarta.annotation.PostConstruct;

@Component
public class EmailContactAclPlugin implements AclPlugin {

  /** The favorites object type of a contact, shared with the favorite plugins, the service and the front-end. */
  public static final String  OBJECT_TYPE = "contact";

  @Autowired
  private PortalContainer     container;

  @Autowired
  private EmailContactStorage emailContactStorage;

  /**
   * Registers this plugin with the kernel {@link UserACL} singleton, which is
   * where the platform's generic permission checks (favorites among them) look
   * plugins up by object type.
   */
  @PostConstruct
  public void init() {
    container.getComponentInstanceOfType(UserACL.class).addAclPlugin(this);
  }

  /**
   * The object type this plugin answers for.
   *
   * @return the contact favorites object type
   */
  @Override
  public String getObjectType() {
    return OBJECT_TYPE;
  }

  /**
   * A contact is only visible to its store owner. The object id reaches this
   * check as an arbitrary caller-supplied string — the favorites REST endpoints
   * forward whatever identifier was typed for object type "contact" — so an id
   * this plugin does not recognise (null, blank, non-numeric, out of
   * {@code long} range, or numeric but matching no row) must simply answer
   * {@code false}: a permission check degrades to "no", it never throws. A
   * SUPPRESSED row answers false too — the user removed that contact, and every
   * read in the service layer already treats it as nonexistent.
   * <p>
   * The row is read from the storage rather than through
   * {@code EmailContactService.getContact}, whose profile enrichment costs a
   * directory query a permission check has no use for.
   *
   * @param objectId the candidate contact id, as an untrusted string
   * @param permissionType the permission being checked (unused: owner-only)
   * @param identity the identity asking for access
   * @return {@code true} only when the id resolves to a visible contact owned
   *         by the given identity
   */
  @Override
  public boolean hasPermission(String objectId, String permissionType, Identity identity) {
    if (identity == null || identity.getUserId() == null || StringUtils.isBlank(objectId)) {
      return false;
    }
    long contactId;
    try {
      contactId = Long.parseLong(objectId);
    } catch (NumberFormatException e) {
      // also covers ids beyond Long range — not a contact of ours either
      return false;
    }
    EmailContact contact = emailContactStorage.getContactById(contactId);
    return contact != null && !contact.isSuppressed() && StringUtils.equals(contact.getUserId(), identity.getUserId());
  }
}
