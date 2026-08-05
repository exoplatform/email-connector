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
import com.fasterxml.jackson.annotation.JsonProperty;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Safe view of an email attachment exposed to the AI agent: metadata plus a
 * ready authenticated download URL. It deliberately carries NO attachment bytes;
 * the raw content is never streamed through the MCP tool - the user opens the
 * download URL in their own authenticated browser.
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
@JsonInclude(value = Include.NON_EMPTY)
public class EmailAttachmentModel {

  private String name;

  @JsonProperty("mime_type")
  private String mimeType;

  // MIME part path of the attachment within the message (e.g. "1.2").
  @JsonProperty("attachment_id")
  private String attachmentId;

  @JsonProperty("download_url")
  private String downloadUrl;
}
