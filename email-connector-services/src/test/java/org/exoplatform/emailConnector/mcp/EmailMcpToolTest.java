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
package org.exoplatform.emailConnector.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import org.exoplatform.commons.exception.ObjectNotFoundException;
import org.exoplatform.emailConnector.mcp.model.EmailModel;
import org.exoplatform.emailConnector.model.Email;
import org.exoplatform.emailConnector.model.EmailBox;
import org.exoplatform.emailConnector.model.EmailContent;
import org.exoplatform.emailConnector.service.EmailBoxService;

class EmailMcpToolTest {

  private static final String   USERNAME = "testuser1";

  private static final long     EMAIL_ID = 42L;

  private EmailBoxService        emailBoxService;

  private EmailMcpTool           emailMcpTool;

  @BeforeEach
  void setUp() {
    emailBoxService = Mockito.mock(EmailBoxService.class);
    emailMcpTool = new EmailMcpTool(emailBoxService) {
      @Override
      public String getCurrentUserName() {
        return USERNAME;
      }
    };
  }

  private Email buildEmail(long id) {
    Email email = new Email();
    email.setId(id);
    email.setUserId(USERNAME);
    email.setUserEmail("testuser1@example.com");
    email.setSubject("Hello");
    EmailContent content = new EmailContent();
    content.setBody("<p>Hello <b>world</b></p>");
    email.setContent(content);
    return email;
  }

  // --- get_email_by_id -----------------------------------------------------

  @Test
  void getEmailById() throws Exception {
    when(emailBoxService.getEmailById(eq(EMAIL_ID), eq(USERNAME))).thenReturn(buildEmail(EMAIL_ID));

    EmailModel model = emailMcpTool.getEmailById(EMAIL_ID);

    assertNotNull(model);
    assertEquals(EMAIL_ID, model.getId());
    assertEquals("Hello", model.getSubject());
    // Body HTML is stripped down to plain text
    assertEquals("Hello world", model.getContent().getBody());
  }

  @Test
  void getEmailByIdNotFoundFails() throws Exception {
    when(emailBoxService.getEmailById(eq(EMAIL_ID), eq(USERNAME))).thenReturn(null);
    assertThrows(ObjectNotFoundException.class, () -> emailMcpTool.getEmailById(EMAIL_ID));
  }

  // --- list_emails ---------------------------------------------------------

  @Test
  void listEmails() throws Exception {
    EmailBox emailBox = new EmailBox();
    emailBox.setEmails(List.of(buildEmail(1L), buildEmail(2L)));
    when(emailBoxService.getEmailBox(eq(USERNAME))).thenReturn(emailBox);

    List<EmailModel> emails = emailMcpTool.listEmails();

    assertNotNull(emails);
    assertEquals(2, emails.size());
    assertEquals("Hello world", emails.get(0).getContent().getBody());
    // list_emails does not expose the user email address
    assertEquals(null, emails.get(0).getUserEmail());
  }

}
