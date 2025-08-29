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

import org.exoplatform.emailConnector.service.EmailConnectorService;

import io.meeds.social.translation.plugin.TranslationPlugin;
import io.meeds.social.translation.service.TranslationService;
import jakarta.annotation.PostConstruct;

@Component
public class EmailConnectorTranslationPlugin extends TranslationPlugin {

  public static final String    EMAIL_CONNECTOR_OBJECT_TYPE = "emailConnector";

  @Autowired
  private TranslationService    translationService;

  @Autowired
  private EmailConnectorService emailConnectorService;

  @PostConstruct
  public void init() {
    translationService.addPlugin(this);
  }

  @Override
  public String getObjectType() {
    return EMAIL_CONNECTOR_OBJECT_TYPE;
  }

  @Override
  public boolean hasAccessPermission(String emailConnectorId, String username) {
    return true;
  }

  @Override
  public boolean hasEditPermission(String emailConnectorId, String username) {
    return emailConnectorService.canEdit(username);
  }

  @Override
  public long getAudienceId(String objectId) {
    return 0;
  }

  @Override
  public long getSpaceId(String objectId) {
    return 0;
  }
}
