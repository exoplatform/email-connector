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
package org.exoplatform.emailConnector.service;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import org.exoplatform.emailConnector.model.DiscoveredFolder;
import org.exoplatform.emailConnector.model.EmailFolder;
import org.exoplatform.emailConnector.model.FolderClassification;
import org.exoplatform.emailConnector.model.FolderSyncSnapshot;
import org.exoplatform.emailConnector.model.MailFolder;
import org.exoplatform.emailConnector.model.MailFolderView;
import org.exoplatform.emailConnector.storage.EmailFolderStorage;

/**
 * The classification of a mailbox's folder list, and the four cost rules over the
 * registry: opt-in with its cap, the budgeted least-recently-checked rotation, the
 * grace-then-delete of a vanished folder, and the staleness that triggers an on-open
 * refresh. Pure decisions over a mocked registry; the persistence is pinned on a real
 * database in {@code EmailFolderStorageTest} and {@code EmailFolderDAOTest}.
 */
@ExtendWith(MockitoExtension.class)
class EmailFolderServiceTest {

  private static final String USER = "alice";

  @Mock
  private EmailFolderStorage  emailFolderStorage;

  @Mock
  private EmailConnectorService emailConnectorService;

  @InjectMocks
  private EmailFolderService  emailFolderService;

  /**
   * Puts the tunables back the way the JVM had them.
   */
  @AfterEach
  void clearTheTunables() {
    System.clearProperty(EmailFolderService.CUSTOM_FOLDERS_MAX_PROPERTY);
    System.clearProperty(EmailFolderService.CUSTOM_FOLDERS_PER_CYCLE_PROPERTY);
    System.clearProperty(EmailFolderService.CUSTOM_FOLDERS_STALE_MINUTES_PROPERTY);
    System.clearProperty(EmailFolderService.CUSTOM_FOLDERS_DISCOVERY_HOURS_PROPERTY);
  }

  // ---------------------------------------------------------------------------------
  // The master switch
  // ---------------------------------------------------------------------------------

  /**
   * Since the administration settings drawer shipped, the master switch is no longer
   * a bare System property read here: it delegates to
   * {@code EmailConnectorService#isCustomFoldersEnabled()}, which is the one that
   * knows about the SettingService-backed value and falls back to the property
   * itself.
   */
  @Test
  void isCustomFoldersEnabledDelegatesToEmailConnectorService() {
    when(emailConnectorService.isCustomFoldersEnabled()).thenReturn(false);
    assertFalse(emailFolderService.isCustomFoldersEnabled());
  }

  // ---------------------------------------------------------------------------------
  // Classification
  // ---------------------------------------------------------------------------------

  /**
   * The Gmail shape: the unselectable parent, the two virtual views and All Mail are
   * none of the user's; the attributed folders fill their roles; a label is custom.
   */
  @Test
  void aGmailListingClassifiesByAttributeAndDropsTheVirtualViews() {
    FolderClassification classification =
                                        emailFolderService.classify(List.of(folder("INBOX", true),
                                                                            folder("[Gmail]", true, "\\Noselect", "\\HasChildren"),
                                                                            folder("[Gmail]/Starred", true, "\\Flagged"),
                                                                            folder("[Gmail]/Important", true, "\\Important"),
                                                                            folder("[Gmail]/All Mail", true, "\\All"),
                                                                            folder("[Gmail]/Sent Mail", true, "\\Sent"),
                                                                            folder("[Gmail]/Drafts", true, "\\Drafts"),
                                                                            folder("[Gmail]/Spam", false, "\\Junk"),
                                                                            folder("[Gmail]/Trash", false, "\\Trash"),
                                                                            folder("Factures", true),
                                                                            folder("Customers/Acme", true)));
    assertEquals("INBOX", classification.builtIn(MailFolder.INBOX).fullName());
    assertEquals("[Gmail]/Sent Mail", classification.builtIn(MailFolder.SENT).fullName());
    assertEquals("[Gmail]/Drafts", classification.builtIn(MailFolder.DRAFTS).fullName());
    assertEquals("[Gmail]/Spam", classification.builtIn(MailFolder.JUNK).fullName());
    assertEquals("[Gmail]/Trash", classification.builtIn(MailFolder.TRASH).fullName());
    assertEquals("[Gmail]/All Mail", classification.builtIn(MailFolder.ALL_MAIL).fullName());
    assertNull(classification.builtIn(MailFolder.ARCHIVE), "All Mail is never the syncable Archive");
    assertEquals(List.of("Factures", "Customers/Acme"),
                 classification.customs().stream().map(DiscoveredFolder::fullName).toList());
  }

