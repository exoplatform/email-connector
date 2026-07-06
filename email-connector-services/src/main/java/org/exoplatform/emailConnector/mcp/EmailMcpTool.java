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

import java.util.List;

import org.jsoup.Jsoup;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import org.exoplatform.commons.exception.ObjectNotFoundException;
import org.exoplatform.emailConnector.mcp.model.EmailModel;
import org.exoplatform.emailConnector.model.Email;
import org.exoplatform.emailConnector.model.EmailBox;
import org.exoplatform.emailConnector.model.EmailContent;
import org.exoplatform.emailConnector.service.EmailBoxService;

import io.meeds.mcp.server.plugin.McpToolPlugin;

/**
 * MCP tools exposing the Email Connector add-on to the AI agent (EVA). Every
 * method acts as the current user, so the caller only ever reads their own
 * email box. These tools only READ email content; they carry no coupling to the
 * AI service layer.
 */
@Service
@Profile("mcp-server")
public class EmailMcpTool implements McpToolPlugin {

  private final EmailBoxService emailBoxService;

  @Autowired
  public EmailMcpTool(EmailBoxService emailBoxService) {
    this.emailBoxService = emailBoxService;
  }

  public EmailModel getEmailById(long emailId) throws ObjectNotFoundException, IllegalAccessException {
    Email email = emailBoxService.getEmailById(emailId, getCurrentUserName());
    if (email == null) {
      throw new ObjectNotFoundException("Email with id %s not found");
    }
    EmailContent emailContent = email.getContent();
    emailContent.setBody(Jsoup.parse(emailContent.getBody()).text().trim());
    return new EmailModel(email.getId(),
                          email.getUserId(),
                          email.getUserEmail(),
                          email.getSubject(),
                          emailContent,
                          email.getReceivedDate(),
                          email.getSender(),
                          email.isRead(),
                          email.isRecent(),
                          email.getTo(),
                          email.getCc(),
                          email.getBcc());
  }

  public List<EmailModel> listEmails() throws ObjectNotFoundException, IllegalAccessException {
    EmailBox emailBox = emailBoxService.getEmailBox(getCurrentUserName());
    return emailBox.getEmails().stream().map(email -> {
      EmailContent emailContent = email.getContent();
      emailContent.setBody(Jsoup.parse(emailContent.getBody()).text().trim());
      return new EmailModel(email.getId(),
                            email.getUserId(),
                            null,
                            email.getSubject(),
                            emailContent,
                            email.getReceivedDate(),
                            email.getSender(),
                            email.isRead(),
                            email.isRecent(),
                            email.getTo(),
                            email.getCc(),
                            email.getBcc());
    }).toList();
  }
}
