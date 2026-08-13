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

import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import org.exoplatform.emailConnector.service.EmailBoxService;
import org.exoplatform.portal.config.UserACL;

import io.meeds.social.category.service.CategoryPluginService;

@ExtendWith(MockitoExtension.class)
public class EmailCategoryPluginTest {

  private static final String   TEST_USER = "testuser";

  private static final String   EMAIL_ID  = "5";

  @Mock
  private UserACL               userAcl;

  @Mock
  private EmailBoxService       emailBoxService;

  @Mock
  private CategoryPluginService categoryPluginService;

  @InjectMocks
  private EmailCategoryPlugin   emailCategoryPlugin;

  /**
   * Self-registration is what makes this plugin the authoritative source for the
   * email category tree: social's Spring context builds first, so its injected
   * plugin list would otherwise miss this bean.
   */
  @Test
  void init() {
    emailCategoryPlugin.init();
    verify(categoryPluginService).addPlugin(emailCategoryPlugin);
  }

  /**
   * The object type must stay "email" — it is the key the category service uses
   * to route the email tree to this plugin.
   */
  @Test
  void getType() {
    assertEquals("email", emailCategoryPlugin.getType());
  }

  @Test
  void canAccess() {
    when(userAcl.hasAccessPermission(EmailCategoryPlugin.OBJECT_TYPE, EMAIL_ID, TEST_USER)).thenReturn(false);
    assertFalse(emailCategoryPlugin.canAccess(EMAIL_ID, TEST_USER));
    when(userAcl.hasAccessPermission(EmailCategoryPlugin.OBJECT_TYPE, EMAIL_ID, TEST_USER)).thenReturn(true);
    assertTrue(emailCategoryPlugin.canAccess(EMAIL_ID, TEST_USER));
  }

  @Test
  void canEdit() {
    when(userAcl.hasAccessPermission(EmailCategoryPlugin.OBJECT_TYPE, EMAIL_ID, TEST_USER)).thenReturn(false);
    assertFalse(emailCategoryPlugin.canEdit(EMAIL_ID, TEST_USER));
    when(userAcl.hasAccessPermission(EmailCategoryPlugin.OBJECT_TYPE, EMAIL_ID, TEST_USER)).thenReturn(true);
    assertTrue(emailCategoryPlugin.canEdit(EMAIL_ID, TEST_USER));
  }

  /**
   * The exposed tree is exactly the add-on's current default categories, so a
   * category dropped from the defaults disappears at once — it is never rebuilt
   * from the links of older emails.
   */
  @Test
  void getCategoryIds() {
    when(emailBoxService.getDefaultEmailCategoryIds()).thenReturn(List.of(11L, 44L));
    assertEquals(List.of(11L, 44L), emailCategoryPlugin.getCategoryIds(0L, TEST_USER));

    // Before the platform's category importer has run, nothing is assignable yet.
    when(emailBoxService.getDefaultEmailCategoryIds()).thenReturn(List.of());
    assertTrue(emailCategoryPlugin.getCategoryIds(0L, TEST_USER).isEmpty());
  }
}
