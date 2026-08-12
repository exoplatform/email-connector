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
package org.exoplatform.emailConnector.model;

/**
 * Where a draft's local row stands relative to the copy on the mail server.
 * <p>
 * The distinction exists because IMAP has no update: a draft is uploaded by
 * APPENDing the whole message again and deleting the previous copy, so the only
 * two questions worth asking of a row are "is there a copy up there at all" and
 * "is it still the text the user last typed". Those are the three states below,
 * and between them they decide the two things the save path does: whether an
 * upload is needed at all, and whether a previous copy has to be removed
 * afterwards.
 */
public enum DraftState {

  /**
   * Written here, never uploaded — either the account has no Drafts folder, the
   * administrator turned server-side drafts off, the upload has not been asked
   * for yet (typing pauses save locally only), or it failed. The user's words are
   * safe; nobody else's mail client can see them yet. Nothing to delete on the
   * server when the next upload lands.
   */
  LOCAL_ONLY,

  /**
   * A copy exists on the server but the user has typed since it was made. The next
   * upload has to APPEND the new text AND remove the stale copy, or the user's
   * other clients show two drafts of the same message.
   */
  DIRTY,

  /**
   * The server copy is the text the user last typed. The single most valuable
   * state to be able to name: it is what lets a save that changed nothing skip the
   * APPEND entirely, and an APPEND re-uploads the whole message, attachments
   * included.
   */
  SYNCED
}
