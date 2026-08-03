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

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Lightweight DTO exposing an email category by its technical id and its
 * locale-resolved display name (used to tag/untag emails from the MCP tools).
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class EmailCategory {

  private long   id;

  private String name;

  /**
   * The stable, locale-independent identifier of one of the add-on's own default
   * categories (e.g. {@code emailImportantCategory}), as declared in
   * {@code default-categories.json}. The display name is localized, so this is
   * what the interface keys behavior on — the mailbox surfaces the Important
   * category as a dedicated filter chip. Null for a category that is not one of
   * the defaults (e.g. one merely found linked to an email).
   */
  private String nameId;

  /**
   * The category's own icon (a Font Awesome name such as
   * {@code fa-exclamation-circle}), as declared in
   * {@code default-categories.json} and persisted by the platform's category
   * importer on the {@link io.meeds.social.category.model.Category} itself.
   * The mailbox renders it wherever the category is offered (the ⋮ menu's
   * Categories section, the Important chip). Null when the category declares
   * none — the interface falls back to a generic tag icon then.
   */
  private String icon;

  /**
   * Builds a category with no known nameId or icon, for categories resolved
   * from links rather than from the add-on's default set.
   *
   * @param id the category id
   * @param name the locale-resolved display name
   */
  public EmailCategory(long id, String name) {
    this(id, name, null, null);
  }
}
