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
package org.exoplatform.emailConnector.utils;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import javax.mail.BodyPart;
import javax.mail.Message;
import javax.mail.internet.MimeMultipart;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

import org.exoplatform.emailConnector.model.EmailContent;
import org.exoplatform.emailConnector.model.UserEmailSetting;

import lombok.SneakyThrows;

@ExtendWith(MockitoExtension.class)
public class EmailConnectorUtilsTest {

  @Test
  @SneakyThrows
  public void getMessageContent() {
    Message message = mock(Message.class);
    when(message.isMimeType("text/*")).thenReturn(true);
    when(message.getContent()).thenReturn("test message");
    EmailContent emailContent = EmailConnectorUtils.getMessageContent(message, false);
    assertEquals("test message", emailContent.getBody());
    assertFalse(emailContent.isHtml());
    when(message.getContent()).thenReturn("test message test message test message test message test message");
    emailContent = EmailConnectorUtils.getMessageContent(message, true);
    assertEquals("test message test message test message test messag...", emailContent.getBody());
    when(message.isMimeType("text/*")).thenReturn(false);
    MimeMultipart mimeMultipart = mock(MimeMultipart.class);
    when(message.getContent()).thenReturn(mimeMultipart);
    when(mimeMultipart.getCount()).thenReturn(1);
    BodyPart bodyPart =  mock(BodyPart.class);
    when(mimeMultipart.getBodyPart(0)).thenReturn(bodyPart);
    when(bodyPart.isMimeType("text/html")).thenReturn(true);
    when(bodyPart.getContent()).thenReturn("<span>test message</span>");
    emailContent = EmailConnectorUtils.getMessageContent(message, false);
    assertEquals("<span>test message</span>", emailContent.getBody());
    when(bodyPart.isMimeType("text/html")).thenReturn(false);
    when(bodyPart.isMimeType("text/plain")).thenReturn(true);
    when(bodyPart.getContent()).thenReturn("test message");
    emailContent = EmailConnectorUtils.getMessageContent(message, false);
    assertEquals("test message", emailContent.getBody());
  }

  @Test
  public void getEmailBoxUserSyncPeriod() throws Exception {
    UserEmailSetting userEmailSetting = mock(UserEmailSetting.class);
    when(userEmailSetting.getEmailBoxUserSyncPeriod()).thenReturn(null);
    int emailBoxUserSyncPeriod = EmailConnectorUtils.getEmailBoxUserSyncPeriod(userEmailSetting);
    assertEquals(10, emailBoxUserSyncPeriod);
    when(userEmailSetting.getEmailBoxUserSyncPeriod()).thenReturn(20);
    emailBoxUserSyncPeriod = EmailConnectorUtils.getEmailBoxUserSyncPeriod(userEmailSetting);
    assertEquals(20, emailBoxUserSyncPeriod);
  }

}
