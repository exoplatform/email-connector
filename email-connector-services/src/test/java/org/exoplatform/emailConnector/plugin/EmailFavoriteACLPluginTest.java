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
public class EmailFavoriteACLPluginTest {

  private static final String        TEST_USER = "testuser";

  private static final String        EMAIL_ID  = "5";

  @Mock
  private FavoriteService            favoriteService;

  @Mock
  private EmailAclPlugin             emailAclPlugin;

  @InjectMocks
  private EmailFavoriteACLPlugin     emailFavoriteACLPlugin;

  /**
   * Registration must reach the favorites service: it keys its plugins by entity
   * type, and a type with no plugin registered is one every user may favorite.
   */
  @Test
  void init() {
    emailFavoriteACLPlugin.init();
    verify(favoriteService).addFavoriteACLPlugin(emailFavoriteACLPlugin);
  }

  /**
   * The entity type must stay "email" — it is the key both the favorites service
   * and the Favorites drawer use to route to this plugin, so a change here
   * silently unregisters the check.
   */
  @Test
  void getEntityType() {
    assertEquals("email", emailFavoriteACLPlugin.getEntityType());
  }

  /**
   * Favoriting one's own email is allowed, and the rule is the mailbox owner's
   * one — delegated, not restated.
   */
  @Test
  void canCreateFavoriteForOwnEmail() {
    Identity identity = new Identity(TEST_USER);
    when(emailAclPlugin.hasPermission(EMAIL_ID, null, identity)).thenReturn(true);
    assertTrue(emailFavoriteACLPlugin.canCreateFavorite(identity, EMAIL_ID));
  }

  /**
   * Somebody else's email cannot be favorited: this is the case the plugin
   * exists for, since without it the generic favorites endpoint would accept any
   * email id.
   */
  @Test
  void canCreateFavoriteForSomeoneElsesEmail() {
    Identity identity = new Identity(TEST_USER);
    when(emailAclPlugin.hasPermission(EMAIL_ID, null, identity)).thenReturn(false);
    assertFalse(emailFavoriteACLPlugin.canCreateFavorite(identity, EMAIL_ID));
  }

  /**
   * An id that resolves to no email, and a missing identity, are both refused —
   * the underlying owner check answers false rather than throwing, and this
   * plugin must not turn that into an accepted favorite.
   */
  @Test
  void canCreateFavoriteForUnresolvableIdOrIdentity() {
    when(emailAclPlugin.hasPermission("not-a-number", null, null)).thenReturn(false);
    assertFalse(emailFavoriteACLPlugin.canCreateFavorite(null, "not-a-number"));
  }
}
