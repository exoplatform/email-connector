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
 * One ranked recipient suggestion, as the compose field's own type-ahead
 * answers it — the address the agent should put in {@code send_email}, the
 * name to confirm it with the user, and whether it is a platform colleague.
 * The avatar the UI shows is deliberately NOT mapped here: no photo URL ever
 * leaves the contact toolset.
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
@JsonInclude(value = Include.NON_EMPTY)
public class RecipientSuggestionModel {

  /** The normalized address, ready to be used as a recipient. */
  private String  address;

  @JsonProperty("display_name")
  private String  displayName;

  /** Whether the address belongs to a platform colleague. */
  @JsonProperty("platform_user")
  private boolean platformUser;

  /** The platform profile link, set only for platform colleagues. */
  @JsonProperty("profile_url")
  private String  profileUrl;
}
