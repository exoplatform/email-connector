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
package org.exoplatform.emailConnector.plugin;

import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import org.exoplatform.container.PortalContainer;
import org.exoplatform.emailConnector.model.Email;
import org.exoplatform.emailConnector.service.EmailBoxService;
import org.exoplatform.emailConnector.utils.EmailConnectorUtils;
import org.exoplatform.portal.config.UserACL;
import org.exoplatform.services.security.Identity;

import io.meeds.portal.plugin.AclPlugin;
import jakarta.annotation.PostConstruct;

@Component
public class EmailAclPlugin implements AclPlugin {

  public static final String OBJECT_TYPE            = EmailConnectorUtils.EMAIL_FEATURE;

  @Autowired
  private PortalContainer    container;

  @Autowired
  private EmailBoxService    emailBoxService;

  @PostConstruct
  public void init() {
    container.getComponentInstanceOfType(UserACL.class).addAclPlugin(this);
  }

  @Override
  public String getObjectType() {
    return OBJECT_TYPE;
  }

  /**
   * An email is only visible to its mailbox owner. The object id reaches this
   * check as an arbitrary caller-supplied string — the favorites REST endpoints
   * and the MCP favorites tools forward whatever identifier an agent typed for
   * object type "email" — so an id this plugin does not recognise (null, blank,
   * non-numeric, out of {@code long} range, or numeric but matching no cached
   * email) must simply answer {@code false}: a permission check degrades to
   * "no", it never throws.
   *
   * @param objectId the candidate email id, as an untrusted string
   * @param permissionType the permission being checked (unused: owner-only)
   * @param identity the identity asking for access
   * @return {@code true} only when the id resolves to a cached email owned by
   *         the given identity
   */
  @Override
  public boolean hasPermission(String objectId, String permissionType, Identity identity) {
    if (identity == null || identity.getUserId() == null || StringUtils.isBlank(objectId)) {
      return false;
    }
    long emailId;
    try {
      emailId = Long.parseLong(objectId);
    } catch (NumberFormatException e) {
      // also covers ids beyond Long range — not an email of ours either
      return false;
    }
    Email email = emailBoxService.getEmailById(emailId, null);
    return email != null && email.getUserId() != null && email.getUserId().equals(identity.getUserId());
  }
}