  /**
   * A server that never learned SPECIAL-USE: every built-in is found by exactly the
   * name rule its former walker applied -- a French Junk and a German Trash by their
   * last segment, Sent by a loose contains, Archive by the full name -- and none of
   * them leaks into the custom list.
   */
  @Test
  void localizedBuiltInNamesFillTheirRolesAndAreNeverCustom() {
    FolderClassification classification =
                                        emailFolderService.classify(List.of(folder("INBOX", true),
                                                                            folder("INBOX.Courrier indésirable", true),
                                                                            folder("INBOX.Papierkorb", true),
                                                                            folder("INBOX.Brouillons", true),
                                                                            folder("Sent Items", true),
                                                                            folder("Archives", true),
                                                                            folder("Projets", true)));
    assertEquals("INBOX.Courrier indésirable", classification.builtIn(MailFolder.JUNK).fullName());
    assertEquals("INBOX.Papierkorb", classification.builtIn(MailFolder.TRASH).fullName());
    assertEquals("INBOX.Brouillons", classification.builtIn(MailFolder.DRAFTS).fullName());
    assertEquals("Sent Items", classification.builtIn(MailFolder.SENT).fullName());
    assertEquals("Archives", classification.builtIn(MailFolder.ARCHIVE).fullName());
    assertEquals(List.of("Projets"), classification.customs().stream().map(DiscoveredFolder::fullName).toList());
  }

  /**
   * The strictness that protects the hidden folders survives the move: a user's
   * "Spam reports" is not the Junk folder (last-segment equality, not contains), so it
   * is theirs; and the server's attribute beats an earlier name match, so "Spam"
   * without the attribute is a folder named Spam when another carries {@code \Junk}.
   */
  @Test
  void theAttributeBeatsAnEarlierNameMatchAndAContainsNeverMatchesAHiddenRole() {
    FolderClassification classification =
                                        emailFolderService.classify(List.of(folder("Spam", true),
                                                                            folder("Spam reports", true),
                                                                            folder("Courrier indésirable", true, "\\Junk")));
    assertEquals("Courrier indésirable", classification.builtIn(MailFolder.JUNK).fullName());
    assertEquals(List.of("Spam", "Spam reports"), classification.customs().stream().map(DiscoveredFolder::fullName).toList());
  }

  /**
   * Subscribed folders are considered first: of two {@code \Trash} folders, the
   * subscribed one is the Trash even when it is listed second, and the other is the
   * user's. An unselectable folder is nobody's.
   */
  @Test
  void aSubscribedFolderWinsAndAnUnselectableOneIsDropped() {
    FolderClassification classification =
                                        emailFolderService.classify(List.of(folder("Old Trash", false, "\\Trash"),
                                                                            folder("Trash", true, "\\Trash"),
                                                                            new DiscoveredFolder("Shared", "Shared", "/", Set.of(), true, false)));
    assertEquals("Trash", classification.builtIn(MailFolder.TRASH).fullName());
    assertEquals(List.of("Old Trash"), classification.customs().stream().map(DiscoveredFolder::fullName).toList());
  }

  // ---------------------------------------------------------------------------------
  // The registry: reconciliation, opt-in, rotation
  // ---------------------------------------------------------------------------------

