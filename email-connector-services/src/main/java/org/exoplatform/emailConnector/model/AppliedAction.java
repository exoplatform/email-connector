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
package org.exoplatform.emailConnector.model;

/**
 * What one action of an eXo rule did to one mail, and what undoing it needs.
 *
 * @param type the action's type, a {@link FilterAction} type or an assistant output
 * @param ok whether it was applied
 * @param reason why it was not, a message code; null when it was
 * @param folderKey for a filing action: the folder the mail was filed into
 * @param originFolder for a filing action: the folder it came from
 * @param categoryId for a category: the category put on it
 * @param wasRead for a read mark: whether the mail was read before
 * @param wasStarred for a star: whether the mail was starred before
 * @param draftLocalId for a draft reply: the draft's local id
 * @param undone whether the owner undid it
 */
public record AppliedAction(String type,
                            boolean ok,
                            String reason,
                            String folderKey,
                            String originFolder,
                            Long categoryId,
                            Boolean wasRead,
                            Boolean wasStarred,
                            String draftLocalId,
                            boolean undone) {

  /**
   * An action that was applied.
   *
   * @param type the type
   * @return the record, nothing to undo it with yet
   */
  public static AppliedAction applied(String type) {
    return new AppliedAction(type, true, null, null, null, null, null, null, null, false);
  }

  /**
   * An action that could not be applied.
   *
   * @param type the type
   * @param reason the message code
   * @return the record
   */
  public static AppliedAction failed(String type, String reason) {
    return new AppliedAction(type, false, reason, null, null, null, null, null, null, false);
  }

  /**
   * The same record, undone.
   *
   * @return the record
   */
  public AppliedAction asUndone() {
    return new AppliedAction(type, ok, reason, folderKey, originFolder, categoryId, wasRead, wasStarred, draftLocalId, true);
  }
}
