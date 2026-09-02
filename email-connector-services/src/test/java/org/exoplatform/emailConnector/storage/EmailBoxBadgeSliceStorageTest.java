/**
 * This file is part of the Meeds project (https://meeds.io/).
 *
 * Copyright (C) 2020 - 2026 Meeds Association contact@meeds.io
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 3 of the License, or (at your option) any later version.
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin Street, Fifth Floor, Boston, MA 02110-1301, USA.
 */
package org.exoplatform.emailConnector.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.stream.LongStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import org.exoplatform.emailConnector.dao.EmailBoxDAO;
import org.exoplatform.emailConnector.model.MailFolder;
import org.exoplatform.emailConnector.plugin.EmailCategoryPlugin;

import io.meeds.social.category.service.CategoryLinkService;

/**
 * The slicing of the badge's category lookup, on its own: pure list arithmetic over
 * a mocked DAO, because what is under test is how many ids go into one call to
 * social, not what the query answers ({@code EmailBoxBadgeScopeStorageTest} runs
 * that one on the real engine).
 */
@ExtendWith(MockitoExtension.class)
class EmailBoxBadgeSliceStorageTest {

  private static final String USER = "hoarder";

  @Mock
  private EmailBoxDAO         emailBoxDao;

  @Mock
  private CategoryLinkService categoryLinkService;

  @InjectMocks
  private EmailBoxStorage     emailBoxStorage;

  /**
   * An inbox one row past a slice is asked for in two lookups, every id in exactly
   * one of them, and comes back as one map with a row per id — the caller never sees
   * the slicing.
   */
  @Test
  void anInboxLargerThanASliceIsLookedUpInSlicesAndAnsweredWhole() {
    List<Long> ids = LongStream.rangeClosed(1, EmailBoxStorage.CATEGORY_LOOKUP_SLICE + 1).boxed().toList();
    when(emailBoxDao.findUnreadIdsByUserIdAndFolder(USER, MailFolder.INBOX)).thenReturn(ids);
    when(categoryLinkService.getLinkedIds(eq(EmailCategoryPlugin.OBJECT_TYPE), anyList())).thenReturn(Map.of("1", List.of(3L)));

    Map<Long, List<Long>> categoryIdsByEmailId = emailBoxStorage.getUnreadInboxCategoryIds(USER);

    @SuppressWarnings("unchecked")
    ArgumentCaptor<List<String>> asked = ArgumentCaptor.forClass(List.class);
    verify(categoryLinkService, times(2)).getLinkedIds(eq(EmailCategoryPlugin.OBJECT_TYPE), asked.capture());
    assertEquals(EmailBoxStorage.CATEGORY_LOOKUP_SLICE, asked.getAllValues().get(0).size(), "a full first slice");
    assertEquals(List.of(String.valueOf(EmailBoxStorage.CATEGORY_LOOKUP_SLICE + 1)),
                 asked.getAllValues().get(1),
                 "and the one id left over in the second");
    assertEquals(ids.size(), categoryIdsByEmailId.size(), "every unread row is in the answer, whichever slice carried it");
    assertEquals(List.of(3L), categoryIdsByEmailId.get(1L));
    assertTrue(categoryIdsByEmailId.get(2L).isEmpty(), "an id social did not answer for is uncategorized, not missing");
  }

  /**
   * An empty inbox asks social nothing: there is no id to look up, and a lookup with
   * an empty list is a statement nobody should have to make sense of.
   */
  @Test
  void anEmptyInboxAsksForNothing() {
    when(emailBoxDao.findUnreadIdsByUserIdAndFolder(USER, MailFolder.INBOX)).thenReturn(List.of());

    assertTrue(emailBoxStorage.getUnreadInboxCategoryIds(USER).isEmpty());
    verify(categoryLinkService, times(0)).getLinkedIds(eq(EmailCategoryPlugin.OBJECT_TYPE), anyList());
  }
}
