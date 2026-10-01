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
package org.exoplatform.emailConnector.notification.mail;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;

import org.exoplatform.commons.api.notification.NotificationContext;
import org.exoplatform.commons.api.notification.model.NotificationInfo;
import org.exoplatform.commons.api.notification.model.PluginKey;
import org.exoplatform.commons.api.notification.plugin.NotificationPluginUtils;
import org.exoplatform.commons.api.notification.service.template.TemplateContext;
import org.exoplatform.commons.notification.template.TemplateUtils;
import org.exoplatform.commons.utils.CommonsUtils;
import org.exoplatform.commons.utils.TimeConvertUtils;
import org.exoplatform.emailConnector.utils.NotificationConstants;
import org.exoplatform.social.notification.plugin.SocialNotificationUtils;

/**
 * A mail's button leads somewhere (EXO-90830): the plugins store a path, which the web
 * and push channels resolve on the platform's own origin, but a mail is read in a mail
 * client, where a relative link leads nowhere.
 */
public class MailTemplateBuilderTest {

  private MockedStatic<CommonsUtils> commonsUtils;

  /**
   * Gives the platform a domain, with the trailing slash an administrator may type.
   */
  @BeforeEach
  void configureTheDomain() {
    commonsUtils = mockStatic(CommonsUtils.class);
    commonsUtils.when(CommonsUtils::getCurrentDomain).thenReturn("https://exo.example.com/");
  }

  /**
   * Takes the static away again.
   */
  @AfterEach
  void forgetTheStatic() {
    commonsUtils.close();
  }

  /**
   * A path gets the platform's domain in front of it, once, with no doubled slash.
   */
  @Test
  void aPathIsMadeAbsolute() {
    assertEquals("https://exo.example.com/portal/dw?openEmailBox=true&sharedWithMe=true",
                 MailTemplateBuilder.absoluteLink("/portal/dw?openEmailBox=true&sharedWithMe=true"));
  }

  /**
   * A link that names its origin -- absolute, or protocol-relative -- is left as it is,
   * and so is no link at all.
   */
  @Test
  void aLinkThatIsNotAPathIsLeftAsItIs() {
    assertEquals("https://other.example.com/x", MailTemplateBuilder.absoluteLink("https://other.example.com/x"));
    assertEquals("//cdn.example.com/x", MailTemplateBuilder.absoluteLink("//cdn.example.com/x"));
    assertNull(MailTemplateBuilder.absoluteLink(null));
  }

  /**
   * The template's button gets the absolute link, beside the subject and the sentence
   * the plugin wrote.
   */
  @Test
  void theMailsButtonGetsTheAbsoluteLink() {
    NotificationInfo notification = NotificationInfo.instance()
                                                    .with(NotificationConstants.TITLE, "A mailbox was shared with you")
                                                    .with(NotificationConstants.CONTENT, "Anne gave you access")
                                                    .with(NotificationConstants.LINK, "/portal/dw?openEmailBox=true&sharedWithMe=true");
    TemplateContext context = new TemplateContext();

    MailTemplateBuilder.putContent(context, notification);

    assertEquals("A mailbox was shared with you", context.get("SUBJECT"));
    assertEquals("Anne gave you access", context.get("EMAILS_CONTENT"));
    assertEquals("https://exo.example.com/portal/dw?openEmailBox=true&sharedWithMe=true", context.get("EMAILS_LINK"));
  }

  /**
   * The mail the channel renders is built with that context: the template is handed the
   * absolute link, whatever the plugin stored.
   */
  @Test
  void theRenderedMailIsGivenTheAbsoluteLink() {
    NotificationInfo notification = NotificationInfo.instance()
                                                    .to("ben")
                                                    .key(PluginKey.key("EmailDelegationInvitationPlugin"))
                                                    .with(NotificationConstants.LINK, "/portal/dw?openEmailBox=true&sharedWithMe=true");
    NotificationContext ctx = mock(NotificationContext.class);
    when(ctx.getNotificationInfo()).thenReturn(notification);
    try (MockedStatic<NotificationPluginUtils> pluginUtils = mockStatic(NotificationPluginUtils.class);
        MockedStatic<SocialNotificationUtils> socialUtils = mockStatic(SocialNotificationUtils.class);
        MockedStatic<TimeConvertUtils> timeUtils = mockStatic(TimeConvertUtils.class);
        MockedStatic<TemplateUtils> templateUtils = mockStatic(TemplateUtils.class)) {
      pluginUtils.when(() -> NotificationPluginUtils.getLanguage("ben")).thenReturn("en");
      timeUtils.when(() -> TimeConvertUtils.convertXTimeAgoByTimeServer(any(), anyString(), any(), anyInt())).thenReturn("now");
      templateUtils.when(() -> TemplateUtils.processSubject(any())).thenReturn("subject");
      templateUtils.when(() -> TemplateUtils.processGroovy(any())).thenReturn("body");

      new MailTemplateBuilder(mock(MailTemplateProvider.class)).makeMessage(ctx);

      ArgumentCaptor<TemplateContext> rendered = ArgumentCaptor.forClass(TemplateContext.class);
      templateUtils.verify(() -> TemplateUtils.processGroovy(rendered.capture()));
      assertEquals("https://exo.example.com/portal/dw?openEmailBox=true&sharedWithMe=true", rendered.getValue().get("EMAILS_LINK"));
    }
  }
}
