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
 * The right sets a mailbox owner can choose from when sharing from eXo. Two presets
 * and no letter picker: nobody can be expected to know what {@code x} does. A share
 * whose rights match neither preset -- one granted in the mail server's own interface,
 * or narrowed by an administrator -- is {@link #CUSTOM}: displayed, never authored here.
 * <p>
 * The letters each preset carries here are the <b>IMAP</b> reading, what
 * {@code ImapAclEngine} writes with SETACL. What a preset becomes on another server is
 * the engine's to say ({@code MailboxAclEngine.presetOf}, delegation plan section
 * 3.4): BlueMind's read share is the verb {@code Read}, which pushes {@code lrp} to
 * its mailstore -- no {@code s} -- so an exact match against {@link #READER}'s
 * {@code lrs} would render every read share made in BlueMind's own interface as
 * CUSTOM. {@link #fromRights} is therefore the default recognition, not the only one.
 */
public enum DelegationPreset {

  /**
   * {@code lrs}: see the folder, read the mail, and have read/unread changes kept by
   * the server -- kept for the mailbox, not for this user: {@code \Seen} is one flag
   * shared by the owner and every delegate on both servers observed (plan, sections
   * 2.1 and 13.B.13).
   */
  READER("lrs"),

  /**
   * {@code lrswit}: a Reader who can also star and tag, file into and move or trash
   * out of the folder. On an IMAP server the engine adds {@code e} (expunge) on every
   * folder mail leaves from -- INBOX, Sent, Archive, Spam and the owner's own folders --
   * and never on Trash, where it would be permanent deletion (PO decision Q-1,
   * {@code ImapAclEngine.lettersFor}). Never {@code x} (delete the mailbox), {@code k},
   * {@code p} or {@code a}.
   */
  EDITOR("lrswit"),

  /** Letters observed on the server that are neither preset. Not grantable from eXo. */
  CUSTOM(null);

  private final String letters;

  /**
   * @param letters the preset's letters, null for CUSTOM
   */
  DelegationPreset(String letters) {
    this.letters = letters;
  }

  /**
   * The rights this preset stands for.
   *
   * @return the rights, empty for {@link #CUSTOM}
   */
  public MailboxRights rights() {
    return MailboxRights.of(letters);
  }

  /**
   * Whether an owner may pick this preset when sharing from eXo.
   *
   * @return true for the two authored presets
   */
  public boolean isGrantable() {
    return letters != null;
  }

  /**
   * The preset a set of observed rights corresponds to, letter for letter -- the IMAP
   * reading, and the default an engine's {@code presetOf} falls back to. An engine
   * whose server pushes other letters for the same preset (BlueMind, section 3.4)
   * recognises them itself rather than through this method.
   *
   * @param rights the rights a server answered
   * @return READER or EDITOR when the letters match exactly, CUSTOM otherwise
   */
  public static DelegationPreset fromRights(MailboxRights rights) {
    if (rights == null) {
      return CUSTOM;
    }
    for (DelegationPreset preset : values()) {
      if (preset.isGrantable() && preset.rights().equals(rights)) {
        return preset;
      }
    }
    return CUSTOM;
  }
}
