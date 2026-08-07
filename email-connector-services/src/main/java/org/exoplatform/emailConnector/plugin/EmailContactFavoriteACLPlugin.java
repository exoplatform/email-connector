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

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import org.exoplatform.services.security.Identity;
import org.exoplatform.social.metadata.FavoriteACLPlugin;
import org.exoplatform.social.metadata.favorite.FavoriteService;

import jakarta.annotation.PostConstruct;

/**
 * Decides who may favorite a contact.
 * <p>
 * This plugin is a security control, not polish: the favorites service asks its
 * own {@code FavoriteACLPlugin} registry, keyed by object type, and answers
 * <em>true</em> for any type that has no plugin. Without this class, the
 * generic favorites endpoint would let any user favorite any contact id —
 * someone else's contacts included. {@link EmailContactAclPlugin} alone does
 * not close that door, because favorite creation goes through this registry,
 * not the generic ACL one.
 * <p>
 * The rule itself is the store owner's, so it is delegated to
 * {@link EmailContactAclPlugin} rather than restated here: a contact belongs to
 * exactly one store, and only its owner sees it.
 */
@Component
public class EmailContactFavoriteACLPlugin extends FavoriteACLPlugin {

  @Autowired
  private FavoriteService       favoriteService;

  @Autowired
  private EmailContactAclPlugin emailContactAclPlugin;

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
   * @return the contact favorites object type
   */
  @Override
  public String getEntityType() {
    return EmailContactAclPlugin.OBJECT_TYPE;
  }

  /**
   * Whether the user may favorite the given contact, i.e. whether that contact
   * is a visible row of their own store.
   *
   * @param userIdentity the identity asking to favorite
   * @param entityId the candidate contact id, as an untrusted string
   * @return {@code true} only when the id resolves to a visible contact owned
   *         by that identity
   */
  @Override
  public boolean canCreateFavorite(Identity userIdentity, String entityId) {
    return emailContactAclPlugin.hasPermission(entityId, null, userIdentity);
  }
}
