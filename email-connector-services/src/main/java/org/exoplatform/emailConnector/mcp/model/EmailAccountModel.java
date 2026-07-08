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
package org.exoplatform.emailConnector.mcp.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Safe view of the current user's connected email account exposed to the AI
 * agent. It deliberately carries ONLY non-sensitive fields; the stored IMAP/SMTP
 * password (UserEmailSetting.emailPassword) is NEVER mapped here.
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
@JsonInclude(value = Include.NON_EMPTY)
public class EmailAccountModel {

  private String  emailAddress;

  private String  connectorName;

  private String  webmailUrl;

  private String  syncStatus;

  private boolean connected;
}
