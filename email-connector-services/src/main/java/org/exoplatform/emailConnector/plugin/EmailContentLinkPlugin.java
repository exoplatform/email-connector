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
import java.util.Locale;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import org.exoplatform.emailConnector.utils.EmailConnectorUtils;
import org.exoplatform.services.security.Identity;

import io.meeds.social.cms.model.ContentLinkExtension;
import io.meeds.social.cms.model.ContentLinkSearchResult;
import io.meeds.social.cms.plugin.ContentLinkPlugin;
import io.meeds.social.cms.service.ContentLinkPluginService;
import jakarta.annotation.PostConstruct;
import lombok.SneakyThrows;

@Component
public class EmailContentLinkPlugin implements ContentLinkPlugin {

  public static final String                OBJECT_TYPE = EmailConnectorUtils.EMAIL_FEATURE;

  private static final String               TITLE_KEY   = "contentLink.email";

  private static final ContentLinkExtension EXTENSION   = new ContentLinkExtension(OBJECT_TYPE, TITLE_KEY, null, null);

  @Autowired
  private ContentLinkPluginService          contentLinkPluginService;

  @Autowired
  @PostConstruct
  public void init() {
    contentLinkPluginService.addPlugin(this);
  }

  @Override
  public ContentLinkExtension getExtension() {
    return EXTENSION;
  }

  @Override
  @SneakyThrows
  public List<ContentLinkSearchResult> search(String keyword, Identity identity, Locale locale, int offset, int limit) {
    return null;
  }

  @Override
  @SneakyThrows
  public String getContentTitle(String objectId, Locale locale) {
    return null;
  }
}