  /**
   * One walk: a new folder is registered opt-in off, a known one is refreshed, one not
   * seen is marked missing, one already missing is deleted and handed back so its
   * mirrored rows go with it.
   */
  @Test
  void aWalkRegistersRefreshesGracesThenDeletes() {
    EmailFolder known = registered(1L, "Known", false, false);
    EmailFolder vanishing = registered(2L, "Vanishing", true, false);
    EmailFolder gone = registered(3L, "Gone", true, true);
    when(emailFolderStorage.getFolderByRemoteName(USER, "Known")).thenReturn(known);
    when(emailFolderStorage.getFolderByRemoteName(USER, "New/Folder")).thenReturn(null);
    when(emailFolderStorage.getFolders(USER)).thenReturn(List.of(known, vanishing, gone));

    List<EmailFolder> purged = emailFolderService.reconcileDiscovered(USER,
                                                                      List.of(folder("Known", true), folder("New/Folder", true)));

    ArgumentCaptor<EmailFolder> created = ArgumentCaptor.forClass(EmailFolder.class);
    verify(emailFolderStorage).createFolder(created.capture());
    assertEquals("New/Folder", created.getValue().getRemoteName());
    assertEquals("Folder", created.getValue().getDisplayName(), "the display name is the last segment");
    assertFalse(created.getValue().isSyncEnabled());
    verify(emailFolderStorage).markSeen(eq(USER), eq(1L), eq("Known"), eq("/"), any(Date.class));
    verify(emailFolderStorage).markMissing(USER, 2L);
    verify(emailFolderStorage).deleteFolder(USER, 3L);
    assertEquals(List.of(gone), purged);
  }

  /**
   * The cap: the eleventh opt-in is refused with the message the screen shows, and
   * nothing is written; the opt-in of a folder already opted in is a no-op that is
   * not counted against it.
   */
  @Test
  void theEleventhOptInIsRefusedAndARepeatIsNotCounted() {
    when(emailFolderStorage.getFolder(USER, 7L)).thenReturn(registered(7L, "Seventh", false, false));
    when(emailFolderStorage.countEnabledFolders(USER)).thenReturn(10L);

    IllegalArgumentException refusal = assertThrows(IllegalArgumentException.class,
                                                    () -> emailFolderService.setSyncEnabled(USER, 7L, true));
    assertEquals(EmailFolderService.TOO_MANY_FOLDERS_MESSAGE, refusal.getMessage());
    verify(emailFolderStorage, never()).updateSyncEnabled(anyString(), anyLong(), anyBoolean(), any());

    when(emailFolderStorage.getFolder(USER, 8L)).thenReturn(registered(8L, "Eighth", true, false));
    emailFolderService.setSyncEnabled(USER, 8L, true);
    verify(emailFolderStorage, never()).updateSyncEnabled(anyString(), anyLong(), anyBoolean(), any());
  }

  /**
   * The cap follows the property, hot; and an opt-out writes the opt-in off.
   */
  @Test
  void theCapIsTunableAndAnOptOutIsWritten() {
    System.setProperty(EmailFolderService.CUSTOM_FOLDERS_MAX_PROPERTY, "2");
    when(emailFolderStorage.getFolder(USER, 7L)).thenReturn(registered(7L, "Seventh", false, false),
                                                             registered(7L, "Seventh", true, false));
    when(emailFolderStorage.countEnabledFolders(USER)).thenReturn(1L);

    EmailFolder enabled = emailFolderService.setSyncEnabled(USER, 7L, true);
    assertTrue(enabled.isSyncEnabled());
    verify(emailFolderStorage).updateSyncEnabled(eq(USER), eq(7L), eq(true), any(Date.class));

    when(emailFolderStorage.getFolder(USER, 9L)).thenReturn(registered(9L, "Ninth", true, false));
    emailFolderService.setSyncEnabled(USER, 9L, false);
    verify(emailFolderStorage).updateSyncEnabled(eq(USER), eq(9L), eq(false), any(Date.class));
  }

  /**
   * An id that is not this user's, or a key that is not a custom key, is a refusal
   * with the unknown-folder message -- the 400 the REST answers, never an inbox read.
   */
  @Test
  void anUnknownFolderIsRefusedNotDefaulted() {
    when(emailFolderStorage.getFolder(USER, 99L)).thenReturn(null);
    assertEquals(EmailFolderService.UNKNOWN_FOLDER_MESSAGE,
                 assertThrows(IllegalArgumentException.class, () -> emailFolderService.getFolder(USER, 99L)).getMessage());
    assertEquals(EmailFolderService.UNKNOWN_FOLDER_MESSAGE,
                 assertThrows(IllegalArgumentException.class, () -> emailFolderService.getFolderByKey(USER, "CUSTOM:99")).getMessage());
    assertEquals(EmailFolderService.UNKNOWN_FOLDER_MESSAGE,
                 assertThrows(IllegalArgumentException.class, () -> emailFolderService.getFolderByKey(USER, MailFolder.SENT)).getMessage());
    verify(emailFolderStorage, never()).getFolder(USER, 0L);
  }

