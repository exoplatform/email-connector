/*
 * Copyright (C) 2026 eXo Platform SAS.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */
package org.exoplatform.emailConnector.model;

/**
 * What one viewer's own contacts answer for one address, as the
 * {@code EmailSenderProfileService} keeps it (EXO-90908): contacts are per user, so
 * the answer is the viewer's alone.
 *
 * @param photoUrl the URL of the picture of the viewer's contact at that address, or
 *          null when none of their contacts there has one
 * @param readAt when it was read, in ms
 */
public record ViewerContactPhoto(String photoUrl, long readAt) {

  /**
   * Whether the answer is recent enough to be served.
   *
   * @param now the time, in ms
   * @param ttlMs how long an answer is served, in ms
   * @return true while younger than the given time
   */
  public boolean isFresh(long now, long ttlMs) {
    return now - readAt < ttlMs;
  }
}
