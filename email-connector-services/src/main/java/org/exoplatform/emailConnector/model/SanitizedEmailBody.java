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

import java.util.List;

/**
 * A received HTML body made safe to display, with what the cleaning found on the way
 * (EXO-90841).
 *
 * @param html the body to serve to the reader
 * @param remoteContentBlocked whether resources to be fetched from the internet were
 *          taken out
 * @param links every link the body keeps, its visible text and its target
 */
public record SanitizedEmailBody(String html, boolean remoteContentBlocked, List<EmailLink> links) {
}
