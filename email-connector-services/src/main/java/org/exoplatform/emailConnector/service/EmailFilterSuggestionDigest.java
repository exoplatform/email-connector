/**
 * Copyright (C) 2026 eXo Platform SAS.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License
 * as published by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program. If not, see <gnu.org/licenses>.
 */
package org.exoplatform.emailConnector.service;

import java.util.Calendar;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import org.exoplatform.commons.api.notification.NotificationContext;
import org.exoplatform.commons.api.notification.model.NotificationInfo;
import org.exoplatform.commons.api.notification.model.PluginKey;
import org.exoplatform.commons.api.notification.model.WebNotificationFilter;
import org.exoplatform.commons.api.notification.service.WebNotificationService;
import org.exoplatform.commons.notification.impl.NotificationContextImpl;
import org.exoplatform.emailConnector.notification.plugin.EmailFilterSuggestionsNotificationPlugin;
import org.exoplatform.emailConnector.utils.NotificationConstants;
import org.exoplatform.services.log.ExoLogger;
import org.exoplatform.services.log.Log;

/**
 * The one notification that tells a user how many suggestions of their mail filters'
 * assistant wait (EXO-90668), kept one per user:
 * <ul>
 * <li>a run that proposed something <b>publishes</b> it: an unread digest is updated in
 * place with the new count and moved to the top, without a second alert; when there is
 * none unread, the read ones are removed and a new one is sent through the platform's
 * channels -- one alert per batch the user has not looked at yet. The platform builds a
 * sent notification later, on its own executor, so two runs close together would both
 * find nothing and send two: the plugin checks again when it builds the digest
 * ({@link #updateUnread}), and a digest that finds an unread one updates it instead of
 * being stored;</li>
 * <li>a decision <b>refreshes</b> it: the count of every digest the user holds is
 * rewritten, nothing is sent, and the digests are removed once nothing waits.</li>
 * </ul>
 * Never throws: a notification that cannot be written leaves the suggestions as they
 * are, their cards in the mail and the list's marker.
 */
@Component
public class EmailFilterSuggestionDigest {

  /** How many of a user's digests are read at most: there is one, save for a race. */
  static final int               MAX_READ = 10;

  private static final Log       LOG      = ExoLogger.getLogger(EmailFilterSuggestionDigest.class);

  private static final PluginKey KEY      = PluginKey.key(NotificationConstants.EMAIL_FILTER_SUGGESTIONS_NOTIFICATION_PLUGIN);

  @Autowired
  private WebNotificationService webNotificationService;

  /**
   * Tells the user that suggestions wait, after a run proposed some.
   *
   * @param username the user
   * @param waiting how many of their suggestions wait now
   */
  public void publish(String username, long waiting) {
    try {
      if (waiting <= 0) {
        removeAll(digests(webNotificationService, username));
        return;
      }
      if (!updateUnread(webNotificationService, username, waiting)) {
        removeAll(digests(webNotificationService, username));
        send(username, waiting);
      }
    } catch (RuntimeException | LinkageError e) {
      LOG.warn("The digest of the waiting suggestions of user {} could not be written", username, e);
    }
  }

  /**
   * Brings the user's digests to the count that waits now, after a decision: rewritten
   * in place, never sent again, removed when nothing waits.
   *
   * @param username the user
   * @param waiting how many of their suggestions wait now
   */
  public void refresh(String username, long waiting) {
    try {
      List<NotificationInfo> digests = digests(webNotificationService, username);
      if (waiting <= 0) {
        removeAll(digests);
        return;
      }
      Map<String, String> parameters = new HashMap<>();
      parameters.put(NotificationConstants.SUGGESTION_COUNT, String.valueOf(waiting));
      parameters.put(NotificationConstants.CONTENT, EmailFilterSuggestionsNotificationPlugin.content(username, (int) waiting));
      for (NotificationInfo digest : digests) {
        Map<String, String> merged = new HashMap<>(digest.getOwnerParameter() == null ? Map.of() : digest.getOwnerParameter());
        merged.putAll(parameters);
        webNotificationService.updateNotificationParameters(digest.getId(), merged);
      }
    } catch (RuntimeException | LinkageError e) {
      LOG.warn("The digest of the waiting suggestions of user {} could not be brought up to date", username, e);
    }
  }

  /**
   * Updates the user's unread digest in place with a new count, moved to the top without
   * a second alert, and removes their other digests. The publisher's first choice, and
   * the plugin's check when the platform builds a digest it was asked to send.
   *
   * @param webNotificationService the platform's web notifications
   * @param username the user
   * @param waiting how many of their suggestions wait now, more than 0
   * @return true when an unread digest was updated; false when the user holds none
   */
  public static boolean updateUnread(WebNotificationService webNotificationService, String username, long waiting) {
    List<NotificationInfo> digests = digests(webNotificationService, username);
    NotificationInfo unread = digests.stream().filter(digest -> !digest.isRead()).findFirst().orElse(null);
    if (unread == null) {
      return false;
    }
    unread.with(NotificationConstants.SUGGESTION_COUNT, String.valueOf(waiting))
          .with(NotificationConstants.CONTENT, EmailFilterSuggestionsNotificationPlugin.content(username, (int) waiting));
    unread.setUpdate(true);
    unread.setRead(false);
    unread.setResetOnBadge(false);
    unread.setLastModifiedDate(Calendar.getInstance());
    webNotificationService.update(unread, true);
    digests.stream().filter(digest -> digest != unread).forEach(digest -> webNotificationService.remove(digest.getId()));
    return true;
  }

  /**
   * Sends a new digest through the platform's channels.
   *
   * @param username the user
   * @param waiting how many wait
   */
  void send(String username, long waiting) {
    NotificationContext ctx = NotificationContextImpl.cloneInstance()
                                                     .append(EmailFilterSuggestionsNotificationPlugin.RECEIVER, username)
                                                     .append(EmailFilterSuggestionsNotificationPlugin.COUNT, String.valueOf(waiting));
    ctx.getNotificationExecutor().with(ctx.makeCommand(KEY)).execute(ctx);
  }

  /**
   * The user's digests, read or not.
   *
   * @param webNotificationService the platform's web notifications
   * @param username the user
   * @return the digests
   */
  private static List<NotificationInfo> digests(WebNotificationService webNotificationService, String username) {
    List<NotificationInfo> digests = webNotificationService.getNotificationInfos(new WebNotificationFilter(username, List.of(KEY), false),
                                                                                 0,
                                                                                 MAX_READ);
    return digests == null ? List.of() : digests;
  }

  /**
   * Removes some digests.
   *
   * @param digests the digests
   */
  private void removeAll(List<NotificationInfo> digests) {
    digests.forEach(digest -> webNotificationService.remove(digest.getId()));
  }
}
