/**
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

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import org.exoplatform.emailConnector.model.EmailBox;
import org.exoplatform.emailConnector.service.EmailBoxService;
import org.exoplatform.emailConnector.utils.EmailConnectorUtils;
import org.exoplatform.portal.config.UserACL;

import io.meeds.social.category.plugin.CategoryPlugin;

@Service
public class EmailCategoryPlugin implements CategoryPlugin {

  public static final String OBJECT_TYPE = EmailConnectorUtils.EMAIL_FEATURE;

  @Autowired
  private UserACL            userAcl;
  
  @Autowired
  EmailBoxService emailBoxService;

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
    // The add-on's own default email categories are always offered, plus any other
    // category already applied to the user's emails, so the email category tree is
    // never empty (the read-only filter and the assign picker both read from here).
    Set<Long> categoryIds = new LinkedHashSet<>(emailBoxService.getDefaultEmailCategoryIds());
    try {
      EmailBox emailBox = emailBoxService.getEmailBox(username);
      emailBox.getEmails()
              .stream()
              .filter(email -> email.getCategoryIds() != null)
              .flatMap(email -> email.getCategoryIds().stream())
              .forEach(categoryIds::add);
    } catch (IllegalAccessException e) {
      // Fall through: still return the default categories.
    }
    return new ArrayList<>(categoryIds);
  }
}
