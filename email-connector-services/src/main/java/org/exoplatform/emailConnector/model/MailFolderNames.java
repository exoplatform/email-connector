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

import java.util.Set;

/**
 * The well-known names of the role folders, lowercased, for the servers that show no
 * SPECIAL-USE attribute (Stalwart 0.11.8 shows none: "Deleted Items", "Junk Mail",
 * "Sent Items"). One list per role, read by the user's own folder classification
 * (EmailFolderService) and by the owner-side and delegate-side role resolution of a
 * shared mailbox ({@link FolderRole#ofUsualName}), so a folder the user's own mailbox
 * recognises is recognised in a shared one too (EXO-90548). Matched for equality on a
 * folder's last path segment, never as a substring: "Trash notes" is not the Trash.
 */
public final class MailFolderNames {

  /**
   * The Drafts names, in the locales the product ships plus the few its users' other
   * clients create.
   */
  public static final Set<String> DRAFTS  = Set.of("drafts",
                                                   "draft",
                                                   "brouillons",
                                                   "brouillon",
                                                   "entwürfe",
                                                   "entwuerfe",
                                                   "bozze",
                                                   "borradores",
                                                   "rascunhos",
                                                   "concepten",
                                                   "utkast",
                                                   "kladde",
                                                   "luonnokset");

  /** The Trash names, plus the "Deleted ..." names Exchange and its clients create. */
  public static final Set<String> TRASH   = Set.of("trash",
                                                   "deleted",
                                                   "deleted items",
                                                   "deleted messages",
                                                   "corbeille",
                                                   "papierkorb",
                                                   "cestino",
                                                   "papelera",
                                                   "lixeira",
                                                   "prullenbak",
                                                   "papperskorg",
                                                   "papirkurv",
                                                   "roskakori");

  /** The Junk names, plus the "Spam" / "Bulk" names the big providers create. */
  public static final Set<String> JUNK    = Set.of("junk",
                                                   "junk e-mail",
                                                   "junk-e-mail",
                                                   "junk email",
                                                   "junk mail",
                                                   "spam",
                                                   "спам",
                                                   "bulk mail",
                                                   "courrier indésirable",
                                                   "indésirables",
                                                   "pourriel",
                                                   "spamverdacht",
                                                   "unerwünscht",
                                                   "posta indesiderata",
                                                   "correo no deseado",
                                                   "no deseado",
                                                   "lixo eletrônico",
                                                   "lixo eletronico",
                                                   "ongewenste e-mail",
                                                   "skräppost",
                                                   "roskaposti",
                                                   "uønsket e-post",
                                                   "søppelpost");

  /** The Archive names. */
  public static final Set<String> ARCHIVE = Set.of("archive", "archives", "archivage");

  /**
   * Not instantiable: a holder of constants.
   */
  private MailFolderNames() {
  }
}
