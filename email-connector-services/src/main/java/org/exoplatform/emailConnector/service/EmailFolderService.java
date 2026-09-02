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

import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;

import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import org.exoplatform.emailConnector.model.DiscoveredFolder;
import org.exoplatform.emailConnector.model.EmailFolder;
import org.exoplatform.emailConnector.model.FolderClassification;
import org.exoplatform.emailConnector.model.FolderSyncSnapshot;
import org.exoplatform.emailConnector.model.MailFolder;
import org.exoplatform.emailConnector.model.MailFolderView;
import org.exoplatform.emailConnector.storage.EmailFolderStorage;
import org.exoplatform.services.log.ExoLogger;
import org.exoplatform.services.log.Log;

/**
 * The user's own mail folders: which of the folders a walk of their mailbox found are
 * the built-in seven and which are theirs, the registry of the latter, the opt-in that
 * decides which of them are mirrored, and the four rules that keep mirroring them from
 * making the mailbox slower to refresh.
 * <p>
 * The IMAP walk itself is not here -- {@code EmailBoxService} owns the connection and
 * runs it -- but everything decided about its result is, in one place: the
 * classification, so a seventh special-use folder is a constant plus a rule here and
 * not a search through the sync; the cap and the budget, so the numbers an
 * administrator tunes are read from one method each; the rotation, so "which folders
 * this cycle" has one answer.
 * <p>
 * <b>The cost rules, and why each exists.</b> Mirroring a folder costs two IMAP
 * round-trips per cycle when it has not changed and a window download when it has, for
 * every user, every period, forever. A mailbox may hold forty folders and a Gmail label
 * is a folder whose messages are already in the inbox. So:
 * <ol>
 * <li><b>opt-in, default off</b> -- nothing is mirrored for a folder the user never
 * chose, and only the user knows which of their forty matter;</li>
 * <li><b>a cap</b> ({@link #CUSTOM_FOLDERS_MAX_PROPERTY}) on how many they may choose,
 * refused at opt-in time with a message the screen can show;</li>
 * <li><b>a small window</b> ({@link #CUSTOM_FOLDER_SYNC_LIMIT}) per folder -- a
 * recent-activity mirror, not a copy; at the cap, five hundred rows per user against
 * an inbox window of a thousand;</li>
 * <li><b>a per-cycle budget</b> ({@link #CUSTOM_FOLDERS_PER_CYCLE_PROPERTY}) with
 * least-recently-checked rotation, so a cycle checks at most that many folders and
 * every enabled folder is checked at least every {@code ceil(enabled / budget)}
 * cycles. At the defaults that is ten round-trips per cycle when nothing changed,
 * which is what a cycle already costs for the built-ins.</li>
 * </ol>
 * The fifth rule is the complement of the fourth: a folder opened while it is stale is
 * refreshed on the request thread of the user who asked, never for users who are not
 * looking ({@link #isStale}).
 */
@Service
public class EmailFolderService {

  private static final Log        LOG                                = ExoLogger.getLogger(EmailFolderService.class);

  /**
   * How many of a custom folder's most recent messages are mirrored -- the same kind
   * of number as {@code TRASH_FOLDER_SYNC_LIMIT} and subject to the same warning:
   * the window size is baked into every {@link FolderSyncSnapshot} at capture, so
   * changing it forces a full re-download of every mirrored custom folder of every
   * mailbox. Fifty rather than the hundred Sent and Archive get because a folder the
   * user files into is, by definition, consulted less than the inbox, and the number
   * is multiplied by the cap: at ten folders this is five hundred bodies per user.
   */
  public static final int         CUSTOM_FOLDER_SYNC_LIMIT           = 50;

  /**
   * The master switch: whether custom folders are discovered, mirrored and offered at
   * all. Read hot, like the Trash and Junk switches, so an administrator can withdraw
   * the feature without a restart.
   */
  public static final String      CUSTOM_FOLDERS_ENABLED_PROPERTY    = "email.connector.customFolders.enabled";