  /**
   * The rotation: of twelve enabled folders under a cap of ten, the two newest opt-ins
   * are ignored; of the ten, the five least recently checked go first, the
   * never-checked ones before all.
   */
  @Test
  void thePickIsTheOldestOptInsWithinTheCapThenTheLeastRecentlyCheckedWithinTheBudget() {
    List<EmailFolder> enabledOldestFirst = new ArrayList<>();
    for (long id = 1; id <= 12; id++) {
      EmailFolder folder = registered(id, "F" + id, true, false);
      // Checked at a time that runs backwards with the id, so the oldest opt-in is the
      // most recently checked: the two orders must not be confused.
      folder.setLastSyncDate(id == 3 || id == 5 ? null : new Date(100_000L - id * 1_000L));
      enabledOldestFirst.add(folder);
    }
    when(emailFolderStorage.getEnabledFolders(USER)).thenReturn(enabledOldestFirst);

    List<Long> picked = emailFolderService.pickFoldersToSync(USER).stream().map(EmailFolder::getId).toList();

    // Never checked first (3, 5), then the least recently checked among ids 1..10
    // (the highest ids, since the check time runs backwards): 10, 9, 8. Never 11 or 12.
    assertEquals(List.of(3L, 5L, 10L, 9L, 8L), picked);
  }

  /**
   * The budget follows its property, and a check is recorded whether or not a
   * snapshot was captured.
   */
  @Test
  void theBudgetIsTunableAndASkipIsRecordedAsACheck() {
    System.setProperty(EmailFolderService.CUSTOM_FOLDERS_PER_CYCLE_PROPERTY, "2");
    when(emailFolderStorage.getEnabledFolders(USER)).thenReturn(List.of(registered(1L, "A", true, false),
                                                                        registered(2L, "B", true, false),
                                                                        registered(3L, "C", true, false)));
    assertEquals(2, emailFolderService.pickFoldersToSync(USER).size());

    emailFolderService.recordSync(USER, 1L, null);
    verify(emailFolderStorage).updateSyncMemory(eq(USER), eq(1L), isNull(), any(Date.class));
    FolderSyncSnapshot snapshot = new FolderSyncSnapshot(1L, 2L, 3L, 4L, 50);
    emailFolderService.recordSync(USER, 2L, snapshot);
    verify(emailFolderStorage).updateSyncMemory(eq(USER), eq(2L), eq(snapshot), any(Date.class));
  }

  // ---------------------------------------------------------------------------------
  // Staleness and discovery cadence
  // ---------------------------------------------------------------------------------

  /**
   * Stale means: opted in, present, and not checked within the user's own sync period
   * -- or within the property when an administrator set one. Never checked is stale;
   * not opted in never is.
   */
  @Test
  void staleFollowsTheUsersPeriodUnlessAnAdministratorSetOne() {
    long now = TimeUnit.HOURS.toMillis(100);
    EmailFolder never = registered(1L, "Never", true, false);
    assertTrue(emailFolderService.isStale(never, 10, now));

    EmailFolder fresh = registered(2L, "Fresh", true, false);
    fresh.setLastSyncDate(new Date(now - TimeUnit.MINUTES.toMillis(5)));
    assertFalse(emailFolderService.isStale(fresh, 10, now));

    EmailFolder old = registered(3L, "Old", true, false);
    old.setLastSyncDate(new Date(now - TimeUnit.MINUTES.toMillis(11)));
    assertTrue(emailFolderService.isStale(old, 10, now));

    // The zero default is a RESOLUTION, not a number: it must read the user's period,
    // never "always stale" (an IMAP round-trip per click) nor "never stale" (no
    // on-open refresh at all). Both ends of that boundary, at the period itself.
    EmailFolder atThePeriod = registered(6L, "AtThePeriod", true, false);
    atThePeriod.setLastSyncDate(new Date(now - TimeUnit.MINUTES.toMillis(10)));
    assertTrue(emailFolderService.isStale(atThePeriod, 10, now), "exactly one period old is stale");
    atThePeriod.setLastSyncDate(new Date(now - TimeUnit.MINUTES.toMillis(10) + 1));
    assertFalse(emailFolderService.isStale(atThePeriod, 10, now), "a moment younger than one period is not");
    EmailFolder justChecked = registered(7L, "JustChecked", true, false);
    justChecked.setLastSyncDate(new Date(now));
    assertFalse(emailFolderService.isStale(justChecked, 10, now), "zero is not \"always stale\"");
    assertFalse(emailFolderService.isStale(justChecked, 0, now), "and a zero period resolves to a minute, not to always");

    System.setProperty(EmailFolderService.CUSTOM_FOLDERS_STALE_MINUTES_PROPERTY, "30");
    assertFalse(emailFolderService.isStale(old, 10, now), "the property overrides the period");

    EmailFolder off = registered(4L, "Off", false, false);
    assertFalse(emailFolderService.isStale(off, 10, now));
    EmailFolder missing = registered(5L, "Missing", true, true);
    assertFalse(emailFolderService.isStale(missing, 10, now));
  }

