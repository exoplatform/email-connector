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

import java.util.List;

import jakarta.annotation.PostConstruct;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import org.exoplatform.emailConnector.service.EmailBoxService;
import org.exoplatform.emailConnector.utils.EmailConnectorUtils;
import org.exoplatform.portal.config.UserACL;

import io.meeds.social.category.plugin.CategoryPlugin;
import io.meeds.social.category.service.CategoryPluginService;

@Service
public class EmailCategoryPlugin implements CategoryPlugin {

  public static final String    OBJECT_TYPE = EmailConnectorUtils.EMAIL_FEATURE;

  @Autowired
  private UserACL               userAcl;

  @Autowired
  private EmailBoxService        emailBoxService;

  @Autowired
  private CategoryPluginService categoryPluginService;

  /**
   * Registers this plugin with the platform's category service. Email-connector's
   * Spring context builds after social's, so the {@code @Autowired List<CategoryPlugin>}
   * there is resolved before this bean exists and would miss it — leaving the "email"
   * object type on the linked-ids fallback. Self-registering here makes our plugin the
   * authoritative source for the email category tree.
   */
  @PostConstruct
  public void init() {
    categoryPluginService.addPlugin(this);
  }

  @Override
  public String getType() {
    return OBJECT_TYPE;
  }

  @Override
  public boolean canAccess(String emailId, String username) {
    return userAcl.hasAccessPermission(OBJECT_TYPE, emailId, username);
  }

  @Override
  public boolean canEdit(String emailId, String username) {
    return userAcl.hasAccessPermission(OBJECT_TYPE, emailId, username);
  }

  @Override
  public List<Long> getCategoryIds(long spaceId, String username) {
    // The add-on's own default email categories are the complete email-category
    // vocabulary, so the email tree exposes exactly that current set. Both the
    // read-only filter and the assign picker read from here; a category removed
    // from the defaults therefore disappears at once, even if an older email is
    // still linked to it (its stale link is simply ignored, never re-listed).
    return emailBoxService.getDefaultEmailCategoryIds();
  }
}