  /** The cap: how many custom folders one user may opt in. Hot. */
  public static final String      CUSTOM_FOLDERS_MAX_PROPERTY        = "email.connector.customFolders.max";

  /** The budget: how many custom folders one sync cycle checks. Hot. */
  public static final String      CUSTOM_FOLDERS_PER_CYCLE_PROPERTY  = "email.connector.customFolders.perCycle";

  /**
   * How old a folder's last check may be before opening it triggers a refresh, in
   * minutes. Zero (the default) means the user's own sync period: a folder checked
   * within the last period is what the routine sync would have shown anyway. Hot.
   */
  public static final String      CUSTOM_FOLDERS_STALE_MINUTES_PROPERTY = "email.connector.customFolders.staleMinutes";

  /**
   * How often the routine sync re-walks the whole folder list, in hours, so a folder
   * created elsewhere appears here without the user asking. The walk is a
   * {@code LIST *} pair, paid per user; once a day is the trade between "shows up
   * today" and "not on every period". Hot.
   */
  public static final String      CUSTOM_FOLDERS_DISCOVERY_HOURS_PROPERTY = "email.connector.customFolders.discoveryHours";

  static final int                DEFAULT_MAX_CUSTOM_FOLDERS         = 10;

  static final int                DEFAULT_PER_CYCLE_BUDGET           = 5;

  static final int                DEFAULT_DISCOVERY_HOURS            = 24;

  /** The message code a refused opt-in beyond the cap carries; the screen shows it. */
  public static final String      TOO_MANY_FOLDERS_MESSAGE           = "emailConnector.folder.tooMany";

  /** The message code an unknown folder id or key carries -- a 400, never INBOX. */
  public static final String      UNKNOWN_FOLDER_MESSAGE             = "emailConnector.folder.unknown";

  // The RFC 6154 SPECIAL-USE attributes, one per built-in role. The server saying which
  // folder plays a role beats any name we could guess, which is why every role is
  // assigned by attribute across the whole listing before any name is looked at.
  static final String             SENT_ATTRIBUTE                     = "\\Sent";

  static final String             ARCHIVE_ATTRIBUTE                  = "\\Archive";

  static final String             DRAFTS_ATTRIBUTE                   = "\\Drafts";

  static final String             TRASH_ATTRIBUTE                    = "\\Trash";

  static final String             JUNK_ATTRIBUTE                     = "\\Junk";

  static final String             ALL_ATTRIBUTE                      = "\\All";

  // The attributes that take a folder out of consideration entirely. \Noselect and
  // \NonExistent name hierarchy nodes that cannot be opened (Gmail's "[Gmail]" parent,
  // Cyrus's intermediate nodes). \Flagged and \Important name Gmail's Starred and
  // Important VIEWS: virtual folders over the inbox, and mirroring either would cache
  // the inbox a second time under another key.
  static final Set<String>        IGNORED_ATTRIBUTES                 =
                                                     Set.of("\\noselect", "\\nonexistent", "\\flagged", "\\important");

