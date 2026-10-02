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
package org.exoplatform.emailConnector.model;

/**
 * What a reader is offered for a sender's brand logo (EXO-90909): the logo's URL when it
 * is cached, else whether it is being looked up for this reader, who is then told over
 * the WebSocket when it is found.
 *
 * @param url the logo's URL, bound to the reader, or null
 * @param pending whether the logo is being resolved and the reader will be told
 */
public record SenderLogoOffer(String url, boolean pending) {

  /** No logo, and none coming. */
  public static final SenderLogoOffer NONE = new SenderLogoOffer(null, false);
}
