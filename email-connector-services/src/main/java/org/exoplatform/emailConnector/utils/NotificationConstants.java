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

  /** One of the user's own mail filters matched new mail (EXO-90654). */
  public static final String EMAIL_FILTER_NOTIFICATION_PLUGIN = "EmailFilterNotificationPlugin";

  /**
   * The user's mail forward changed, or one eXo did not set was found (EXO-90656): set,
   * changed, removed, a rule that forwards saved or removed. Its own id, so it has its own
   * line in the notification settings.
   */
  public static final String EMAIL_FORWARDING_NOTIFICATION_PLUGIN = "EmailForwardingNotificationPlugin";

  /** What happened to the forward: an {@code EmailForwardingNotificationPlugin.Change} name. */
  public static final String FORWARDING_CHANGE              = "FORWARDING_CHANGE";

  /** Where mail is, or was, forwarded. */
  public static final String FORWARDING_DESTINATION         = "FORWARDING_DESTINATION";

  /** The rule, or the other client's script, the change is about; blank when none. */
  public static final String FORWARDING_SOURCE              = "FORWARDING_SOURCE";

  /**
   * Suggestions of the mail filters' assistant wait for the receiver (EXO-90668): one
   * notification per user, "N suggestions waiting", updated in place while unread and
   * replaced once read, never one per suggestion.
   */
  public static final String EMAIL_FILTER_SUGGESTIONS_NOTIFICATION_PLUGIN = "EmailFilterSuggestionsNotificationPlugin";

  /** How many suggestions wait, as the digest carries it. */
  public static final String SUGGESTION_COUNT               = "SUGGESTION_COUNT";

  /** The mail filter's name, as its notification carries it. */
  public static final String FILTER_NAME                    = "FILTER_NAME";

  /** How many mails the filter matched in one pass. */
  public static final String FILTER_COUNT                   = "FILTER_COUNT";

  /** The UID in the inbox of the mail a filter notification opens, when it is still there. */
  public static final String MAIL_REMOTE_ID                 = "MAIL_REMOTE_ID";

  /** The built-in folder a filter filed the notified mail into, when it left the inbox. */
  public static final String MAIL_FOLDER                    = "MAIL_FOLDER";

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

  /**
   * New mail arrived in a mailbox shared with the receiver, who asked to be told
   * (EXO-90553). Its own id, so it has its own line in the notification settings.
   */
  public static final String DELEGATED_NEW_EMAILS_NOTIFICATION_PLUGIN = "DelegatedNewEmailsNotificationPlugin";

  /** The other party's eXo username: the owner on an invitation, the actor on an answer. */
  public static final String DELEGATION_ACTOR               = "DELEGATION_ACTOR";

  /** The preset the share carries, a {@code DelegationPreset} name. */
  public static final String DELEGATION_PRESET              = "DELEGATION_PRESET";

  /** The transition being told about: an {@code EmailDelegationEvent.Type} name. */
  public static final String DELEGATION_RESPONSE            = "DELEGATION_RESPONSE";

  /** The delegation row's id, so the interface can open the share it is about. */
  public static final String DELEGATION_ID                  = "DELEGATION_ID";

  /**
   * Where the share an invitation is about now stands, written onto the stored web
   * notification once it is no longer waiting (EXO-90830): {@code ACCEPTED},
   * {@code DECLINED}, {@code LEFT} or {@code REVOKED}. Absent while it waits, which is
   * what makes the notification offer Accept and Refuse.
   */
  public static final String DELEGATION_STATUS              = "DELEGATION_STATUS";
}
