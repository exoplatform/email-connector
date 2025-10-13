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
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;

import org.exoplatform.emailConnector.service.EmailConnectorService;

import io.meeds.social.translation.service.TranslationService;

@SpringBootTest(classes = { EmailConnectorTranslationPlugin.class })
@ExtendWith(MockitoExtension.class)
public class EmailConnectorTranslationPluginTest {

  private static final String             TEST_USER          = "testuser";

  private static final String             EMAIL_CONNECTOR_ID = "8";

  @MockBean
  private TranslationService              translationService;

  @MockBean
  private EmailConnectorService           emailConnectorService;

  @Autowired
  private EmailConnectorTranslationPlugin emailConnectorTranslationPlugin;

  @Test
  void init() {
    emailConnectorTranslationPlugin.init();
    verify(translationService).addPlugin(emailConnectorTranslationPlugin);
  }

  @Test
  void getObjectType() {
    assertEquals("emailConnector", emailConnectorTranslationPlugin.getObjectType());
  }

  @Test
  void hasEditPermission() {
    assertFalse(emailConnectorTranslationPlugin.hasEditPermission(EMAIL_CONNECTOR_ID, TEST_USER));
    when(emailConnectorService.canEdit(TEST_USER)).thenReturn(true);
    assertTrue(emailConnectorTranslationPlugin.hasEditPermission(EMAIL_CONNECTOR_ID, TEST_USER));
  }

  @Test
  void hasAccessPermission() {
    assertTrue(emailConnectorTranslationPlugin.hasAccessPermission(EMAIL_CONNECTOR_ID, TEST_USER));
  }

  @Test
  void getAudienceId() {
    assertEquals(0l, emailConnectorTranslationPlugin.getAudienceId(EMAIL_CONNECTOR_ID));
  }

  @Test
  void getSpaceId() {
    assertEquals(0l, emailConnectorTranslationPlugin.getSpaceId(EMAIL_CONNECTOR_ID));
  }
}
