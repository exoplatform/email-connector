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
package org.exoplatform.emailConnector.rest.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * The body of an owner's "Writing mail in your name" choice for one grantee (EXO-90582):
 * {@code NONE}, {@code ON_BEHALF} or {@code AS}. A string, not the enum, so an unknown
 * value is answered with this add-on's own code ({@code emailConnector.sendMode.invalid})
 * rather than the JSON parser's.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class DelegationSendModeRequest {

  /** NONE, ON_BEHALF or AS; anything else is answered 400. */
  private String sendMode;
}
