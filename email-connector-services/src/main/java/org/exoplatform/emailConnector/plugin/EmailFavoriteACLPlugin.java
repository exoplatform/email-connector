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
import org.exoplatform.services.security.Identity;
import org.exoplatform.social.metadata.FavoriteACLPlugin;
import org.exoplatform.social.metadata.favorite.FavoriteService;

import jakarta.annotation.PostConstruct;

/**
 * Decides who may favorite an email.
 * <p>
 * This is not the same check as {@link EmailAclPlugin}, and registering only
 * that one would leave the door open: the favorites service asks its own
 * {@code FavoriteACLPlugin} registry, keyed by object type, and answers
 * <em>true</em> for any type that has no plugin. Without this class, the generic
 * favorites endpoint would let any user favorite any email id — someone else's
 * mail included.
 * <p>
 * The rule itself is the mailbox owner's, so it is delegated to
 * {@link EmailAclPlugin} rather than restated here: an email belongs to exactly
 * one mailbox, and only its owner sees it.
 */
@Component
public class EmailFavoriteACLPlugin extends FavoriteACLPlugin {

  @Autowired
  private FavoriteService favoriteService;

  @Autowired
  private EmailAclPlugin  emailAclPlugin;

  /**
   * Registers this plugin with the favorites service, which keys its plugins by
   * entity type.
   */
  @PostConstruct
  public void init() {
    favoriteService.addFavoriteACLPlugin(this);
  }

  /**
   * The object type this plugin answers for, matching the type the front-end
   * registers in the Favorites drawer.
   *
   * @return the email favorites object type
   */
  @Override
  public String getEntityType() {
    return EmailConnectorUtils.EMAIL_FEATURE;
  }

  /**
   * Whether the user may favorite the given email, i.e. whether that email is
   * one of their own.
   *
   * @param userIdentity the identity asking to favorite
   * @param entityId the candidate email id, as an untrusted string
   * @return {@code true} only when the id resolves to a cached email owned by
   *         that identity
   */
  @Override
  public boolean canCreateFavorite(Identity userIdentity, String entityId) {
    return emailAclPlugin.hasPermission(entityId, null, userIdentity);
  }
}
