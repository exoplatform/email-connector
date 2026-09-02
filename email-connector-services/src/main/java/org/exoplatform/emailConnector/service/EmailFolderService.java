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
   * <p>
   * Since the administration settings drawer shipped, this JVM property is only the
   * default — the live switch is {@code EmailConnectorService#isCustomFoldersEnabled()},
   * a {@code SettingService} value an administrator can flip from the drawer, which
   * falls back to this property when nothing is stored.
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

  /**
   * The longest name a user may type for a folder they create or rename to. Bound by
   * {@code DISPLAY_NAME VARCHAR(255)} rather than {@code REMOTE_NAME VARCHAR(500)}:
   * for a folder this add-on writes (create) or moves within its own parent (rename),
   * the remote name IS the display name -- there is no server-supplied path to make
   * the two diverge the way discovery's {@code StringUtils.abbreviate(255)} allows --
   * so the tighter of the two columns is the real limit, and typing past it is refused
   * rather than silently shortened the way a discovered name is.
   */
  public static final int         MAX_FOLDER_NAME_LENGTH             = 255;

  /** The message code a blank or whitespace-only typed name carries. */
  public static final String      FOLDER_NAME_BLANK_MESSAGE          = "emailConnector.folder.name.blank";

  /** The message code a typed name past {@link #MAX_FOLDER_NAME_LENGTH} carries. */
  public static final String      FOLDER_NAME_TOO_LONG_MESSAGE       = "emailConnector.folder.name.tooLong";

  /**
   * The message code a typed name carrying the server's own hierarchy delimiter
   * carries -- creating or renaming into a NESTED folder is refused in v1 rather than
   * supported half-way (no parent picker, no path typing): every existing custom
   * folder, nested ones included, is still shown, opted in and moved to and from; what
   * is refused is a user typing the separator themselves, which would either silently
   * create a sub-folder under whatever the last segment before it names (if it
   * exists) or be refused by the server for a segment that does not.
   */
  public static final String      FOLDER_NAME_NESTED_MESSAGE         = "emailConnector.folder.name.nested";

  /**
   * The message code a typed name reserved for a provider's own namespace or for one
   * of the seven built-in roles carries. Gmail's whole special-use tree lives under
   * {@code [Gmail]/} -- a name starting with {@code [} is refused outright, the same
   * boundary {@link #classify} already draws by dropping {@code \Noselect} parents.
   * A name that EQUALS one of the strict built-in name sets ({@link #DRAFTS_FOLDER_NAMES},
   * {@link #TRASH_FOLDER_NAMES}, {@link #JUNK_FOLDER_NAMES}, {@code inbox},
   * {@code archive}/{@code archives}/{@code archivage}) is refused too: created today,
   * such a name would sit as a plain custom row until the next walk, and
   * {@link #classify}'s attribute-then-name order would then hand the role to THIS
   * folder if the mailbox has no other candidate for it -- taking it out of
   * {@code customs()}, so {@link #reconcileDiscovered} would mark the registry row
   * missing on a mailbox that never lost anything. The loose Sent/Archive/All-Mail
   * {@code contains} rules are deliberately NOT enforced here (see {@link #matchesByName}):
   * blocking every name containing "sent" would refuse "Consent forms" for a risk that
   * is, unlike the strict sets, a pre-existing and accepted fragility of those two
   * rules, not one this feature introduces.
   */
  public static final String      FOLDER_NAME_RESERVED_MESSAGE       = "emailConnector.folder.name.reserved";

  /** The message code a name already registered for this user (case-sensitively) carries. */
  public static final String      FOLDER_NAME_DUPLICATE_MESSAGE      = "emailConnector.folder.name.duplicate";

  /** The message code a delete refused because the folder still holds mail carries. */
  public static final String      FOLDER_NOT_EMPTY_MESSAGE           = "emailConnector.folder.notEmpty";

  /** The message code a server-refused CREATE carries. */
  public static final String      FOLDER_CREATE_FAILED_MESSAGE       = "emailConnector.folder.createFailed";

  /** The message code a server-refused RENAME carries. */
  public static final String      FOLDER_RENAME_FAILED_MESSAGE       = "emailConnector.folder.renameFailed";

  /** The message code a server-refused DELETE carries. */
  public static final String      FOLDER_DELETE_FAILED_MESSAGE       = "emailConnector.folder.deleteFailed";

  // The reserved-namespace bracket every big provider parks its own special-use tree
  // under (Gmail's [Gmail]/Spam, [Gmail]/Trash, ...). A folder the user types starting
  // with it is refused before any server round-trip.
  static final String             RESERVED_NAMESPACE_PREFIX          = "[";

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

  // The strict built-in names a typed folder name is refused against -- see
  // FOLDER_NAME_RESERVED_MESSAGE for why only the STRICT sets (last-segment equality)
  // are enforced here and not the loose Sent/Archive/All-Mail "contains" rules.
  private static final Set<String>  RESERVED_EXACT_NAMES              =
                                                 buildReservedExactNames();

  /**
   * The strict built-in name sets, flattened into one lowercase set, plus the three
   * names {@link #classify} matches by full-name equality: {@code inbox} and the
   * Archive spread ({@code archive}, {@code archives}, {@code archivage}).
   *
   * @return the reserved names, lowercase
   */
  private static Set<String> buildReservedExactNames() {
    Set<String> names = new HashSet<>();
    names.add("inbox");
    names.add("archive");
    names.add("archives");
    names.add("archivage");
    names.addAll(DRAFTS_FOLDER_NAMES);
    names.addAll(TRASH_FOLDER_NAMES);
    names.addAll(JUNK_FOLDER_NAMES);
    return Set.copyOf(names);
  }

  @Autowired
  private EmailFolderStorage      emailFolderStorage;

  @Autowired
  private EmailConnectorService   emailConnectorService;

  /**
   * Whether custom folders are switched on at all -- see
   * {@link #CUSTOM_FOLDERS_ENABLED_PROPERTY}. Delegates to
   * {@code EmailConnectorService#isCustomFoldersEnabled()}, which reads the
   * administration-wide setting (falling back to the JVM property) so an
   * administrator can withdraw the feature from the settings drawer without a
   * restart.
   *
   * @return true when custom folders are discovered, mirrored and offered
   */
  public boolean isCustomFoldersEnabled() {
    return emailConnectorService.isCustomFoldersEnabled();
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
          emailFolderStorage.markSeen(username, existing.getId(), displayNameOf(discovered), discovered.delimiter(), now);
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
        emailFolderStorage.markMissing(username, registered.getId());
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
   * The name checks that need nothing from the server: blank, too long, or reserved
   * for a provider's own namespace or one of the built-in roles. What DOES need the
   * server -- whether the name embeds the mailbox's actual hierarchy delimiter -- is
   * {@link #checkNotNested}, asked separately because a rename already knows its
   * folder's delimiter while a create must first connect to learn it; asking here
   * would make every doomed name (blank, too long) pay for a connection it does not
   * need.
   *
   * @param name the name as typed
   * @return the name, trimmed
   * @throws IllegalArgumentException if the name is blank, longer than
   *           {@link #MAX_FOLDER_NAME_LENGTH}, starts with {@value
   *           #RESERVED_NAMESPACE_PREFIX}, or equals one of the strict built-in names
   *           ({@link #FOLDER_NAME_RESERVED_MESSAGE})
   */
  public String validateFolderName(String name) {
    String trimmed = name == null ? "" : name.trim();
    if (trimmed.isEmpty()) {
      throw new IllegalArgumentException(FOLDER_NAME_BLANK_MESSAGE);
    }
    if (trimmed.length() > MAX_FOLDER_NAME_LENGTH) {
      throw new IllegalArgumentException(FOLDER_NAME_TOO_LONG_MESSAGE);
    }
    if (trimmed.startsWith(RESERVED_NAMESPACE_PREFIX) || RESERVED_EXACT_NAMES.contains(trimmed.toLowerCase())) {
      throw new IllegalArgumentException(FOLDER_NAME_RESERVED_MESSAGE);
    }
    return trimmed;
  }

  /**
   * Refuses a name that embeds the mailbox's own hierarchy delimiter -- the one check
   * {@link #validateFolderName} cannot make on its own, since the delimiter is a fact
   * of the connected server (or, for a rename, of the folder's own registry row), not
   * of the typed string. A blank delimiter (a flat namespace, or a folder with none
   * recorded) admits every name: there is no separator to embed.
   *
   * @param name the name, already trimmed by {@link #validateFolderName}
   * @param delimiter the mailbox's hierarchy separator, or null/blank on a flat
   *          namespace
   * @throws IllegalArgumentException if the name contains the delimiter
   *           ({@link #FOLDER_NAME_NESTED_MESSAGE})
   */
  public void checkNotNested(String name, String delimiter) {
    if (StringUtils.isNotBlank(delimiter) && name.contains(delimiter)) {
      throw new IllegalArgumentException(FOLDER_NAME_NESTED_MESSAGE);
    }
  }

  /**
   * Refuses a name already registered for this user, case-sensitively -- the registry's
   * own unique index is {@code (USER_ID, REMOTE_NAME)} on a case-sensitive collation
   * (see {@code EmailFolderEntity}), because "Projets" and "projets" are two different
   * IMAP folders. A pre-check rather than letting the index alone answer: it turns the
   * database's constraint violation into the one message code the screen knows how to
   * show, before any server round-trip is spent on a name that cannot be registered.
   *
   * @param username the mailbox owner
   * @param remoteName the full name the create or rename would write
   * @param excludingId a folder id to exempt (a rename checking against its own row
   *          keeping its current name), or null
   * @throws IllegalArgumentException if another row of this user already carries that
   *           name ({@link #FOLDER_NAME_DUPLICATE_MESSAGE})
   */
  public void checkNameAvailable(String username, String remoteName, Long excludingId) {
    EmailFolder existing = emailFolderStorage.getFolderByRemoteName(username, remoteName);
    if (existing != null && (excludingId == null || !existing.getId().equals(excludingId))) {
      throw new IllegalArgumentException(FOLDER_NAME_DUPLICATE_MESSAGE);
    }
  }

  /**
   * Registers the row for a folder this add-on just created on the server -- the
   * explicit-act counterpart of {@link #reconcileDiscovered}'s upsert, deliberately
   * NOT built on top of it: {@code reconcileDiscovered} marks every registered folder
   * NOT in the batch it is handed as missing, which is correct for a WHOLE walk and
   * would be wrong here, where the batch is exactly the one folder just made -- every
   * other one of the user's folders would be marked missing by a create. So a create
   * writes its own row directly, through the same {@link EmailFolderStorage#createFolder}
   * every discovered folder is first written by (opt-in off, whatever the caller
   * asks -- see its own javadoc), and leaves every other row untouched. The next real
   * walk, whenever it runs, finds this folder by its remote name and upserts it like
   * any other -- it is never registered twice, and it is never marked missing before
   * that walk has a chance to see it.
   *
   * @param username the mailbox owner
   * @param remoteName the folder's full name on the server, as created (top-level, so
   *          this is also its display name in v1 -- see {@link #FOLDER_NAME_NESTED_MESSAGE})
   * @param delimiter the mailbox's hierarchy separator, for path rendering later
   * @return the registered row, opt-in off
   */
  public EmailFolder registerCreatedFolder(String username, String remoteName, String delimiter) {
    EmailFolder folder = new EmailFolder();
    folder.setUserId(username);
    folder.setRemoteName(remoteName);
    folder.setDisplayName(remoteName);
    folder.setDelimiter(delimiter);
    folder.setType(MailFolderView.TYPE_CUSTOM);
    Date now = new Date();
    folder.setDiscoveredDate(now);
    folder.setLastSeenDate(now);
    return emailFolderStorage.createFolder(folder);
  }

  /**
   * Opts a just-created folder in, unless the cap is already reached -- the answer to
   * "does a created folder auto-opt-in, and what happens at the cap": yes, because the
   * user made it to use it, and a folder created then left invisible in every listing
   * until a second, separate toggle is a worse first experience than the one
   * {@code +} promises; but the cap is never bypassed for it, so at the cap the folder
   * is left registered and unmirrored -- on the server, in the settings list, ready to
   * opt in the moment a slot frees -- rather than either silently exceeding the limit
   * or refusing a create that already succeeded on the mail server.
   *
   * @param username the mailbox owner
   * @param id the registry id of the just-created row
   * @return true when the folder is now mirrored, false when the cap refused it
   */
  public boolean tryAutoEnable(String username, long id) {
    try {
      setSyncEnabled(username, id, true);
      return true;
    } catch (IllegalArgumentException capReached) {
      return false;
    }
  }

  /**
   * Renames the registry row of a folder this add-on just renamed on the server --
   * called only AFTER the server confirms the rename, and updating the SAME row rather
   * than deleting and re-creating one: an in-app rename is known to have happened
   * (unlike a server-side one, which discovery can only read as "old folder missing,
   * new folder discovered" -- Design &amp; Plan assumption A-5), so the row's id, and
   * with it the {@code CUSTOM:<id>} key every one of its mirrored {@code EMAIL_BOX}
   * rows carries, survives the rename untouched. Every read of those rows is keyed by
   * id, never by {@code REMOTE_NAME} (verified across this module: the only other
   * readers of {@code REMOTE_NAME} open the live IMAP folder, never a cached row), so
   * a mirrored message stays exactly where it was before the rename, in the same
   * conversation, in the same search results.
   *
   * @param username the mailbox owner
   * @param id the registry id
   * @param remoteName the folder's new full name on the server
   * @param displayName the new display name (the new last segment)
   * @return the row as it now stands
   */
  public EmailFolder renameFolder(String username, long id, String remoteName, String displayName) {
    return emailFolderStorage.renameFolder(username, id, remoteName, displayName);
  }

  /**
   * Drops one registered folder -- the explicit-delete counterpart of
   * {@link #deleteFolders}, which drops every row of a mailbox wipe. The mirrored rows
   * this folder kept are NOT deleted here, for the reason {@link #reconcileDiscovered}
   * gives: the registry cannot reach them, and the caller (which can) is expected to
   * have cleared them first.
   *
   * @param username the mailbox owner
   * @param id the registry id
   */
  public void removeFolder(String username, long id) {
    emailFolderStorage.deleteFolder(username, id);
  }

  /**
   * The parent prefix of a full name -- everything up to and including the last
   * occurrence of the delimiter, or the empty string on a top-level folder or a flat
   * namespace. What a rename adds the new last segment onto, so a rename can only ever
   * change a folder's own name, never move it to a different parent (v1 does not offer
   * a parent picker -- see {@link #FOLDER_NAME_NESTED_MESSAGE}).
   *
   * @param fullName the folder's current full name
   * @param delimiter the mailbox's hierarchy separator, or null/blank on a flat
   *          namespace or a top-level folder
   * @return the prefix, possibly empty, never null
   */
  static String parentPrefix(String fullName, String delimiter) {
    if (StringUtils.isBlank(fullName) || StringUtils.isBlank(delimiter)) {
      return "";
    }
    int cut = fullName.lastIndexOf(delimiter);
    return cut < 0 ? "" : fullName.substring(0, cut + delimiter.length());
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
    emailFolderStorage.markMissing(username, id);
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
