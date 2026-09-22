/**
 * Copyright (C) 2025 eXo Platform SAS
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
package org.exoplatform.emailConnector.utils;

public class NotificationConstants {

  public static final String TITLE                          = "TITLE";

  public static final String CONTENT                        = "CONTENT";

  public static final String LINK                           = "LINK";

  public static final String NEW_EMAILS_NOTIFICATION_PLUGIN = "NewEmailsNotificationPlugin";

  /** A scheduled mail was not sent, or could not be confirmed sent (EXO-90434). */
  public static final String SCHEDULED_EMAIL_FAILED_NOTIFICATION_PLUGIN = "ScheduledEmailFailedNotificationPlugin";

  /** The scheduled mail's subject, as the notification carries it. */
  public static final String SUBJECT                        = "SUBJECT";

  /** Why it was not sent: a {@code ScheduledSendError} name, never the server's text. */
  public static final String REASON                         = "REASON";

  /** Somebody shared their mailbox with the receiver, and waits for an answer (EXO-90503). */
  public static final String EMAIL_DELEGATION_INVITATION_NOTIFICATION_PLUGIN = "EmailDelegationInvitationPlugin";

  /**
   * The answer to a share came back, or the access was taken away (EXO-90503). One
   * plugin for both directions because it is one kind of news -- "where that share now
   * stands" -- told to whichever party did not act; {@link #DELEGATION_RESPONSE} says
   * which transition it was.
   */
  public static final String EMAIL_DELEGATION_RESPONSE_NOTIFICATION_PLUGIN = "EmailDelegationResponseNotificationPlugin";

  /** The other party's eXo username: the owner on an invitation, the actor on an answer. */
  public static final String DELEGATION_ACTOR               = "DELEGATION_ACTOR";

  /** The preset the share carries, a {@code DelegationPreset} name. */
  public static final String DELEGATION_PRESET              = "DELEGATION_PRESET";

  /** The transition being told about: an {@code EmailDelegationEvent.Type} name. */
  public static final String DELEGATION_RESPONSE            = "DELEGATION_RESPONSE";

  /** The delegation row's id, so the interface can open the share it is about. */
  public static final String DELEGATION_ID                  = "DELEGATION_ID";
}
