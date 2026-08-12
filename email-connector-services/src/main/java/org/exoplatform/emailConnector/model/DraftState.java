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
  SYNCED,

  /**
   * The draft has been handed to the SMTP server and the send has not come back
   * yet. Unlike the three above it says nothing about the server copy — it is a
   * claim on the draft, and it exists so that a second send cannot start while the
   * first is still in the air. Double-sending a mail is not a failure the user can
   * undo.
   * <p>
   * The in-JVM lock already serialises two sends, so this state earns its keep in
   * the case the lock cannot cover: a second request landing on another cluster
   * node. It is also why the state is persisted rather than held in memory.
   * <p>
   * It is a transient state and every path out of the send restores or removes it —
   * a successful send deletes the row outright, a refused one puts back the state
   * the row had before. A JVM killed mid-send is the one case that can leave a row
   * stuck here; such a draft can still be discarded, and that is the deliberate
   * trade, because the alternative (letting a stale claim expire on its own) is a
   * timeout that would eventually permit exactly the double send this prevents.
   */
  SENDING
}