  // The well-known Drafts folder names, for the servers that never learned SPECIAL-USE,
  // in the locales the product ships plus the few its users' other clients create.
  // Matched on the folder's last path segment, for equality -- see the classification
  // for why this list is not applied as a "contains".
  static final Set<String>        DRAFTS_FOLDER_NAMES                =
                                                      Set.of("drafts",
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

  // The well-known Trash folder names, same spread of locales plus the "Deleted ..."
  // names Exchange and its clients create. Last path segment, for equality.
  static final Set<String>        TRASH_FOLDER_NAMES                 =
                                                     Set.of("trash",
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

  // The well-known Junk folder names, same spread plus the "Spam" / "Bulk" names the
  // big providers and their clients create. Last path segment, for equality.
  static final Set<String>        JUNK_FOLDER_NAMES                  =
                                                    Set.of("junk",
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

  // The built-in roles in the order they are assigned. INBOX first because its name is
  // protocol-guaranteed; the rest in the order the sync runs them. Order matters only
  // when one folder could fill two roles, and then the earlier role takes it.
  private static final List<String> BUILT_IN_ROLES                   =
                                                 List.of(MailFolder.INBOX,
                                                         MailFolder.SENT,
                                                         MailFolder.ARCHIVE,
                                                         MailFolder.DRAFTS,
                                                         MailFolder.TRASH,
                                                         MailFolder.JUNK,
                                                         MailFolder.ALL_MAIL);

  @Autowired
  private EmailFolderStorage      emailFolderStorage;

  /**
   * Whether custom folders are switched on at all -- see
   * {@link #CUSTOM_FOLDERS_ENABLED_PROPERTY}.
   *
   * @return true when custom folders are discovered, mirrored and offered
   */
  public boolean isCustomFoldersEnabled() {
    return Boolean.parseBoolean(System.getProperty(CUSTOM_FOLDERS_ENABLED_PROPERTY, "true"));
  }

  /**
   * The cap -- see {@link #CUSTOM_FOLDERS_MAX_PROPERTY}. Never below zero; a
   * misconfigured value falls back to the default rather than switching the feature
   * off by accident.
   *
   * @return how many custom folders one user may opt in
   */
  public int getMaxCustomFolders() {
    return positiveIntProperty(CUSTOM_FOLDERS_MAX_PROPERTY, DEFAULT_MAX_CUSTOM_FOLDERS);
  }

  /**
   * The per-cycle budget -- see {@link #CUSTOM_FOLDERS_PER_CYCLE_PROPERTY}.
   *
   * @return how many custom folders one sync cycle checks, at least one
   */
  public int getPerCycleBudget() {
    return Math.max(1, positiveIntProperty(CUSTOM_FOLDERS_PER_CYCLE_PROPERTY, DEFAULT_PER_CYCLE_BUDGET));
  }

  /**
   * The mirror window of every custom folder.
   *
   * @return {@link #CUSTOM_FOLDER_SYNC_LIMIT}
   */
  public int getWindowSize() {
    return CUSTOM_FOLDER_SYNC_LIMIT;
  }

  /**
   * Whether the routine sync should walk the whole folder list again -- see
   * {@link #CUSTOM_FOLDERS_DISCOVERY_HOURS_PROPERTY}. A mailbox never walked is due.
   *
   * @param lastDiscoveryMillis when the list was last walked, or null for never
   * @param nowMillis the current time
   * @return true when a walk is due
   */
  public boolean isDiscoveryDue(Long lastDiscoveryMillis, long nowMillis) {
    if (lastDiscoveryMillis == null) {
      return true;
    }
    long hours = positiveIntProperty(CUSTOM_FOLDERS_DISCOVERY_HOURS_PROPERTY, DEFAULT_DISCOVERY_HOURS);
    return nowMillis - lastDiscoveryMillis >= TimeUnit.HOURS.toMillis(hours);
  }

  /**
   * Whether opening a folder should refresh it first -- see
   * {@link #CUSTOM_FOLDERS_STALE_MINUTES_PROPERTY}. A folder never checked is stale;
   * a folder not opted in, or missing, never is (there is nothing to refresh).
   *
   * @param folder the registered folder
   * @param userSyncPeriodMinutes the user's own sync period, the threshold when the
   *          property is zero
   * @param nowMillis the current time
   * @return true when a refresh is worth running before answering
   */
  public boolean isStale(EmailFolder folder, int userSyncPeriodMinutes, long nowMillis) {
    if (folder == null || !folder.isSyncEnabled() || folder.isMissing()) {
      return false;
    }
    if (folder.getLastSyncDate() == null) {
      return true;
    }
    int staleMinutes = positiveIntProperty(CUSTOM_FOLDERS_STALE_MINUTES_PROPERTY, 0);
    long threshold = TimeUnit.MINUTES.toMillis(staleMinutes > 0 ? staleMinutes : Math.max(1, userSyncPeriodMinutes));
    return nowMillis - folder.getLastSyncDate().getTime() >= threshold;
  }

  /**
   * Sorts one walk of the folder list into the built-in roles and the user's own
   * folders. First match wins, in this order:
   * <ol>
   * <li>a folder carrying an ignored attribute ({@link #IGNORED_ATTRIBUTES}) or that
   * cannot be opened is dropped -- it is neither a role nor the user's;</li>
   * <li>every role is filled by its SPECIAL-USE attribute across the WHOLE listing
   * before any name is looked at. The attribute is the server telling us which folder
   * this is; the name is us guessing -- so an attribute found later in the listing
   * still beats a name matched earlier, which is the rule the Drafts, Trash and Junk
   * discovery already followed and which the loose Sent and Archive walkers, being
   * one loop each, could not;</li>
   * <li>the roles still empty are filled by name, with EXACTLY the rule each had
   * before: Sent by a loose {@code contains} on the full name, Archive by equality on
   * the full lowercased name, All Mail by {@code contains}, Drafts / Trash / Junk by
   * last-segment equality against their known-name sets, INBOX by its own name. No
   * mailbox changes which folder it syncs as what;</li>
   * <li>subscribed folders are considered before unsubscribed ones at every step, so
   * a subscribed Sent beats an unsubscribed twin; a role found only among the
   * unsubscribed still counts, as it already did for Drafts, Trash and Junk;</li>
   * <li>everything selectable that is left is the user's own.</li>
   * </ol>
   * A folder fills at most one role, and a folder that fills one is never custom --
   * the French "Courrier indésirable" without a {@code \Junk} attribute is the Junk
   * folder, not a folder named that.
   *
   * @param folders the walk's result, subscribed and unsubscribed alike
   * @return the classification, never null
   */
  public FolderClassification classify(List<DiscoveredFolder> folders) {
    List<DiscoveredFolder> candidates = new ArrayList<>();
    for (DiscoveredFolder folder : folders == null ? List.<DiscoveredFolder> of() : folders) {
      if (folder == null || !folder.selectable() || isIgnored(folder)) {
        continue;
      }
      candidates.add(folder);
    }
    // Subscribed first, listing order kept within each half.
    candidates.sort(Comparator.comparing((DiscoveredFolder folder) -> !folder.subscribed()));
    Map<String, DiscoveredFolder> builtIns = new HashMap<>();
    Set<DiscoveredFolder> assigned = new HashSet<>();
    for (String role : BUILT_IN_ROLES) {
      assign(role, candidates, builtIns, assigned, folder -> matchesByAttribute(role, folder));
    }
    for (String role : BUILT_IN_ROLES) {
      if (!builtIns.containsKey(role)) {
        assign(role, candidates, builtIns, assigned, folder -> matchesByName(role, folder));
      }
    }
    // A folder without a name can fill a role by attribute; it can never be the user's,
    // there being nothing to show for it.
    List<DiscoveredFolder> customs = candidates.stream()
                                               .filter(folder -> !assigned.contains(folder))
                                               .filter(folder -> StringUtils.isNotBlank(folder.fullName()))
                                               .toList();
    return new FolderClassification(builtIns, customs);
  }

  /**
   * Reconciles the registry with what a walk found. New folders are registered
   * (opt-in off); folders seen again are refreshed and un-missed; folders not seen
   * are marked missing and, if they were missing already, deleted -- one grace walk,
   * because the walk that misses a folder may be the one that ran while the user was
   * moving it, and because a rename is indistinguishable from a delete-plus-create
   * from here.
   * <p>
   * The rows a deleted folder mirrored are NOT deleted here: that is
   * {@code EmailBoxService}'s to do, with the category links it keeps, which is why
   * the deleted folders are handed back to it.
   *
   * @param username the mailbox owner
   * @param customs the walk's custom candidates
   * @return the folders deleted by this reconciliation, whose mirrored rows the caller
   *         must now delete; never null
   */
  public List<EmailFolder> reconcileDiscovered(String username, List<DiscoveredFolder> customs) {
    Date now = new Date();
    Set<String> seenNames = new HashSet<>();
    for (DiscoveredFolder discovered : customs == null ? List.<DiscoveredFolder> of() : customs) {
      seenNames.add(discovered.fullName());
      try {
        EmailFolder existing = emailFolderStorage.getFolderByRemoteName(username, discovered.fullName());
        if (existing == null) {
          EmailFolder folder = new EmailFolder();
          folder.setUserId(username);
          folder.setRemoteName(discovered.fullName());
          folder.setDisplayName(displayNameOf(discovered));
          folder.setDelimiter(discovered.delimiter());
          folder.setType(MailFolderView.TYPE_CUSTOM);
          folder.setDiscoveredDate(now);
          folder.setLastSeenDate(now);
          emailFolderStorage.createFolder(folder);
        } else {
          emailFolderStorage.updateDiscovery(username,
                                             existing.getId(),
                                             displayNameOf(discovered),
                                             discovered.delimiter(),
                                             false,
                                             now);
        }
      } catch (Exception e) {
        // One folder's row failing (a name the database collates onto another's, say)
        // must not cost the user the rest of their list.
        LOG.warn("Could not register folder '{}' of user {}", discovered.fullName(), username, e);
      }
    }
    List<EmailFolder> purged = new ArrayList<>();
    for (EmailFolder registered : emailFolderStorage.getFolders(username)) {
      if (seenNames.contains(registered.getRemoteName())) {
        continue;
      }
      if (registered.isMissing()) {
        emailFolderStorage.deleteFolder(username, registered.getId());
        purged.add(registered);
      } else {
        emailFolderStorage.updateDiscovery(username, registered.getId(), null, null, true, null);
      }
    }
    return purged;
  }

  /**
   * Every registered folder of a mailbox, missing ones included.
   *
   * @param username the mailbox owner
   * @return the folders, by display name, never null
   */
  public List<EmailFolder> getFolders(String username) {
    return emailFolderStorage.getFolders(username);
  }

  /**
   * One registered folder of a mailbox, by id.
   *
   * @param username the mailbox owner
   * @param id the registry id
   * @return the folder, never null
   * @throws IllegalArgumentException if no such folder belongs to that user
   */
  public EmailFolder getFolder(String username, long id) {
    EmailFolder folder = emailFolderStorage.getFolder(username, id);
    if (folder == null) {
      throw new IllegalArgumentException(UNKNOWN_FOLDER_MESSAGE);
    }
    return folder;
  }

  /**
   * One registered folder of a mailbox, by the key its mirrored rows carry.
   *
   * @param username the mailbox owner
   * @param key the {@code CUSTOM:<id>} discriminator
   * @return the folder, never null
   * @throws IllegalArgumentException if the key is not a custom key, or names no
   *           folder of that user -- a 400, never a silent fallback to the inbox
   */
  public EmailFolder getFolderByKey(String username, String key) {
    return getFolder(username, MailFolder.customId(key));
  }

  /**
   * The user's opt-in for one folder. Enabling is refused beyond the cap
   * ({@link #TOO_MANY_FOLDERS_MESSAGE}); enabling an already-enabled folder is a
   * no-op that does not count against it. Disabling clears the folder's sync memory;
   * deleting what it mirrored is the caller's job.
   *
   * @param username the mailbox owner
   * @param id the registry id
   * @param enabled the new opt-in
   * @return the folder as it now stands
   * @throws IllegalArgumentException if the folder is unknown, or the cap is reached
   */
  public EmailFolder setSyncEnabled(String username, long id, boolean enabled) {
    EmailFolder folder = getFolder(username, id);
    if (folder.isSyncEnabled() == enabled) {
      return folder;
    }
    if (enabled && emailFolderStorage.countEnabledFolders(username) >= getMaxCustomFolders()) {
      throw new IllegalArgumentException(TOO_MANY_FOLDERS_MESSAGE);
    }
    emailFolderStorage.updateSyncEnabled(username, id, enabled, new Date());
    return getFolder(username, id);
  }

  /**
   * The folders one sync cycle checks: of the enabled, present folders, the oldest
   * opt-ins up to the cap (so an administrator lowering the cap under what a user
   * already enabled ignores the newest, deterministically, and deletes nothing), and
   * of those, the least recently checked up to the budget -- never-checked ones first.
   * With ten folders and a budget of five every folder is checked every second cycle.
   *
   * @param username the mailbox owner
   * @return the folders to check this cycle, least recently checked first
   */
  public List<EmailFolder> pickFoldersToSync(String username) {
    List<EmailFolder> withinCap = emailFolderStorage.getEnabledFolders(username)
                                                    .stream()
                                                    .limit(getMaxCustomFolders())
                                                    .toList();
    return withinCap.stream()
                    .sorted(Comparator.comparing(EmailFolder::getLastSyncDate,
                                                 Comparator.nullsFirst(Comparator.naturalOrder())))
                    .limit(getPerCycleBudget())
                    .toList();
  }

  /**
   * Records that a folder was checked this cycle, with the snapshot the check captured
   * when it ran the full sync. A skip is a check too: the folder rotates to the back
   * either way.
   *
   * @param username the mailbox owner
   * @param id the registry id
   * @param snapshot the captured snapshot, or null when skipped or nothing captured
   */
  public void recordSync(String username, long id, FolderSyncSnapshot snapshot) {
    emailFolderStorage.updateSyncMemory(username, id, snapshot, new Date());
  }

  /**
   * Records that the sync could not find a folder the registry still lists -- the
   * same mark a discovery walk would put on it, so the next walk's grace rule applies.
   *
   * @param username the mailbox owner
   * @param id the registry id
   */
  public void markMissing(String username, long id) {
    emailFolderStorage.updateDiscovery(username, id, null, null, true, null);
  }

  /**
   * Drops every registered folder of a mailbox -- the disconnect / rebind wipe.
   *
   * @param username the mailbox owner
   */
  public void deleteFolders(String username) {
    emailFolderStorage.deleteFolders(username);
  }

  /**
   * Fills one role with the first candidate the test accepts that no role took yet.
   *
   * @param role the {@link MailFolder} constant
   * @param candidates the folders, subscribed first
   * @param builtIns the roles filled so far, updated in place
   * @param assigned the folders taken so far, updated in place
   * @param test the rule
   */
  private void assign(String role,
                      List<DiscoveredFolder> candidates,
                      Map<String, DiscoveredFolder> builtIns,
                      Set<DiscoveredFolder> assigned,
                      Predicate<DiscoveredFolder> test) {
    for (DiscoveredFolder folder : candidates) {
      if (!assigned.contains(folder) && test.test(folder)) {
        builtIns.put(role, folder);
        assigned.add(folder);
        return;
      }
    }
  }

  /**
   * Whether a walk result is one of the folders classification drops outright.
   *
   * @param folder the folder
   * @return true when it is neither a role nor the user's
   */
  private boolean isIgnored(DiscoveredFolder folder) {
    return folder.attributes() != null
        && folder.attributes().stream().anyMatch(attribute -> IGNORED_ATTRIBUTES.contains(attribute.toLowerCase()));
  }

  /**
   * The SPECIAL-USE test of a role.
   *
   * @param role the {@link MailFolder} constant
   * @param folder the folder
   * @return true when the server tagged the folder with the role's attribute
   */
  private boolean matchesByAttribute(String role, DiscoveredFolder folder) {
    return switch (role) {
      case MailFolder.SENT -> folder.hasAttribute(SENT_ATTRIBUTE);
      case MailFolder.ARCHIVE -> folder.hasAttribute(ARCHIVE_ATTRIBUTE);
      case MailFolder.DRAFTS -> folder.hasAttribute(DRAFTS_ATTRIBUTE);
      case MailFolder.TRASH -> folder.hasAttribute(TRASH_ATTRIBUTE);
      case MailFolder.JUNK -> folder.hasAttribute(JUNK_ATTRIBUTE);
      case MailFolder.ALL_MAIL -> folder.hasAttribute(ALL_ATTRIBUTE);
      default -> false;
    };
  }

  /**
   * The name test of a role -- each one exactly the rule its former walker applied.
   *
   * @param role the {@link MailFolder} constant
   * @param folder the folder
   * @return true when the folder's name says it plays the role
   */
  private boolean matchesByName(String role, DiscoveredFolder folder) {
    if (StringUtils.isBlank(folder.fullName())) {
      return false;
    }
    String name = folder.fullName().toLowerCase();
    return switch (role) {
      case MailFolder.INBOX -> "inbox".equals(name);
      case MailFolder.SENT -> name.contains("sent") || name.contains("envoyé") || name.contains("envoye");
      case MailFolder.ARCHIVE -> name.equals("archive") || name.equals("archives") || name.equals("archivage");
      case MailFolder.ALL_MAIL -> name.contains("all mail") || name.contains("tous les messages");
      case MailFolder.DRAFTS -> DRAFTS_FOLDER_NAMES.contains(lastSegment(folder.fullName()));
      case MailFolder.TRASH -> TRASH_FOLDER_NAMES.contains(lastSegment(folder.fullName()));
      case MailFolder.JUNK -> JUNK_FOLDER_NAMES.contains(lastSegment(folder.fullName()));
      default -> false;
    };
  }

  /**
   * A folder's last path segment, lowercased and trimmed, split on both separators
   * seen in the wild ('/' on Gmail and Dovecot's default, '.' on the Maildir++
   * layouts) rather than on the folder's own -- a pure string test, kept free of an
   * IMAP round-trip, as the former walkers did it.
   *
   * @param fullName the folder's full name
   * @return the last segment, lowercased
   */
  static String lastSegment(String fullName) {
    return fullName.substring(Math.max(fullName.lastIndexOf('/'), fullName.lastIndexOf('.')) + 1).trim().toLowerCase();
  }

  /**
   * What the interface shows for a custom folder: the display name the walk reported,
   * or, when the server gave none, the last segment of the full name -- as written,
   * never lowercased, never translated.
   *
   * @param folder the walk result
   * @return the display name, never blank
   */
  private String displayNameOf(DiscoveredFolder folder) {
    if (StringUtils.isNotBlank(folder.displayName())) {
      return StringUtils.abbreviate(folder.displayName(), 255);
    }
    String fullName = folder.fullName();
    int cut = StringUtils.isNotBlank(folder.delimiter()) ? fullName.lastIndexOf(folder.delimiter())
                                                          : Math.max(fullName.lastIndexOf('/'), fullName.lastIndexOf('.'));
    return StringUtils.abbreviate(fullName.substring(cut + 1), 255);
  }

  /**
   * A non-negative integer system property, or its default when absent or malformed.
   *
   * @param name the property
   * @param defaultValue the fallback
   * @return the value
   */
  private int positiveIntProperty(String name, int defaultValue) {
    try {
      int value = Integer.parseInt(System.getProperty(name, String.valueOf(defaultValue)).trim());
      return value < 0 ? defaultValue : value;
    } catch (NumberFormatException e) {
      LOG.warn("Property {} is not a number; using {}", name, defaultValue);
      return defaultValue;
    }
  }
}
