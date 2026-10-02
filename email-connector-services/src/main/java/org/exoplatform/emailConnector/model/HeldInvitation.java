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
 * The copy of an invitation the user's calendar already holds (EXO-90873,
 * {@code InvitationCalendarPlugin#held}): the user accepted or declined it elsewhere --
 * on their phone, in another client, in their mail server's webmail -- or added it from
 * here earlier.
 *
 * @param eventId the calendar event's technical id, 0 when the add-on names none
 * @param link where the event is read in the platform, built from the platform's own
 *          domain; null when the add-on has no page for it
 * @param answer the user's answer as the held copy says it; null when it says none
 *          (NEEDS-ACTION) or one the reader has no word for
 * @param sequence the SEQUENCE of the held copy, 0 when it carries none: a mail with a
 *          higher one is the organiser's newer revision
 */
public record HeldInvitation(long eventId, String link, InvitationAnswer answer, int sequence) {
}
