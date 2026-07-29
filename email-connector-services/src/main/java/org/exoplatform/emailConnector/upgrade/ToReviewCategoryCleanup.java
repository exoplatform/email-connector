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

import java.util.List;
import java.util.concurrent.CompletableFuture;

import jakarta.annotation.PostConstruct;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import org.exoplatform.commons.api.settings.SettingService;
import org.exoplatform.commons.api.settings.SettingValue;
import org.exoplatform.commons.api.settings.data.Context;
import org.exoplatform.commons.api.settings.data.Scope;
import org.exoplatform.commons.exception.ObjectNotFoundException;
import org.exoplatform.container.ExoContainerContext;
import org.exoplatform.container.PortalContainer;
import org.exoplatform.container.component.RequestLifeCycle;
import org.exoplatform.emailConnector.plugin.EmailCategoryPlugin;
import org.exoplatform.portal.config.UserACL;
import org.exoplatform.services.log.ExoLogger;
import org.exoplatform.services.log.Log;
import org.exoplatform.social.metadata.MetadataService;
import org.exoplatform.social.metadata.model.MetadataItem;

import io.meeds.social.category.service.CategoryService;

/**
 * One-shot cleanup of the retired "To review" default email category. The
 * platform's category importer is strictly additive — dropping the
 * {@code emailToReviewCategory} descriptor from {@code default-categories.json}
 * leaves the already-created category (and its email links) in the database —
 * so this component deletes it explicitly on startup. It also removes the
 * importer's persisted {@code nameId -> id} mapping: the importer skips any
 * descriptor whose mapping still exists, so a stale mapping would make the
 * nameId impossible to ever re-introduce.
 */
@Component
public class ToReviewCategoryCleanup {

  private static final Log     LOG                     = ExoLogger.getLogger(ToReviewCategoryCleanup.class);

  private static final String  TO_REVIEW_NAME_ID       = "emailToReviewCategory";

  // The platform's CategoryImportService persists its nameId -> category id
  // mappings under these settings coordinates (same constants as in
  // EmailBoxService, which resolves the remaining default categories from them).
  private static final Context CATEGORY_IMPORT_CONTEXT = Context.GLOBAL.id("CATEGORY");

  private static final Scope   CATEGORY_IMPORT_SCOPE   = Scope.APPLICATION.id("CATEGORY_IMPORT");

  // Categories are stored as "category"-typed metadata (the type name is not
  // exposed as an API constant by the platform), so the pre-delete link count
  // is read from the metadata items of that type on email objects.
  private static final String  CATEGORY_METADATA_TYPE  = "category";

  @Autowired
  private SettingService       settingService;

  @Autowired
  private CategoryService      categoryService;

  @Autowired
  private MetadataService      metadataService;

  @Autowired
  private UserACL              userAcl;

  /**
   * Kicks off the cleanup asynchronously at startup so a slow or failing
   * cleanup can never delay or break the platform boot — the same posture as
   * the platform's own category importer.
   */
  @PostConstruct
  public void init() {
    CompletableFuture.runAsync(this::processCleanup);
  }

  /**
   * Runs the cleanup inside the portal container's request lifecycle, which the
   * async thread lacks and the underlying settings/metadata persistence needs.
   * Any failure is logged and swallowed: the cleanup retries on next startup
   * because the mapping is only removed after a successful deletion.
   */
  public void processCleanup() {
    PortalContainer container = PortalContainer.getInstance();
    ExoContainerContext.setCurrentContainer(container);
    RequestLifeCycle.begin(container);
    try {
      deleteToReviewCategory();
    } catch (Exception e) {
      LOG.warn("Failed to clean up the retired '{}' email category, will retry on next startup", TO_REVIEW_NAME_ID, e);
    } finally {
      RequestLifeCycle.end();
    }
  }

  /**
   * Deletes the "To review" category if the importer's mapping shows it was
   * ever created on this installation — absent mapping means a fresh install or
   * an already-cleaned one, so this is a no-op then. The category deletion
   * cascades its email links at DB level; they are counted beforehand so the
   * operation is auditable. The mapping is removed only once the category is
   * confirmed gone, so a failed deletion is retried on the next startup.
   *
   * @throws IllegalAccessException if the super user is unexpectedly not
   *           allowed to delete the category
   */
  public void deleteToReviewCategory() throws IllegalAccessException {
    long categoryId = getToReviewCategoryId();
    if (categoryId <= 0) {
      return;
    }
    long linkCount = countEmailLinks(categoryId);
    try {
      categoryService.deleteCategory(categoryId, userAcl.getSuperUser());
      LOG.info("Deleted the retired '{}' email category (id {}) and its {} email link(s)",
               TO_REVIEW_NAME_ID,
               categoryId,
               linkCount);
    } catch (ObjectNotFoundException e) {
      // Already deleted (e.g. manually); only the stale mapping is left to remove.
      LOG.info("The retired '{}' email category (id {}) no longer exists, removing its stale import mapping",
               TO_REVIEW_NAME_ID,
               categoryId);
    }
    settingService.remove(CATEGORY_IMPORT_CONTEXT, CATEGORY_IMPORT_SCOPE, TO_REVIEW_NAME_ID);
  }

  /**
   * Resolves the "To review" category id from the mapping the platform's
   * category importer persisted when it created the category.
   *
   * @return the category id, or 0 when the mapping is absent or unreadable
   */
  private long getToReviewCategoryId() {
    SettingValue<?> settingValue = settingService.get(CATEGORY_IMPORT_CONTEXT, CATEGORY_IMPORT_SCOPE, TO_REVIEW_NAME_ID);
    if (settingValue == null || settingValue.getValue() == null) {
      return 0;
    }
    try {
      return Long.parseLong(settingValue.getValue().toString());
    } catch (NumberFormatException e) {
      LOG.warn("Invalid category id '{}' stored for {}", settingValue.getValue(), TO_REVIEW_NAME_ID);
      return 0;
    }
  }

  /**
   * Counts the email links of the given category before it is deleted, purely
   * for the audit log — a counting failure must not block the deletion itself.
   *
   * @param categoryId the category whose email links are counted
   * @return the number of emails linked to the category, or -1 when the count
   *         could not be read
   */
  private long countEmailLinks(long categoryId) {
    try {
      List<MetadataItem> items = metadataService.getMetadataItemsByMetadataTypeAndObjectType(CATEGORY_METADATA_TYPE,
                                                                                             EmailCategoryPlugin.OBJECT_TYPE);
      return items == null ? 0 :
                           items.stream()
                                .filter(item -> item.getMetadata() != null && item.getMetadata().getId() == categoryId)
                                .count();
    } catch (Exception e) {
      LOG.warn("Unable to count the email links of category {}", categoryId, e);
      return -1;
    }
  }
}
