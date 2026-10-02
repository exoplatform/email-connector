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

import java.util.Map;

/**
 * A frame pushed to a user's pages about sender logos (EXO-90909), in the shape social's
 * {@code $socialWebSocket.initCometd} dispatches: {@code wsEventName} names the document
 * event, {@code message} is read from its detail.
 *
 * @param wsEventName the document event the page receives
 * @param message what the event carries: a domain, or rows and their senders, never a
 *          logo URL
 */
public record SenderLogoWebSocketMessage(String wsEventName, Map<String, Object> message) {
}
