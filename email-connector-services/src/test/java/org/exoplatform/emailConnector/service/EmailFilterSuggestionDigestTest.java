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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import org.exoplatform.commons.api.notification.model.NotificationInfo;
import org.exoplatform.commons.api.notification.model.PluginKey;
import org.exoplatform.commons.api.notification.model.WebNotificationFilter;
import org.exoplatform.commons.api.notification.plugin.NotificationPluginUtils;
import org.exoplatform.commons.api.notification.service.WebNotificationService;
import org.exoplatform.commons.utils.CommonsUtils;
import org.exoplatform.emailConnector.utils.NotificationConstants;
import org.exoplatform.services.resources.ResourceBundleService;

/**
 * The digest of the waiting suggestions stays one per user (EXO-90668): a new batch
 * updates an unread digest in place rather than stacking a second one, replaces a read
 * one with a fresh alert, a decision rewrites the count without alerting, and a digest
 * with nothing left to wait for goes.
 */
@ExtendWith(MockitoExtension.class)
class EmailFilterSuggestionDigestTest {

  private static final String                   USERNAME = "alice";

  @Mock
  private WebNotificationService                webNotificationService;

  @Spy
  @InjectMocks
  private EmailFilterSuggestionDigest           digest;

  private final List<NotificationInfo>          held     = new ArrayList<>();

  private MockedStatic<CommonsUtils>            commonsUtils;

  private MockedStatic<NotificationPluginUtils> pluginUtils;

  /**
   * The user's digests in a list behind the mock, the bundle's sentences, and no digest
   * actually sent.
   */
  @BeforeEach
  void setUp() {
    ResourceBundleService bundles = mock(ResourceBundleService.class);
    lenient()
                       .when(bundles.getSharedString(anyString(), any(Locale.class)))
                       .thenAnswer(invocation -> switch ((String) invocation.getArgument(0)) {
                       case "emailFilterSuggestions.notification.content.one" -> "1 suggestion waiting";
                       case "emailFilterSuggestions.notification.content.many" -> "{0} suggestions waiting";
                       default -> "Mail assistant";
                       });
    commonsUtils = mockStatic(CommonsUtils.class);
    commonsUtils.when(() -> CommonsUtils.getService(ResourceBundleService.class)).thenReturn(bundles);
    pluginUtils = mockStatic(NotificationPluginUtils.class);
    pluginUtils.when(() -> NotificationPluginUtils.getLanguage(anyString())).thenReturn("en");
    lenient()
                       .when(webNotificationService.getNotificationInfos(any(WebNotificationFilter.class), anyInt(), anyInt()))
                       .thenAnswer(invocation -> {
                         WebNotificationFilter filter = invocation.getArgument(0);
                         assertEquals(USERNAME, filter.getUserId());
                         assertEquals(List.of(PluginKey.key(NotificationConstants.EMAIL_FILTER_SUGGESTIONS_NOTIFICATION_PLUGIN)),
                                      filter.getPluginKeys());
                         return new ArrayList<>(held);
                       });
    lenient().doNothing().when(digest).send(anyString(), anyLong());
  }

  /**
   * Takes the statics away again.
   */
  @AfterEach
  void tearDown() {
    commonsUtils.close();
    pluginUtils.close();
  }

  /**
   * No digest yet: one is sent, with the count.
   */
  @Test
  void aFirstBatchSendsOneDigest() {
    digest.publish(USERNAME, 3);

    verify(digest).send(USERNAME, 3);
    verify(webNotificationService, never()).update(any(), eq(true));
  }

  /**
   * An unread digest is updated in place, moved to the top, with the new count: no
   * second notification, no second alert.
   */
  @Test
  void anUnreadDigestIsUpdatedInPlaceRatherThanStacked() {
    NotificationInfo unread = digest("11", false, 2);
    held.add(unread);

    digest.publish(USERNAME, 5);

    ArgumentCaptor<NotificationInfo> updated = ArgumentCaptor.forClass(NotificationInfo.class);
    verify(webNotificationService).update(updated.capture(), eq(true));
    assertEquals("11", updated.getValue().getId());
    assertEquals("5", updated.getValue().getValueOwnerParameter(NotificationConstants.SUGGESTION_COUNT));
    assertEquals("5 suggestions waiting", updated.getValue().getValueOwnerParameter(NotificationConstants.CONTENT));
    assertFalse(updated.getValue().isRead());
    verify(digest, never()).send(anyString(), anyLong());
    verify(webNotificationService, never()).remove(anyString());
  }

  /**
   * A digest the user read is replaced: removed, and a new one sent -- one alert per batch
   * the user has not seen.
   */
  @Test
  void aReadDigestIsReplaced() {
    held.add(digest("11", true, 2));

    digest.publish(USERNAME, 1);

    verify(webNotificationService).remove("11");
    verify(digest).send(USERNAME, 1);
  }

  /**
   * A decision rewrites the count of the digest without sending anything; with nothing
   * left waiting, the digest goes.
   */
  @Test
  void aDecisionRewritesTheCountAndNothingLeftRemovesTheDigest() {
    held.add(digest("11", false, 3));

    digest.refresh(USERNAME, 2);

    @SuppressWarnings("unchecked")
    ArgumentCaptor<Map<String, String>> parameters = ArgumentCaptor.forClass(Map.class);
    verify(webNotificationService).updateNotificationParameters(eq("11"), parameters.capture());
    assertEquals("2", parameters.getValue().get(NotificationConstants.SUGGESTION_COUNT));
    assertEquals("2 suggestions waiting", parameters.getValue().get(NotificationConstants.CONTENT));
    verify(digest, never()).send(anyString(), anyLong());

    digest.refresh(USERNAME, 0);

    verify(webNotificationService).remove("11");
    verify(digest, never()).send(anyString(), anyLong());
  }

  /**
   * A digest held by the user.
   *
   * @param id its id
   * @param read whether it was read
   * @param count the count it says
   * @return the digest
   */
  private static NotificationInfo digest(String id, boolean read, int count) {
    NotificationInfo info = NotificationInfo.instance()
                                            .key(PluginKey.key(NotificationConstants.EMAIL_FILTER_SUGGESTIONS_NOTIFICATION_PLUGIN))
                                            .to(USERNAME)
                                            .with(NotificationConstants.SUGGESTION_COUNT, String.valueOf(count));
    info.setId(id);
    info.setRead(read);
    return info;
  }
}