  /**
   * The routine walk is due on a mailbox never walked, and then once a day.
   */
  @Test
  void theWalkIsDueOnceADay() {
    long now = TimeUnit.DAYS.toMillis(10);
    assertTrue(emailFolderService.isDiscoveryDue(null, now));
    assertFalse(emailFolderService.isDiscoveryDue(now - TimeUnit.HOURS.toMillis(1), now));
    assertTrue(emailFolderService.isDiscoveryDue(now - TimeUnit.HOURS.toMillis(25), now));
    System.setProperty(EmailFolderService.CUSTOM_FOLDERS_DISCOVERY_HOURS_PROPERTY, "1");
    assertTrue(emailFolderService.isDiscoveryDue(now - TimeUnit.HOURS.toMillis(1), now));
  }

  /**
   * A selectable folder as a walk reports it, with '/' as its separator.
   *
   * @param fullName the IMAP full name
   * @param subscribed whether it was in the subscribed listing
   * @param attributes the LIST attributes
   * @return the descriptor
   */
  private DiscoveredFolder folder(String fullName, boolean subscribed, String... attributes) {
    return new DiscoveredFolder(fullName,
                                fullName.substring(fullName.lastIndexOf('/') + 1),
                                "/",
                                Set.of(attributes),
                                subscribed,
                                true);
  }

  // ---------------------------------------------------------------------------------
  // Name validation (EXO-89943)
  // ---------------------------------------------------------------------------------

  /**
   * A valid name is trimmed and handed back; nothing is refused for it.
   */
  @Test
  void aValidNameIsTrimmedAndAccepted() {
    assertEquals("Invoices", emailFolderService.validateFolderName("  Invoices  "));
  }

  /**
   * Blank, empty and whitespace-only names are all refused with the same code.
   */
  @Test
  void blankAndWhitespaceOnlyNamesAreRefused() {
    for (String blank : List.of("", "   ", "\t\n ")) {
      assertEquals(EmailFolderService.FOLDER_NAME_BLANK_MESSAGE,
                   assertThrows(IllegalArgumentException.class, () -> emailFolderService.validateFolderName(blank)).getMessage());
    }
    assertEquals(EmailFolderService.FOLDER_NAME_BLANK_MESSAGE,
                 assertThrows(IllegalArgumentException.class, () -> emailFolderService.validateFolderName(null)).getMessage());
  }

  /**
   * A name past {@link EmailFolderService#MAX_FOLDER_NAME_LENGTH} is refused; the
   * bound itself is accepted, and a name only too long once its surrounding
   * whitespace is trimmed is accepted too (the check runs on the trimmed name).
   */
  @Test
  void aNamePastTheLengthBoundIsRefused() {
    String atTheBound = "x".repeat(EmailFolderService.MAX_FOLDER_NAME_LENGTH);
    assertEquals(atTheBound, emailFolderService.validateFolderName(atTheBound));
    String oneOver = "x".repeat(EmailFolderService.MAX_FOLDER_NAME_LENGTH + 1);
    assertEquals(EmailFolderService.FOLDER_NAME_TOO_LONG_MESSAGE,
                 assertThrows(IllegalArgumentException.class, () -> emailFolderService.validateFolderName(oneOver)).getMessage());
    assertEquals(atTheBound, emailFolderService.validateFolderName("  " + atTheBound + "  "));
  }

