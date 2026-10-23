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
 * What the add-on holding the user's calendar did with an invitation (EXO-90848,
 * {@code InvitationCalendarPlugin}).
 *
 * @param eventId the calendar event's technical id, 0 when the add-on names none
 * @param link where the event is read in the platform, built from the platform's own
 *          domain; null when removed, or when the add-on has no page for it
 * @param removed true when the event was removed from the calendar -- its organiser
 *          cancelled it -- rather than put there
 * @param alreadyHeld true when nothing was written because the event is one of the
 *          platform's own, in the calendar already
 */
public record LandedInvitation(long eventId, String link, boolean removed, boolean alreadyHeld) {
}
