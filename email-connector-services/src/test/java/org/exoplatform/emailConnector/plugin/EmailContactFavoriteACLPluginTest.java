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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import org.exoplatform.services.security.Identity;
import org.exoplatform.social.metadata.favorite.FavoriteService;

@ExtendWith(MockitoExtension.class)
public class EmailContactFavoriteACLPluginTest {

  private static final String           TEST_USER  = "testuser";

  private static final String           CONTACT_ID = "7";

  @Mock
  private FavoriteService               favoriteService;

  @Mock
  private EmailContactAclPlugin         emailContactAclPlugin;

  @InjectMocks
  private EmailContactFavoriteACLPlugin emailContactFavoriteACLPlugin;

  /**
   * Registration must go to the favorites service's own plugin registry: it is
   * the registry favorite creation consults, and a type absent from it is
   * answered TRUE for everybody — which is the hole this plugin closes.
   */
  @Test
  void init() {
    emailContactFavoriteACLPlugin.init();
    verify(favoriteService).addFavoriteACLPlugin(emailContactFavoriteACLPlugin);
  }

  /**
   * The entity type must stay "contact", matching {@link EmailContactAclPlugin}
   * and the type the front-end registers in the Favorites drawer.
   */
  @Test
  void getEntityType() {
    assertEquals("contact", emailContactFavoriteACLPlugin.getEntityType());
  }

  /**
   * The decision is the ACL plugin's, verbatim: one rule about who sees a
   * contact, stated once.
   */
  @Test
  void canCreateFavoriteDelegatesToTheAclPlugin() {
    Identity identity = new Identity(TEST_USER);
    when(emailContactAclPlugin.hasPermission(CONTACT_ID, null, identity)).thenReturn(true);
    assertTrue(emailContactFavoriteACLPlugin.canCreateFavorite(identity, CONTACT_ID));

    when(emailContactAclPlugin.hasPermission(CONTACT_ID, null, identity)).thenReturn(false);
    assertFalse(emailContactFavoriteACLPlugin.canCreateFavorite(identity, CONTACT_ID));
  }
}
