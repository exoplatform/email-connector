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

/**
 * What an owner lets one delegate do in one folder of their mailbox, as the owner's
 * "Folders and access" list offers it (EXO-90556): the two presets, or not shared at
 * all. Never letters: what a preset writes on a folder is the engine's to say by the
 * folder's role ({@code MailboxAclEngine.lettersFor}) -- an Editor holds {@code e} where
 * mail leaves and never on Trash.
 */
public enum FolderAccess {

  /** Read the folder's mail, and mark it read. */
  READER,

  /** Also star, file, move and delete. */
  EDITOR,

  /** The delegate has no entry on the folder: they do not see it. */
  NONE;

  /**
   * The preset this access writes, null for {@link #NONE}, which removes the entry.
   *
   * @return READER, EDITOR or null
   */
  public DelegationPreset preset() {
    return switch (this) {
    case READER -> DelegationPreset.READER;
    case EDITOR -> DelegationPreset.EDITOR;
    case NONE -> null;
    };
  }

  /**
   * The access a grantable preset stands for.
   *
   * @param preset READER or EDITOR
   * @return the access, null for any other preset
   */
  public static FolderAccess of(DelegationPreset preset) {
    if (preset == DelegationPreset.READER) {
      return READER;
    }
    return preset == DelegationPreset.EDITOR ? EDITOR : null;
  }

  /**
   * The access of a stored or requested name, null for a blank or unknown one.
   *
   * @param name the name
   * @return the access, or null
   */
  public static FolderAccess of(String name) {
    if (name == null || name.isBlank()) {
      return null;
    }
    for (FolderAccess access : values()) {
      if (access.name().equals(name.trim())) {
        return access;
      }
    }
    return null;
  }
}
