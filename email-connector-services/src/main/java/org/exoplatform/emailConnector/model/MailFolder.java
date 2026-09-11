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

import java.util.List;

/**
 * The remote mail folder a cached message belongs to. Stored as a string
 * discriminator on {@code EMAIL_BOX.FOLDER}; IMAP UIDs are per-folder, so the
 * cache is keyed by (userId, folder, mailRemoteId).
 */
public final class MailFolder {

  private MailFolder() {
  }

  public static final String INBOX    = "INBOX";

  public static final String SENT     = "SENT";

  public static final String ARCHIVE  = "ARCHIVE";

  // The Gmail "All Mail" (\All) superset. Never bulk-synced (it duplicates every
  // folder); rows land here only via on-demand thread completion, so an archived
  // message that lost its INBOX label still shows inline when its thread is opened.
  public static final String ALL_MAIL = "ALL_MAIL";

  // Unsent drafts. The one folder whose rows are AUTHORED here rather than mirrored
  // from the server, so it inverts the cache's usual direction: for INBOX / SENT /
  // ARCHIVE the server is the truth and the local row a copy, while a draft is
  // written locally first and pushed up afterwards. Everything that treats "not on
  // the server" as "delete the local row" therefore has to make an exception for it
  // — see EmailBoxService#cleanupObsoleteEmails.
  public static final String DRAFTS   = "DRAFTS";

  // Deleted mail. Mirrored from the server exactly as SENT and ARCHIVE are — the row
  // is a copy of what the Trash folder holds, and nothing here authors one — but it
  // is the one mirrored folder that must NOT be read back into the rest of the
  // product: it is excluded from the conversation reader, from the per-conversation
  // summaries the list is built on, and from the cached search.
  //
  // The distinction the exclusions encode is "browsable, not resurfaced". A user who
  // opens Trash is asking to see what they threw away, and a folder-scoped read
  // answers them. Every OTHER read is somebody who did not ask: a message deleted out
  // of a conversation must not reappear inside that conversation, and a search must
  // not offer back what the user threw away. A cross-folder read with no folder
  // predicate does exactly that the moment the first TRASH row exists, silently and
  // everywhere at once — which is why the exclusions landed BEFORE anything writes
  // one, rather than alongside the folder that will.
  //
  // Two kinds of read stay deliberately total, and both are wrong to "fix":
  // wiping a mailbox (disconnect / rebind) must delete TRASH rows like any other, or
  // deleted mail outlives the account it belonged to; and the thread-identity
  // machinery (sibling lookup, Thread-Index roots, merges) must keep seeing them, or
  // a trashed message is orphaned from its conversation and comes back — if it ever
  // comes back — as a conversation of its own.
  public static final String TRASH    = "TRASH";

  // Mail the server quarantined as spam. Mirrored exactly as TRASH is, and in the same
  // behaviour class as TRASH — "browsable, not resurfaced" — for the same reason with
  // the same teeth: a user who opens Spam is asking to see what was filtered and to
  // rescue a false positive, and a folder-scoped read answers them; every other read
  // is somebody who did not ask, and a phishing reply to a real thread rendered inside
  // that conversation, offered back by search, or counted in "N messages" is the
  // exact leak the Trash exclusions were written to prevent. So JUNK does not get its
  // own set of exclusions: it joins TRASH in HIDDEN_FOLDERS, which is the one spelling
  // of the excluded set every excluding read is fed, and a read that forgets the list
  // forgets both folders at once — visibly, because the tests write a row in each.
  //
  // The two deliberately-total reads (the mailbox wipe, the thread-identity machinery)
  // stay total for JUNK too: a quarantined message must die with the account it
  // belonged to, and it must keep its place in its conversation so "Not spam" puts
  // it back where it was rather than as a conversation of its own.
  public static final String JUNK     = "JUNK";

  /**
   * The folders whose rows are browsable but never resurfaced — the set every
   * cross-folder read that shows mail leaves out. Passed as one bound list to the
   * {@code folder NOT IN :excludedFolders} predicates of the DAO so the excluded set
   * is spelled here and nowhere else: a folder added to this class in the Trash
   * behaviour class is added to this list and to nothing else, and every excluding
   * read follows. {@code FOLDER} is {@code NOT NULL} in the schema, so {@code NOT IN}
   * can never be UNKNOWN for a row and silently drop it.
   */
  public static final List<String> HIDDEN_FOLDERS = List.of(TRASH, JUNK);

