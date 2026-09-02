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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

import io.meeds.social.util.JsonUtils;

/**
 * The sync memory's JSON round trip through the settings serializer, with every
 * field set — the positional constructor included.
 * <p>
 * The class carries a rule ("new fields go at the END") whose violation does not
 * fail a build: a field slipped into the middle re-numbers every positional argument
 * after it and every call site keeps compiling. This is the test that turns that
 * into a failure — it names each field by its accessor after building the state
 * positionally, so a shifted column is a wrong value here rather than a wrong folder
 * in production.
 */
class MailboxSyncStateTest {

  /**
   * Every field survives the trip, the Junk pair included, and each one comes back
   * under its own name.
   */
  @Test
  void everyFieldSurvivesTheJsonRoundTrip() {
    MailboxSyncState state = new MailboxSyncState(snapshot(1),
                                                  snapshot(2),
                                                  snapshot(3),
                                                  "Sent",
                                                  "Archive",
                                                  "Drafts",
                                                  snapshot(4),
                                                  "Trash",
                                                  snapshot(5),
                                                  "[Gmail]/Spam",
                                                  snapshot(6),
                                                  1_700_000_000_000L);

    MailboxSyncState back = JsonUtils.fromJsonString(JsonUtils.toJsonString(state), MailboxSyncState.class);

    assertEquals(1L, back.getSnapshot(MailFolder.INBOX).getUidValidity());
    assertEquals(2L, back.getSnapshot(MailFolder.SENT).getUidValidity());
    assertEquals(3L, back.getSnapshot(MailFolder.ARCHIVE).getUidValidity());
    assertEquals("Sent", back.getSentFolderName());
    assertEquals("Archive", back.getArchiveFolderName());
    assertEquals("Drafts", back.getDraftsFolderName());
    assertEquals(4L, back.getSnapshot(MailFolder.DRAFTS).getUidValidity());
    assertEquals("Trash", back.getTrashFolderName());
    assertEquals(5L, back.getSnapshot(MailFolder.TRASH).getUidValidity());
    assertEquals("[Gmail]/Spam", back.getJunkFolderName(), "the Junk name is the tenth positional argument, after every older field");
    assertEquals(6L, back.getSnapshot(MailFolder.JUNK).getUidValidity(), "and its snapshot the eleventh");
    assertEquals(1_700_000_000_000L, back.getFoldersDiscoveredAt(), "the folder-walk stamp is the twelfth, after everything");
  }

  /**
   * A blob written before the Junk fields existed reads back with them empty, so the
   * first sync after the upgrade takes the Junk folder down the full path once and
   * touches nothing else — the same shape every mailbox met when Trash arrived.
   */
  @Test
  void aStateWrittenBeforeJunkExistedReadsBackWithoutIt() {
    MailboxSyncState back = JsonUtils.fromJsonString("{\"trashFolderName\":\"Trash\",\"trashSnapshot\":{\"uidValidity\":5,\"uidNext\":6,\"messageCount\":7,\"highestModSeq\":8,\"windowSize\":30}}",
                                                     MailboxSyncState.class);

    assertEquals("Trash", back.getTrashFolderName());
    assertEquals(5L, back.getSnapshot(MailFolder.TRASH).getUidValidity());
    assertNull(back.getJunkFolderName());
    assertNull(back.getSnapshot(MailFolder.JUNK));
  }

  /**
   * The store and clear arms of the Junk snapshot, so a folder-keyed write lands on
   * the Junk field and only there.
   */
  @Test
  void theJunkSnapshotIsStoredUnderItsOwnKey() {
    MailboxSyncState state = new MailboxSyncState();

    state.setSnapshot(MailFolder.JUNK, snapshot(9));

    assertEquals(9L, state.getSnapshot(MailFolder.JUNK).getUidValidity());
    assertNull(state.getSnapshot(MailFolder.TRASH), "the neighbouring hidden folder's field is not the one written");
    state.setSnapshot(MailFolder.JUNK, null);
    assertNull(state.getSnapshot(MailFolder.JUNK));
  }

  /**
   * A snapshot recognisable by its UIDVALIDITY.
   *
   * @param marker the value to plant in the first field
   * @return the snapshot
   */
  private FolderSyncSnapshot snapshot(long marker) {
    return new FolderSyncSnapshot(marker, marker * 10, marker * 100, marker * 1000, 30);
  }
}
