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
package org.exoplatform.emailConnector.upgrade;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import org.exoplatform.commons.api.settings.SettingService;
import org.exoplatform.commons.api.settings.SettingValue;
import org.exoplatform.commons.api.settings.data.Context;
import org.exoplatform.commons.api.settings.data.Scope;
import org.exoplatform.commons.exception.ObjectNotFoundException;
import org.exoplatform.portal.config.UserACL;
import org.exoplatform.social.metadata.MetadataService;

import io.meeds.social.category.service.CategoryService;

import lombok.SneakyThrows;

@ExtendWith(MockitoExtension.class)
public class ToReviewCategoryCleanupTest {

  private static final String       TO_REVIEW_NAME_ID = "emailToReviewCategory";

  private static final String       SUPER_USER        = "root";

  private static final long         CATEGORY_ID       = 123L;

  @Mock
  private SettingService            settingService;

  @Mock
  private CategoryService           categoryService;

  @Mock
  private MetadataService           metadataService;

  @Mock
  private UserACL                   userAcl;

  @InjectMocks
  private ToReviewCategoryCleanup   cleanup;

  /**
   * Fresh install or already-cleaned installation: no persisted mapping means
   * there is nothing to delete and, crucially, nothing to remove from settings.
   */
  @Test
  @SneakyThrows
  public void deleteToReviewCategoryWhenMappingAbsent() {
    when(settingService.get(any(Context.class), any(Scope.class), eq(TO_REVIEW_NAME_ID))).thenReturn(null);

    cleanup.deleteToReviewCategory();

    verify(categoryService, never()).deleteCategory(anyLong(), anyString());
    verify(settingService, never()).remove(any(Context.class), any(Scope.class), anyString());
  }

  /**
   * Nominal upgrade path: the mapping resolves, the category is deleted as the
   * super user and the mapping is removed so the nameId can be re-introduced.
   */
  @Test
  @SneakyThrows
  public void deleteToReviewCategoryWhenMappingPresent() {
    doReturn(SettingValue.create(String.valueOf(CATEGORY_ID))).when(settingService)
                                                              .get(any(Context.class), any(Scope.class), eq(TO_REVIEW_NAME_ID));
    when(userAcl.getSuperUser()).thenReturn(SUPER_USER);

    cleanup.deleteToReviewCategory();

    verify(categoryService).deleteCategory(CATEGORY_ID, SUPER_USER);
    verify(settingService).remove(any(Context.class), any(Scope.class), eq(TO_REVIEW_NAME_ID));
  }

  /**
   * Category already gone (e.g. deleted manually): the stale mapping is still
   * removed, otherwise the importer would forever skip a re-introduced nameId.
   */
  @Test
  @SneakyThrows
  public void deleteToReviewCategoryWhenCategoryAlreadyGone() {
    doReturn(SettingValue.create(String.valueOf(CATEGORY_ID))).when(settingService)
                                                              .get(any(Context.class), any(Scope.class), eq(TO_REVIEW_NAME_ID));
    when(userAcl.getSuperUser()).thenReturn(SUPER_USER);
    doThrow(new ObjectNotFoundException("already deleted")).when(categoryService).deleteCategory(CATEGORY_ID, SUPER_USER);

    cleanup.deleteToReviewCategory();

    verify(settingService).remove(any(Context.class), any(Scope.class), eq(TO_REVIEW_NAME_ID));
  }

  /**
   * Failed deletion: the mapping must be kept so the cleanup retries on the
   * next startup instead of leaving an orphaned category behind.
   */
  @Test
  @SneakyThrows
  public void deleteToReviewCategoryWhenDeletionFails() {
    doReturn(SettingValue.create(String.valueOf(CATEGORY_ID))).when(settingService)
                                                              .get(any(Context.class), any(Scope.class), eq(TO_REVIEW_NAME_ID));
    when(userAcl.getSuperUser()).thenReturn(SUPER_USER);
    doThrow(new IllegalAccessException("not allowed")).when(categoryService).deleteCategory(CATEGORY_ID, SUPER_USER);

    assertThrows(IllegalAccessException.class, () -> cleanup.deleteToReviewCategory());

    verify(settingService, never()).remove(any(Context.class), any(Scope.class), anyString());
  }

  /**
   * Corrupted mapping value: treated as absent so startup stays safe, and the
   * mapping is left in place for a human to inspect.
   */
  @Test
  @SneakyThrows
  public void deleteToReviewCategoryWhenMappingUnreadable() {
    doReturn(SettingValue.create("not-a-number")).when(settingService)
                                                 .get(any(Context.class), any(Scope.class), eq(TO_REVIEW_NAME_ID));

    cleanup.deleteToReviewCategory();

    verify(categoryService, never()).deleteCategory(anyLong(), anyString());
    verify(settingService, never()).remove(any(Context.class), any(Scope.class), anyString());
  }
}