  // The prefix of a CUSTOM folder's key -- a folder the user made in their own mailbox
  // ("Factures", "Customers/Acme", a Gmail label), mirrored here on their say-so.
  // The key written to EMAIL_BOX.FOLDER for such a folder is "CUSTOM:" + the id of its
  // EMAIL_FOLDER registry row, never the folder's remote name: the name would not fit
  // the VARCHAR(50) the column is, would orphan every row the day the folder is
  // renamed on the server, would drag the hierarchy delimiter, modified-UTF-7 and a
  // case question into a key, and a user folder literally named "SENT" would collide
  // with the constant above. The id has none of those problems, and the registry row
  // it points at is where the remote name, the display name and the per-folder sync
  // memory live.
  //
  // Custom folders are in the ARCHIVE behaviour class -- browsable AND resurfaced --
  // and that is the whole reason they are cheap: filing a message in "Factures" is
  // organisation, not disposal, so the message keeps its place in its conversation and
  // in search, and the exclusions written for TRASH and JUNK are not touched. Getting
  // the class wrong here is the exact mirror of getting it wrong for Junk: a custom
  // folder treated as hidden makes filing into deleting, and the more organised the
  // user, the more of their own mail the platform hides from them.
  public static final String CUSTOM_KEY_PREFIX = "CUSTOM:";

  // The built-in folders a user may open as a list. ALL_MAIL is deliberately absent: it
  // is an on-demand completion store, never a listing. A custom folder is browsable by
  // construction (see isBrowsable), so this list is only the built-in half of it.
  public static final List<String> BROWSABLE_BUILT_INS = List.of(INBOX, SENT, ARCHIVE, DRAFTS, TRASH, JUNK);

  /**
   * The {@code EMAIL_BOX.FOLDER} key of a custom folder, from its registry id.
   *
   * @param id the {@code EMAIL_FOLDER} row id
   * @return the key, e.g. {@code CUSTOM:42}
   */
  public static String customKey(long id) {
    return CUSTOM_KEY_PREFIX + id;
  }

  /**
   * Whether a folder key addresses a custom folder -- one of the user's own, through
   * the registry -- rather than one of the constants above.
   *
   * @param key the folder discriminator, possibly null
   * @return true for a {@code CUSTOM:<id>} key
   */
  public static boolean isCustom(String key) {
    return key != null && key.startsWith(CUSTOM_KEY_PREFIX) && key.length() > CUSTOM_KEY_PREFIX.length();
  }

  /**
   * The registry id a custom folder key carries.
   * <p>
   * Refuses anything that is not a well-formed custom key, with the message code the
   * REST layer answers 400 with: a client sending a key this schema never wrote is
   * asking for a folder that does not exist, and answering INBOX instead -- the fallback
   * every {@code String folder} parameter has for a BLANK value -- would silently read
   * the wrong mailbox.
   *
   * @param key the folder discriminator
   * @return the {@code EMAIL_FOLDER} row id
   * @throws IllegalArgumentException if the key is not a custom folder key
   */
  public static long customId(String key) {
    if (!isCustom(key)) {
      throw new IllegalArgumentException("emailConnector.folder.unknown");
    }
    try {
      return Long.parseLong(key.substring(CUSTOM_KEY_PREFIX.length()));
    } catch (NumberFormatException e) {
      throw new IllegalArgumentException("emailConnector.folder.unknown");
    }
  }

  /**
   * Whether a folder key is one of the seven constants above -- a folder this add-on
   * discovers by SPECIAL-USE attribute or well-known name, as opposed to one the user
   * made.
   *
   * @param key the folder discriminator, possibly null
   * @return true for INBOX, SENT, ARCHIVE, ALL_MAIL, DRAFTS, TRASH and JUNK
   */
  public static boolean isBuiltIn(String key) {
    return INBOX.equals(key) || SENT.equals(key) || ARCHIVE.equals(key) || ALL_MAIL.equals(key) || DRAFTS.equals(key)
        || TRASH.equals(key) || JUNK.equals(key);
  }

  /**
   * Whether a folder key may be opened as a list: the browsable built-ins, or any
   * custom folder -- whose EXISTENCE for this user is a separate question the registry
   * answers, not a question of the key's shape. This is the one spelling of "the folders
   * a user can browse"; the client's folder menu is fed from it rather than from a
   * list of its own, so the two cannot disagree.
   *
   * @param key the folder discriminator, possibly null
   * @return true when a folder-scoped listing may be served for that key
   */
  public static boolean isBrowsable(String key) {
    return key != null && (BROWSABLE_BUILT_INS.contains(key) || isCustom(key));
  }

  /**
   * Whether rows of a folder are read back into the rest of the product -- the
   * conversation reader, the summaries, the search -- or served ONLY by a folder-scoped
   * listing. The complement of {@link #HIDDEN_FOLDERS}, named so a caller asks the
   * question in the terms the TRASH comment above states it.
   *
   * @param key the folder discriminator, possibly null
   * @return false for TRASH and JUNK, true for everything else
   */
  public static boolean isResurfaced(String key) {
    return key == null || !HIDDEN_FOLDERS.contains(key);
  }
}
