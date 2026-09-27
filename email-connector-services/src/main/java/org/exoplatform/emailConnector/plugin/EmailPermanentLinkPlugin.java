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

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import org.exoplatform.emailConnector.utils.EmailConnectorUtils;
import org.exoplatform.portal.config.UserACL;
import org.exoplatform.portal.config.UserPortalConfigService;
import org.exoplatform.services.security.Identity;

import io.meeds.portal.permlink.model.PermanentLinkObject;
import io.meeds.portal.permlink.plugin.PermanentLinkPlugin;
import io.meeds.portal.permlink.service.PermanentLinkService;
import jakarta.annotation.PostConstruct;

/**
 * The permanent link of a mail.
 * <p>
 * Social's content-link registry computes the URI of every link it returns
 * through the permanent-link service, and fails the link when no plugin serves
 * its type: without this plugin a mail could never be resolved as a content
 * link. The link opens the mailbox; the mail itself stays readable by its owner
 * only.
 */
@Component
public class EmailPermanentLinkPlugin implements PermanentLinkPlugin {

  public static final String      OBJECT_TYPE = EmailConnectorUtils.EMAIL_FEATURE;

  public static final String      URL_FORMAT  = "/portal/%s?openEmailBox=true";

  @Autowired
  private UserPortalConfigService portalConfigService;

  @Autowired
  private PermanentLinkService    permanentLinkService;

  @Autowired
  private UserACL                 userAcl;

  /**
   * Registers this plugin in the permanent-link service, the way the content
   * add-on registers its news plugin.
   */
  @PostConstruct
  public void init() {
    permanentLinkService.addPlugin(this);
  }

  /**
   * @return {@code email}
   */
  @Override
  public String getObjectType() {
    return OBJECT_TYPE;
  }

  /**
   * A mail is accessible to its mailbox owner only. The decision is the one
   * {@link EmailAclPlugin} already takes, reached through {@link UserACL}: an
   * unknown id answers {@code false} like another user's mail, so the answer
   * never tells whether a mail id exists.
   *
   * @param object the designated mail
   * @param identity the identity accessing it
   * @return {@code true} only when the mail belongs to that identity
   */
  @Override
  public boolean canAccess(PermanentLinkObject object, Identity identity) {
    return object != null && userAcl.hasAccessPermission(OBJECT_TYPE, object.getObjectId(), identity);
  }

  /**
   * The URL a mail link leads to: the mailbox, opened on the site's home page.
   * It carries no mail data, so it is the same for every mail and every user.
   *
   * @param object the designated mail
   * @return the mailbox URL
   */
  @Override
  public String getDirectAccessUrl(PermanentLinkObject object) {
    return String.format(URL_FORMAT, portalConfigService.getMetaPortal());
  }

}
