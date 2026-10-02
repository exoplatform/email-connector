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
package org.exoplatform.emailConnector.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.Set;
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
 * EXO-90888 -- the reads behind a search narrowed to categories: the folder's (UID, id)
 * pairs as the DAO gives them ({@code EmailBoxDAOTest} runs that query on the real
 * engine), and the category filter over eXo's links, asked of social in slices, never
 * one message at a time.
 */
@ExtendWith(MockitoExtension.class)
class EmailBoxCategorySearchStorageTest {

  private static final String USER = "alice";

  @Mock
  private EmailBoxDAO         emailBoxDao;

  @Mock
  private CategoryLinkService categoryLinkService;

  @InjectMocks
  private EmailBoxStorage     emailBoxStorage;

  /**
   * A message is kept when one of its links names one of the categories asked, whatever
   * its other links; a message with no link, or only with other categories, is not.
   * One slice past the bound takes two lookups, each id asked once.
   */
  @Test
  void theMessagesLinkedToOneOfTheCategoriesAreKeptLookedUpInSlices() {
    List<Long> ids = LongStream.rangeClosed(1, EmailBoxStorage.CATEGORY_LOOKUP_SLICE + 1).boxed().toList();
    when(categoryLinkService.getLinkedIds(eq(EmailCategoryPlugin.OBJECT_TYPE),
                                          anyList())).thenReturn(Map.of("1",
                                                                        List.of(7L, 3L),
                                                                        "2",
                                                                        List.of(9L),
                                                                        String.valueOf(EmailBoxStorage.CATEGORY_LOOKUP_SLICE + 1),
                                                                        List.of(5L)));

    Set<Long> kept = emailBoxStorage.getEmailIdsInCategories(ids, Set.of(3L, 5L));

    assertEquals(Set.of(1L, (long) EmailBoxStorage.CATEGORY_LOOKUP_SLICE + 1), kept);
    @SuppressWarnings("unchecked")
    ArgumentCaptor<List<String>> asked = ArgumentCaptor.forClass(List.class);
    verify(categoryLinkService, times(2)).getLinkedIds(eq(EmailCategoryPlugin.OBJECT_TYPE), asked.capture());
    assertEquals(EmailBoxStorage.CATEGORY_LOOKUP_SLICE, asked.getAllValues().get(0).size());
    assertEquals(List.of(String.valueOf(EmailBoxStorage.CATEGORY_LOOKUP_SLICE + 1)), asked.getAllValues().get(1));
  }

  /**
   * No message, or no category, asks social nothing.
   */
  @Test
  void nothingToFilterAsksForNothing() {
    assertTrue(emailBoxStorage.getEmailIdsInCategories(List.of(), Set.of(3L)).isEmpty());
    assertTrue(emailBoxStorage.getEmailIdsInCategories(List.of(1L), Set.of()).isEmpty());
    verify(categoryLinkService, never()).getLinkedIds(eq(EmailCategoryPlugin.OBJECT_TYPE), anyList());
  }

  /**
   * The folder's pairs come back keyed by UID, each with its row's id.
   */
  @Test
  void theCachedIdsOfAFolderAreKeyedByUid() {
    when(emailBoxDao.findCachedIdsByUserIdAndFolder(USER, MailFolder.INBOX)).thenReturn(List.of(new Object[] { 40L, 101L },
                                                                                               new Object[] { 41L, 102L }));

    assertEquals(Map.of(40L, 101L, 41L, 102L), emailBoxStorage.getCachedFolderEmailIds(USER, MailFolder.INBOX));
  }
}