  /**
   * A name starting with a provider's reserved namespace bracket ({@code [Gmail]/...})
   * is refused, whatever comes after the bracket.
   */
  @Test
  void aNameStartingWithTheReservedNamespaceBracketIsRefused() {
    for (String reserved : List.of("[Gmail]", "[Gmail]/Custom", "[Work]")) {
      assertEquals(EmailFolderService.FOLDER_NAME_RESERVED_MESSAGE,
                   assertThrows(IllegalArgumentException.class, () -> emailFolderService.validateFolderName(reserved))
                                                                                                                      .getMessage());
    }
  }

  /**
   * A name equal, case-insensitively, to one of the STRICT built-in name sets is
   * refused: created today, it would sit as a plain custom row and could be handed
   * the built-in role at the next walk (see {@link EmailFolderService#FOLDER_NAME_RESERVED_MESSAGE}).
   * The loose Sent/Archive/All-Mail rules are deliberately NOT enforced -- a name
   * merely containing "sent" is accepted.
   */
  @Test
  void aNameEqualToAStrictBuiltInNameIsRefusedButALooseOneIsNot() {
    for (String reserved : List.of("inbox", "INBOX", "Archive", "archives", "Trash", "trash", "Deleted Items",
                                   "Drafts", "Brouillons", "Spam", "Junk E-mail", "Courrier indésirable")) {
      assertEquals(EmailFolderService.FOLDER_NAME_RESERVED_MESSAGE,
                   assertThrows(IllegalArgumentException.class, () -> emailFolderService.validateFolderName(reserved))
                                                                                                                      .getMessage(),
                   reserved);
    }
    // "Consent forms" contains "sent"; the loose Sent rule is a pre-existing,
    // accepted fragility of DISCOVERY -- not one this create/rename feature adds.
    assertEquals("Consent forms", emailFolderService.validateFolderName("Consent forms"));
  }

  /**
   * The delimiter check is a separate call: a name embedding the mailbox's own
   * separator is refused, a blank/null delimiter (a flat namespace) admits every
   * name.
   */
  @Test
  void checkNotNestedRefusesANameEmbeddingTheDelimiterOnly() {
    IllegalArgumentException nested = assertThrows(IllegalArgumentException.class,
                                                   () -> emailFolderService.checkNotNested("Customers/Acme", "/"));
    assertEquals(EmailFolderService.FOLDER_NAME_NESTED_MESSAGE, nested.getMessage());
    assertDoesNotThrow(() -> emailFolderService.checkNotNested("Customers/Acme", null));
    assertDoesNotThrow(() -> emailFolderService.checkNotNested("Customers/Acme", ""));
    assertDoesNotThrow(() -> emailFolderService.checkNotNested("Customers.Acme", "/"), "a different separator is not embedded");
  }

  /**
   * The parent prefix a rename adds the new segment onto: everything up to and
   * including the last delimiter, empty on a top-level folder or a flat namespace.
   */
  @Test
  void parentPrefixIsEverythingUpToTheLastDelimiter() {
    assertEquals("Customers/", EmailFolderService.parentPrefix("Customers/Acme", "/"));
    assertEquals("", EmailFolderService.parentPrefix("Invoices", "/"), "top-level: no delimiter found");
    assertEquals("", EmailFolderService.parentPrefix("Invoices", null), "flat namespace");
    assertEquals("A/B/", EmailFolderService.parentPrefix("A/B/C", "/"), "only the LAST delimiter counts");
  }

  // ---------------------------------------------------------------------------------
  // Duplicate check, create/rename/delete (EXO-89943)
  // ---------------------------------------------------------------------------------

  /**
   * A name already registered for this user is refused; a name differing only by case
   * is a DIFFERENT name (the registry's own case-sensitive collation); excluding the
   * row's own id admits a rename that keeps its own name.
   */
  @Test
  void checkNameAvailableIsCaseSensitiveAndExcludesTheRowsOwnId() {
    when(emailFolderStorage.getFolderByRemoteName(USER, "Factures")).thenReturn(registered(5L, "Factures", true, false));
    assertThrows(IllegalArgumentException.class, () -> emailFolderService.checkNameAvailable(USER, "Factures", null));
    assertThrows(IllegalArgumentException.class, () -> emailFolderService.checkNameAvailable(USER, "Factures", 6L));
    assertDoesNotThrow(() -> emailFolderService.checkNameAvailable(USER, "Factures", 5L), "excluded: it is its own row");

    when(emailFolderStorage.getFolderByRemoteName(USER, "factures")).thenReturn(null);
    assertDoesNotThrow(() -> emailFolderService.checkNameAvailable(USER, "factures", null), "different case, different name");
  }

  /**
   * A create registers the row directly through the storage, opt-in off whatever the
   * caller passes -- {@link EmailFolderStorage#createFolder} owns that invariant, and
   * a create relies on it exactly as discovery does.
   */
  @Test
  void registerCreatedFolderWritesTheRowThroughStorage() {
    EmailFolder stored = registered(9L, "Invoices", false, false);
    when(emailFolderStorage.createFolder(any())).thenReturn(stored);

    EmailFolder result = emailFolderService.registerCreatedFolder(USER, "Invoices", "/");

    ArgumentCaptor<EmailFolder> captor = ArgumentCaptor.forClass(EmailFolder.class);
    verify(emailFolderStorage).createFolder(captor.capture());
    assertEquals(USER, captor.getValue().getUserId());
    assertEquals("Invoices", captor.getValue().getRemoteName());
    assertEquals("Invoices", captor.getValue().getDisplayName());
    assertEquals("/", captor.getValue().getDelimiter());
    assertEquals(MailFolderView.TYPE_CUSTOM, captor.getValue().getType());
    assertSame(stored, result);
  }

  /**
   * Auto-enable succeeds when the cap has room, and leaves the folder disabled --
   * without throwing -- when it does not.
   */
  @Test
  void tryAutoEnableSucceedsWithRoomAndFailsQuietlyAtTheCap() {
    when(emailFolderStorage.getFolder(USER, 9L)).thenReturn(registered(9L, "Invoices", false, false));
    when(emailFolderStorage.countEnabledFolders(USER)).thenReturn(0L);
    assertTrue(emailFolderService.tryAutoEnable(USER, 9L));
    verify(emailFolderStorage).updateSyncEnabled(eq(USER), eq(9L), eq(true), any(Date.class));

    when(emailFolderStorage.getFolder(USER, 10L)).thenReturn(registered(10L, "Projets", false, false));
    when(emailFolderStorage.countEnabledFolders(USER)).thenReturn(10L);
    assertFalse(emailFolderService.tryAutoEnable(USER, 10L));
    verify(emailFolderStorage, never()).updateSyncEnabled(eq(USER), eq(10L), anyBoolean(), any());
  }

  /**
   * A rename delegates straight to the storage's own targeted write -- the same
   * two-column UPDATE {@link EmailFolderDAOTest} pins against a real database.
   */
  @Test
  void renameFolderDelegatesToStorage() {
    EmailFolder renamed = registered(5L, "Invoices", true, false);
    when(emailFolderStorage.renameFolder(USER, 5L, "Invoices", "Invoices")).thenReturn(renamed);

    EmailFolder result = emailFolderService.renameFolder(USER, 5L, "Invoices", "Invoices");

    assertSame(renamed, result);
  }

  /**
   * A delete delegates straight to the storage; the mirrored rows are the caller's to
   * clear first, exactly as {@link #aWalkRegistersRefreshesGracesThenDeletes} already
   * documents for the discovery-side deletion.
   */
  @Test
  void removeFolderDelegatesToStorage() {
    emailFolderService.removeFolder(USER, 5L);
    verify(emailFolderStorage).deleteFolder(USER, 5L);
  }

  /**
   * A registered folder.
   *
   * @param id the registry id
   * @param remoteName the IMAP full name
   * @param enabled the opt-in
   * @param missing whether the last walk missed it
   * @return the DTO
   */
  private EmailFolder registered(long id, String remoteName, boolean enabled, boolean missing) {
    EmailFolder folder = new EmailFolder();
    folder.setId(id);
    folder.setUserId(USER);
    folder.setRemoteName(remoteName);
    folder.setDisplayName(remoteName);
    folder.setDelimiter("/");
    folder.setType("CUSTOM");
    folder.setSyncEnabled(enabled);
    folder.setEnabledDate(enabled ? new Date(id * 1_000L) : null);
    folder.setMissing(missing);
    return folder;
  }
}
