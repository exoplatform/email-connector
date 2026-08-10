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
package org.exoplatform.emailConnector.service;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import org.exoplatform.emailConnector.carddav.AddressBook;
import org.exoplatform.emailConnector.carddav.CardDavClient;
import org.exoplatform.emailConnector.carddav.CardDavException;
import org.exoplatform.emailConnector.carddav.ContactResource;
import org.exoplatform.emailConnector.carddav.ParsedVCard;
import org.exoplatform.emailConnector.carddav.PutResult;
import org.exoplatform.emailConnector.carddav.VCardParser;
import org.exoplatform.emailConnector.model.CardDavContactData;
import org.exoplatform.emailConnector.model.CardDavRow;
import org.exoplatform.emailConnector.model.ContactSyncState;
import org.exoplatform.emailConnector.model.EmailConnector;
import org.exoplatform.emailConnector.model.EmailContact;
import org.exoplatform.emailConnector.model.EmailContactSource;
import org.exoplatform.emailConnector.model.PhotoOrigin;
import org.exoplatform.emailConnector.model.SyncStatus;
import org.exoplatform.emailConnector.model.UserEmailSetting;
import org.exoplatform.emailConnector.storage.EmailContactStorage;
import org.exoplatform.emailConnector.utils.EmailContactUtils;
import org.exoplatform.services.log.ExoLogger;
import org.exoplatform.services.log.Log;

/**
 * Brings a user's CardDAV address book into their contact store.
 * <p>
 * Reads are the rule, server to us: an address-book contact is edited where it
 * lives, and the next sync carries the change over — which is why a CARDDAV
 * row is refused edits elsewhere in the service layer. The one write is
 * {@link #publishContact}: an explicit, per-contact, creates-only push of a
 * contact the user owns into their own book, after which the server copy is
 * authoritative like any other.
 * <p>
 * Kept apart from {@link EmailContactService} on purpose — that class owns the
 * store's own rules and is long enough already; this one owns a protocol
 * conversation and the reconciliation it produces.
 */
@Service
public class EmailContactCardDavSyncService {

  private static final Log         LOG                     = ExoLogger.getLogger(EmailContactCardDavSyncService.class);

  /** How many entries are fetched in one multiget. */
  private static final int         FETCH_BATCH_SIZE        = 50;

  /** Consecutive failures after which a user's sync stops trying by itself. */
  private static final int         MAX_FAILED_ATTEMPTS     = 3;

  /** How long a BLOCKED user waits before the next attempt is allowed. */
  private static final long        BLOCKED_COOLDOWN_MS     = 30L * 60 * 1000;

  /** How long a scheduled run waits, in hours, overridable per deployment. */
  private static final String      SYNC_PERIOD_PROPERTY    = "email.connector.contacts.sync.hour.period";

  /** Six hours: a quarter of a day stale at worst, with Sync now for the rest. */
  private static final int         DEFAULT_SYNC_PERIOD_HOURS = 6;

  /**
   * The administrator's kill switch for publishing contacts to the address
   * book, in the style of {@code email.connector.contacts.sync.hour.period}:
   * a JVM property read on every call so flipping it needs no restart. Default
   * ON — every publish is one explicit user click on their own contact — but
   * an organisation whose CardDAV server is the authority on address books can
   * turn this connector back to read-only with it.
   */
  public static final String       PUBLISH_ENABLED_PROPERTY  = "email.connector.contacts.publish.enabled";

  /** Message code: the administrator turned publishing off. */
  public static final String       PUBLISH_DISABLED          = "emailConnector.contacts.publish.disabled";

  /** Message code: this source may not be published (directory rows never are). */
  public static final String       PUBLISH_SOURCE_NOT_ALLOWED = "emailConnector.contacts.publish.sourceNotAllowed";

  /** Message code: the row already lives in the address book. */
  public static final String       PUBLISH_ALREADY_PUBLISHED = "emailConnector.contacts.publish.alreadyPublished";

  /** Message code: no address book is bound, or the binding is unusable. */
  public static final String       PUBLISH_NO_ADDRESS_BOOK   = "emailConnector.contacts.publish.noAddressBook";

  /** Message code: the book was never discovered, so there is nowhere verified to write. */
  public static final String       PUBLISH_NOT_DISCOVERED    = "emailConnector.contacts.publish.notDiscovered";

  /** Message code: the server says an entry already exists where the card would go. */
  public static final String       PUBLISH_EXISTS_ON_SERVER  = "emailConnector.contacts.publish.existsOnServer";

  @Autowired
  private EmailContactStorage      emailContactStorage;

  @Autowired
  private EmailContactService      emailContactService;

  @Autowired
  private EmailContactVCardService emailContactVCardService;

  @Autowired
  private EmailContactFavoriteService emailContactFavoriteService;

  @Autowired
  private UserEmailSettingService  userEmailSettingService;

  @Autowired
  private EmailConnectorService    emailConnectorService;

  @Autowired
  private CardDavClient            cardDavClient;

  @Autowired
  private VCardParser              vCardParser;

  /**
   * Who is syncing right now, in this JVM.
   * <p>
   * The stored status alone is not enough to prevent two runs at once — the
   * mailbox sync learned that the hard way — because between reading it and
   * writing it there is a window a second thread walks straight through.
   */
  private final Set<String>        syncingUsers            = ConcurrentHashMap.newKeySet();

  /**
   * Pulls one user's address book into their contacts.
   * <p>
   * Most runs cost a single request: the collection carries a version, and an
   * unchanged version means nothing in the whole address book moved.
   *
   * @param username the mailbox owner
   */
  public void syncAddressBook(String username) {
    syncAddressBook(username, false);
  }

  /**
   * Syncs this user's address book if enough time has passed since the last run.
   * <p>
   * Called when a mailbox sync completes, which is what gives the address book a
   * schedule without a scheduler of its own: the mailbox job already runs per
   * user, already staggers them, already re-registers at startup and unschedules
   * on disconnect. A second Quartz job would mean writing those three things
   * again, and they are precisely the ones that break.
   * <p>
   * Throttled because an address book is not mail. At a ten-minute mailbox period,
   * syncing on every completion would be some 140 requests a day per user for a
   * book most people change monthly, and providers rate-limit. The period only
   * bounds how stale the book can get: pressing Sync now still reads it at once,
   * and a run that finds an unchanged collection version costs a single request.
   *
   * @param username the mailbox owner
   */
  public void syncAddressBookIfDue(String username) {
    if (StringUtils.isBlank(username)) {
      return;
    }
    ContactSyncState state = userEmailSettingService.getContactSyncState(username);
    if (state != null && !isDue(state)) {
      return;
    }
    // Not "requested": a scheduled run keeps the collection-version short-circuit
    // and the pause a blocked book is serving, both of which only a person asking
    // for a run may bypass.
    syncAddressBook(username, false);
  }

  /**
   * Whether the address book has gone long enough without a run.
   *
   * @param state where the last run got to
   * @return true when a run is due
   */
  private boolean isDue(ContactSyncState state) {
    Long last = state.getLastSyncStartDate();
    return last == null || new Date().getTime() - last >= syncPeriodMs();
  }

  /**
   * How long the address book may go between scheduled runs.
   * <p>
   * Read from a JVM property each time rather than cached, matching the mailbox
   * period, so an administrator chasing a rate limit can change it without a
   * restart.
   *
   * @return the period in milliseconds
   */
  private long syncPeriodMs() {
    int hours;
    try {
      hours = Integer.parseInt(System.getProperty(SYNC_PERIOD_PROPERTY, String.valueOf(DEFAULT_SYNC_PERIOD_HOURS)));
    } catch (NumberFormatException e) {
      // A typo in a property must not stop the address book syncing altogether.
      LOG.warn("Unreadable {}, falling back to {} hours", SYNC_PERIOD_PROPERTY, DEFAULT_SYNC_PERIOD_HOURS);
      hours = DEFAULT_SYNC_PERIOD_HOURS;
    }
    return Math.max(hours, 1) * 3600_000L;
  }

  /**
   * Pulls one user's address book, saying whether a person asked for it.
   * <p>
   * A run the user asked for ignores the pause: the pause exists to stop a
   * scheduled job hammering a server that keeps refusing, not to tell somebody who
   * just fixed their password to wait half an hour. Clicking the button and having
   * nothing happen is worse than an error.
   *
   * @param username the mailbox owner
   * @param requested whether a person asked for this run
   */
  public void syncAddressBook(String username, boolean requested) {
    if (StringUtils.isBlank(username) || !syncingUsers.add(username)) {
      return;
    }
    ContactSyncState state = null;
    try {
      UserEmailSetting setting = userEmailSettingService.getUserEmailSetting(username);
      if (setting == null || !Boolean.TRUE.equals(setting.getCarddavEnabled())
          || StringUtils.isBlank(setting.getEmailConnectorId())) {
        return;
      }
      EmailConnector connector = emailConnectorService.getEmailConnector(Long.parseLong(setting.getEmailConnectorId()));
      if (connector == null || StringUtils.isBlank(connector.getCarddavUrl())) {
        return;
      }
      state = userEmailSettingService.getContactSyncState(username);
      if (!requested && isBlocked(state)) {
        LOG.debug("Address book sync for user {} is paused after repeated failures", username);
        return;
      }
      run(username, setting, connector, state, requested);
    } catch (Exception e) {
      // Recorded, not merely logged. A failure the server did not cause -- a URL
      // that is not a URL, a bug of ours -- used to leave the stored status at
      // whatever the last good run wrote, so the page went on reporting a sync
      // that had in fact never happened. Nobody reads the server log to find that
      // out.
      fail(username,
           state == null ? new ContactSyncState() : state,
           e instanceof CardDavException cause ? cause : new CardDavException(e.getMessage(), e));
    } finally {
      syncingUsers.remove(username);
    }
  }

  /**
   * Forgets what the last sync knew, so the next one reads the whole address book
   * again. What "re-sync the address book" means, and cheap: the rows stay, they
   * are simply all re-examined.
   *
   * @param username the mailbox owner
   */
  public void resetAddressBookSync(String username) {
    userEmailSettingService.clearContactSyncState(username);
  }

  /**
   * The state the settings screen displays.
   *
   * @param username the mailbox owner
   * @return the stored state, never null
   */
  public ContactSyncState getSyncState(String username) {
    return userEmailSettingService.getContactSyncState(username);
  }

  /**
   * Whether the publish action exists for this user at all — what the UI asks
   * before showing the button, and the cheap half of what {@link #publishContact}
   * re-checks server-side.
   * <p>
   * Every condition here is answered from stored state, no network: the kill
   * switch is on, an address book is bound and enabled, the connector still
   * carries a CardDAV URL, and discovery has succeeded at least once. The last
   * one matters most — a book that was never discovered is a book this
   * connector has never verified exists, and offering a button that can only
   * fail teaches users to stop trusting it.
   *
   * @param username the mailbox owner
   * @return true when publishing a contact could work for this user
   */
  public boolean isPublishAvailable(String username) {
    if (!isPublishEnabled() || StringUtils.isBlank(username)) {
      return false;
    }
    UserEmailSetting setting = userEmailSettingService.getUserEmailSetting(username);
    Long connectorId = boundConnectorId(setting);
    if (connectorId == null) {
      return false;
    }
    EmailConnector connector = emailConnectorService.getEmailConnector(connectorId);
    if (connector == null || StringUtils.isBlank(connector.getCarddavUrl())) {
      return false;
    }
    ContactSyncState state = userEmailSettingService.getContactSyncState(username);
    return state != null && StringUtils.isNotBlank(state.getAddressBookHref());
  }

  /**
   * Publishes one of the caller's contacts into their CardDAV address book —
   * the write-back's first and deliberately least destructive shape: it can
   * only CREATE. The card is stored under {@code If-None-Match: *}, so an
   * entry already at the minted URL makes the server refuse rather than this
   * code overwrite, whatever race produced it.
   * <p>
   * The guards all matter, and they run server-side whatever the UI showed:
   * the caller owns the contact and it is visible (a stranger's id answers
   * null, indistinguishable from absent); the source is MANUAL or COLLECTED —
   * never DIRECTORY, because pushing the company's people directory into a
   * personal address book is an export nobody asked for, not a sync; the
   * administrator's kill switch is on; an address book is bound; and discovery
   * has already succeeded — a publish never writes to a guessed or
   * configured-but-unverified URL, only to a book the inbound sync's own
   * {@link #resolveAddressBook} vouches for, so the two directions can never
   * disagree about which book they mean.
   * <p>
   * On success the row is bound to the entry it just became — connector, href,
   * etag, uid — and flipped to CARDDAV: the server copy is authoritative from
   * here on, exactly as if the inbound sync had written it. The stored ctag is
   * deliberately NOT advanced: a post-PUT ctag could equal one that also
   * covers another client's concurrent change, and recording it would make the
   * next run's cheap check skip that change for good. One redundant reconcile
   * is the honest price of the echo.
   *
   * @param username the mailbox owner
   * @param contactId the contact to publish
   * @return the contact re-read after the publish, now an address-book row; or
   *         null when the caller has no such visible contact
   * @throws IllegalArgumentException with a message code when a guard refuses
   * @throws IllegalStateException with a message code when the row or the
   *           server says the card already exists
   * @throws CardDavException when the server cannot be reached or errors
   */
  public EmailContact publishContact(String username, long contactId) {
    if (!isPublishEnabled()) {
      throw new IllegalArgumentException(PUBLISH_DISABLED);
    }
    EmailContact contact = emailContactService.getContact(contactId, username);
    if (contact == null) {
      return null;
    }
    if (EmailContactSource.CARDDAV.equals(contact.getSource())) {
      // Not an error to force through: the row IS an address-book entry, and
      // "publish" for it would be the update slice, which does not exist yet.
      throw new IllegalStateException(PUBLISH_ALREADY_PUBLISHED);
    }
    if (!EmailContactSource.MANUAL.equals(contact.getSource())
        && !EmailContactSource.COLLECTED.equals(contact.getSource())) {
      throw new IllegalArgumentException(PUBLISH_SOURCE_NOT_ALLOWED);
    }
    UserEmailSetting setting = userEmailSettingService.getUserEmailSetting(username);
    Long connectorId = boundConnectorId(setting);
    EmailConnector connector = connectorId == null ? null : emailConnectorService.getEmailConnector(connectorId);
    if (connector == null || StringUtils.isBlank(connector.getCarddavUrl())) {
      throw new IllegalArgumentException(PUBLISH_NO_ADDRESS_BOOK);
    }
    ContactSyncState state = userEmailSettingService.getContactSyncState(username);
    if (state == null || StringUtils.isBlank(state.getAddressBookHref())) {
      // Discovery has never succeeded, so there is no VERIFIED collection to
      // write into. Publishing to the configured URL as typed would be writing
      // to a guess; the fix is a successful sync, not a bolder publish.
      throw new IllegalArgumentException(PUBLISH_NOT_DISCOVERED);
    }
    // The same resolution the inbound sync uses, so both directions always mean
    // the same book. With discovery already done this answers from stored state;
    // it only goes back to the network when an administrator repointed the
    // connector -- and then it verifies the NEW book before anything is written.
    String previousHref = state.getAddressBookHref();
    AddressBook addressBook = resolveAddressBook(setting, connector, state);
    if (!StringUtils.equals(previousHref, addressBook.url())) {
      // Discovery ran and found a different book: remembered, as a sync run
      // would have remembered it, so the next publish or sync starts there.
      userEmailSettingService.setContactSyncState(state, username);
    }
    String uid = UUID.randomUUID().toString();
    String putUrl = StringUtils.removeEnd(addressBook.url(), "/") + "/" + uid + ".vcf";
    String vcard = emailContactVCardService.getPublishVCard(contact, uid);
    PutResult result = cardDavClient.putVCard(putUrl, vcard, "*", setting.getEmailAddress(), setting.getEmailPassword());
    if (result.preconditionFailed()) {
      // The server refusing to create is it keeping the only promise that makes
      // this slice safe. A UUID colliding is not the likely story here -- a
      // retried request or a proxy replay is -- and either way the answer is
      // the same: report, never force.
      throw new IllegalStateException(PUBLISH_EXISTS_ON_SERVER);
    }
    // The name bound locally is the SERVER's name for the entry, in the shape
    // the server's own listing will use. Two lessons from the first BlueMind
    // run are folded in here. First, BlueMind does not keep the URL that was
    // PUT -- it files the card under a path of its own and says so in
    // Location, so when that header is present it wins over the minted URL.
    // Second, the sync's rows carry hrefs exactly as PROPFIND lists them --
    // server-relative paths -- and the reconciliation matches by string
    // equality, so binding an absolute URL here would never match the listing
    // and the row would look vanished on the very next run. When the server
    // neither sends a Location nor keeps our path, the next sync still
    // converges by claiming the row through its address; one redundant cycle,
    // never a loss.
    String entryHref = listingHrefOf(StringUtils.defaultIfBlank(result.location(), putUrl));
    emailContactStorage.bindPublishedCard(contact.getId(), connector.getId(), entryHref, result.etag(), uid);
    LOG.info("Contact {} of user {} published to the address book of connector {}", contact.getId(), username, connector.getId());
    // Re-read rather than mutated in place: the caller gets the row as every
    // other read now sees it, source flipped and enrichment applied.
    return emailContactService.getContact(contactId, username);
  }

  /**
   * Whether the administrator allows publishing at all — the kill switch, read
   * per call like the sync period so flipping it needs no restart.
   *
   * @return true unless the property says otherwise
   */
  private boolean isPublishEnabled() {
    return Boolean.parseBoolean(System.getProperty(PUBLISH_ENABLED_PROPERTY, "true"));
  }

  /**
   * An entry URL in the shape the server's listing answers it — the
   * server-relative path. This is what every synced row stores as its href
   * (see {@code reconcile}, which compares hrefs by string equality against
   * the PROPFIND listing), so it is the only shape a bound row may carry.
   *
   * @param entryUrl the entry's absolute URL
   * @return its path, or the URL untouched when it will not parse — a name
   *         too odd to parse still names the entry, and the address-claim
   *         fallback absorbs a shape mismatch
   */
  private String listingHrefOf(String entryUrl) {
    try {
      String path = new URI(entryUrl).getRawPath();
      return StringUtils.isNotBlank(path) ? path : entryUrl;
    } catch (URISyntaxException e) {
      LOG.debug("An entry URL could not be reduced to its path and is kept whole: {}", entryUrl, e);
      return entryUrl;
    }
  }

  /**
   * One run, from discovery to reconciliation.
   *
   * @param username the mailbox owner
   * @param setting the user's mail binding, whose credentials the address book shares
   * @param connector the provider preset carrying the CardDAV URL
   * @param state where the last run got to
   */
  private void run(String username,
                   UserEmailSetting setting,
                   EmailConnector connector,
                   ContactSyncState state,
                   boolean requested) {
    state.setStatus(SyncStatus.IN_PROGRESS);
    state.setLastSyncStartDate(new Date().getTime());
    userEmailSettingService.setContactSyncState(state, username);
    try {
      String previousBook = state.getAddressBookHref();
      AddressBook addressBook = resolveAddressBook(setting, connector, state);
      // An administrator repointing the connector is not 500 people being deleted,
      // and must not be treated as one.
      boolean bookChanged = previousBook != null && !previousBook.equals(addressBook.url());
      String currentCtag = cardDavClient.getCtag(addressBook, setting.getEmailAddress(), setting.getEmailPassword());
      // The cheap check is skipped for a run somebody asked for. It makes the
      // scheduled job nearly free, but it also means an address book that has not
      // changed is never looked at again -- so after the rules for reading it
      // change, or when a user presses the button because something looks wrong,
      // it is precisely the wrong answer. They clicked to have it redone.
      if (!requested && currentCtag != null && currentCtag.equals(state.getCtag())) {
        // Nothing in the address book moved. This is the common case, and it is
        // why the job can run often without costing anything.
        succeed(username, state, state.getCtag());
        return;
      }
      boolean complete = reconcile(username, setting, connector, addressBook, bookChanged);
      // The version is only recorded when the run saw everything. Recording it
      // after a partial run would make the next run's cheap check skip exactly
      // the entries this one missed, permanently and without a trace.
      succeed(username, state, complete ? currentCtag : state.getCtag());
    } catch (CardDavException e) {
      fail(username, state, e);
    }
  }

  /**
   * The address book to talk to, discovered once and remembered.
   *
   * @param setting the user's mail binding
   * @param connector the provider preset
   * @param state where the last run got to
   * @return the address book
   */
  private AddressBook resolveAddressBook(UserEmailSetting setting, EmailConnector connector, ContactSyncState state) {
    String configured = resolveConfiguredUrl(connector.getCarddavUrl(), setting.getEmailAddress());
    if (StringUtils.isNotBlank(state.getAddressBookHref()) && StringUtils.equals(configured, state.getConfiguredUrl())) {
      return new AddressBook(state.getAddressBookHref(), null, state.getCtag());
    }
    // Either nothing was discovered yet, or an administrator repointed the
    // connector somewhere else. The second case must not reuse the old book.
    AddressBook discovered = cardDavClient.discoverAddressBook(configured,
                                                               setting.getEmailAddress(),
                                                               setting.getEmailPassword());
    state.setAddressBookHref(discovered.url());
    state.setConfiguredUrl(configured);
    return discovered;
  }

  /**
   * Fills the per-user placeholders an administrator may have written into the
   * connector's CardDAV URL.
   * <p>
   * Some providers put the account inside the collection path — Google's is
   * {@code /carddav/v1/principals/somebody@gmail.com/lists/default/} — which a
   * single preset shared by every user of that provider cannot hold literally.
   * Discovery would normally spare us this, but Google does not serve
   * {@code /.well-known/carddav} at all. So the preset carries the shape and each
   * run fills in whose account it is.
   *
   * @param configuredUrl the URL as the administrator wrote it
   * @param emailAddress the user's own mailbox address
   * @return the URL for this user
   */
  private String resolveConfiguredUrl(String configuredUrl, String emailAddress) {
    if (StringUtils.isBlank(configuredUrl) || StringUtils.isBlank(emailAddress)) {
      return configuredUrl;
    }
    String localPart = StringUtils.substringBefore(emailAddress, "@");
    String resolved = configuredUrl.replace("{email}", emailAddress).replace("{localpart}", localPart);
    // Administrators type a host, not a URI. Left alone this reaches the client as
    // a string with no scheme, which fails as "URI with undefined scheme" -- an
    // error about our parser rather than about what was typed. Address books are
    // served over TLS; assuming so beats refusing.
    return StringUtils.startsWithAny(resolved, "http://", "https://") ? resolved : "https://" + resolved;
  }

  /**
   * Compares the address book with what is stored and makes the store match.
   *
   * @param username the mailbox owner
   * @param setting the user's mail binding
   * @param connector the provider preset
   * @param addressBook the collection to read
   * @return true when every entry that needed reading was read
   */
  private boolean reconcile(String username,
                            UserEmailSetting setting,
                            EmailConnector connector,
                            AddressBook addressBook,
                            boolean bookChanged) {
    Map<String, String> serverEtags = cardDavClient.listResourceEtags(addressBook,
                                                                      setting.getEmailAddress(),
                                                                      setting.getEmailPassword());
    List<CardDavRow> storedRows = emailContactStorage.getCardDavRows(username, connector.getId());
    Map<String, CardDavRow> storedByHref = new java.util.HashMap<>();
    storedRows.forEach(row -> storedByHref.put(row.href(), row));

    List<String> toFetch = new ArrayList<>();
    serverEtags.forEach((href, etag) -> {
      CardDavRow stored = storedByHref.get(href);
      if (stored == null || !StringUtils.equals(etag, stored.etag())) {
        toFetch.add(href);
      }
    });

    boolean complete = true;
    int written = 0;
    int skipped = 0;
    // Every row the apply loop writes onto, whichever href it was found under.
    // The vanished check below judges rows by the storedRows SNAPSHOT taken
    // before this loop ran, and a row an entry just claimed through its address
    // still carries its old href in that snapshot. Without this set, a server
    // that files a card under a different path than the one it was known by --
    // BlueMind does this to a freshly published card -- had its row updated by
    // apply and then hard-deleted by removeVanished in the same run.
    Set<Long> appliedRowIds = new HashSet<>();
    for (int start = 0; start < toFetch.size(); start += FETCH_BATCH_SIZE) {
      List<String> batch = toFetch.subList(start, Math.min(start + FETCH_BATCH_SIZE, toFetch.size()));
      try {
        for (ContactResource resource : cardDavClient.multiget(addressBook,
                                                               batch,
                                                               setting.getEmailAddress(),
                                                               setting.getEmailPassword())) {
          Long appliedId = apply(username, connector, resource, storedByHref.get(resource.href()));
          written += appliedId != null ? 1 : 0;
          skipped += appliedId != null ? 0 : 1;
          if (appliedId != null) {
            appliedRowIds.add(appliedId);
          }
        }
      } catch (CardDavException e) {
        // One batch failing costs those entries this run, not the whole sync: the
        // rest of the address book still lands, and the version is not recorded,
        // so the next run picks these up.
        complete = false;
        LOG.warn("A batch of address book entries could not be read for user {}", username, e);
      }
    }
    int removed = removeVanished(username, serverEtags.keySet(), storedRows, bookChanged, appliedRowIds);
    // The skipped count matters as much as the written one: an address book full of
    // phone-only entries produces far fewer contacts than it has entries, and
    // without this number that gap looks like a bug rather than the store being
    // keyed on an address.
    LOG.info("Address book sync for user {}: {} entries on the server, {} written, {} skipped (no address, or not ours to claim), {} no longer there",
             username,
             serverEtags.size(),
             written,
             skipped,
             removed);
    return complete;
  }

  /**
   * Writes one address-book entry into the store, under the rules that decide
   * whether it may claim a row that is already there.
   *
   * @param username the store owner
   * @param connector the provider preset
   * @param resource the entry as the server returned it
   * @param sameHref the row already known to be this entry, if any
   * @return the id of the row written, or null when the entry was skipped —
   *         the caller needs the id, not a boolean, because a written row must
   *         be shielded from the vanished check's pre-apply snapshot
   */
  private Long apply(String username, EmailConnector connector, ContactResource resource, CardDavRow sameHref) {
    ParsedVCard card = vCardParser.parse(resource.vcard());
    if (card == null) {
      // Unreadable, which the parser has already logged. One entry skipped, never a
      // failed run.
      return null;
    }
    if (card.emails().isEmpty() && StringUtils.isBlank(card.formattedName()) && StringUtils.isBlank(card.familyName())) {
      // Nothing to show and nothing to reach: not a person, whatever the server
      // calls it. An entry with a NAME but no address is kept -- somebody with only
      // a phone number is an ordinary contact, and refusing them is what made a
      // 496-entry address book arrive as 132 contacts.
      LOG.debug("An address book entry of user {} carries neither a name nor an address and was skipped", username);
      return null;
    }
    CardDavContactData data = toContactData(card);
    // Matched on ANY address the entry carries, not only the first: the same person
    // may already be known here under their other address, and two rows for one
    // person is the thing this is meant to prevent. An entry with no address at all
    // matches nothing and simply becomes a new contact.
    CardDavRow target = sameHref != null ? sameHref : findByAnyAddress(username, data);
    if (target != null && sameHref == null && !claimable(target)) {
      // A contact the user typed themselves, or a legacy link to a colleague's
      // profile. Claiming it would flip it read-only and overwrite what they
      // wrote; the address book is not more right about a person than they are.
      LOG.debug("An address book entry of user {} matches a contact that is not the sync's to claim", username);
      return null;
    }
    boolean writePhoto = target == null || target.photoOrigin() != PhotoOrigin.USER;
    return emailContactStorage.saveCardDavContact(username,
                                                  target == null ? null : target.id(),
                                                  connector.getId(),
                                                  resource.href(),
                                                  resource.etag(),
                                                  data,
                                                  target == null ? null : target.photoFileId(),
                                                  writePhoto);
  }

  /**
   * The contact this entry already is, found by any address it carries.
   *
   * @param username the store owner
   * @param data the entry's fields
   * @return the row, or null when this person is not known here yet
   */
  private CardDavRow findByAnyAddress(String username, CardDavContactData data) {
    List<String> addresses = new ArrayList<>();
    if (StringUtils.isNotBlank(data.primaryEmail())) {
      addresses.add(data.primaryEmail());
    }
    if (data.secondaryEmails() != null) {
      addresses.addAll(data.secondaryEmails());
    }
    for (String address : addresses) {
      CardDavRow row = emailContactStorage.getCardDavRowByAddress(username, address);
      if (row != null) {
        return row;
      }
    }
    return null;
  }

  /**
   * Whether the sync may take over a row that already exists at this address.
   * <p>
   * A collected row yes: it was inferred from mail, and the address book knows
   * the person better. A row the user typed, no. A legacy directory link, no
   * either — a live profile beats a copied vCard, which is the same reasoning
   * that removed the directory import.
   * <p>
   * A hidden row IS claimable, and stays hidden: hiding is a local decision the
   * sync must respect, while still refreshing the data underneath so that
   * un-hiding later gives something current rather than stale.
   *
   * @param row the stored row
   * @return true when the sync may write onto it
   */
  private boolean claimable(CardDavRow row) {
    return EmailContactSource.COLLECTED.equals(row.source()) || EmailContactSource.CARDDAV.equals(row.source());
  }

  /**
   * Lets go of every address-book row this user's current binding does not account
   * for, and forgets where the book was.
   * <p>
   * Called when a mailbox is rebound, disconnected, or the address book is switched
   * off. Without it those rows are orphans: a sync only ever looks at the rows its
   * OWN connector wrote, so contacts from the provider the user has just left would
   * stay in the store for good, with nothing able to reach them again.
   * <p>
   * What happens to a row depends on what else vouches for it. A person the user has
   * actually exchanged mail with is kept and demoted to collected — the mailbox
   * earned that, and the address book was only ever one of the reasons to know them.
   * A row nothing but the old book vouches for is deleted, because leaving a provider
   * should take its address book with it rather than leave hundreds of entries behind
   * as though they had been collected.
   *
   * @param username the mailbox owner
   */
  public void releaseUnboundBooks(String username) {
    UserEmailSetting setting = userEmailSettingService.getUserEmailSetting(username);
    Long boundConnectorId = boundConnectorId(setting);

    List<CardDavRow> rows = emailContactStorage.getAllCardDavRows(username);
    if (boundConnectorId != null) {
      // Read by connector rather than compared per row: the rows the current binding
      // accounts for are exactly the ones its own sync will keep maintaining.
      Set<Long> keptIds = emailContactStorage.getCardDavRows(username, boundConnectorId)
                                             .stream()
                                             .map(CardDavRow::id)
                                             .collect(Collectors.toSet());
      rows = rows.stream().filter(row -> !keptIds.contains(row.id())).toList();
    }
    if (rows.isEmpty()) {
      return;
    }

    int demoted = 0;
    int deleted = 0;
    for (CardDavRow row : rows) {
      if (row.seenCount() > 0) {
        emailContactStorage.demoteCardDavRow(row.id(), row.photoOrigin() == PhotoOrigin.VCARD);
        demoted++;
      } else {
        emailContactStorage.deleteContact(row.id());
        dropFavorite(row.id(), username);
        deleted++;
      }
    }
    // The discovered book belonged to the binding that has just gone, so a later sync
    // must discover again rather than ask the new provider for the old book's URL.
    userEmailSettingService.setContactSyncState(new ContactSyncState(), username);
    LOG.info("Address book released for user {}: {} kept as collected, {} removed", username, demoted, deleted);
  }

  /**
   * The connector whose address book the user is currently bound to.
   *
   * @param setting the user's mail setting, possibly null
   * @return the connector id, or null when no address book is bound at all
   */
  private Long boundConnectorId(UserEmailSetting setting) {
    if (setting == null || !Boolean.TRUE.equals(setting.getCarddavEnabled())
        || StringUtils.isBlank(setting.getEmailConnectorId())) {
      return null;
    }
    try {
      return Long.parseLong(setting.getEmailConnectorId());
    } catch (NumberFormatException e) {
      return null;
    }
  }

  /**
   * Deals with the rows whose entries are no longer on the server.
   * <p>
   * A contact with correspondence behind it is demoted back to collected rather
   * than deleted: the address book dropping an entry says nothing about whether
   * the user still writes to that person, and the mailbox earned that history.
   * One with none is deleted outright — it only ever existed because the address
   * book said so.
   *
   * @param username the mailbox owner
   * @param serverHrefs every entry the server still has
   * @param storedRows the rows this address book wrote, AS OF BEFORE the apply
   *          loop — a snapshot, and stale by construction for any row that
   *          loop wrote onto
   * @param bookChanged whether the administrator repointed the book this run
   * @param appliedRowIds every row the apply loop just wrote — untouchable
   *          here, whatever the snapshot remembers about them
   * @return how many rows were demoted or deleted
   */
  private int removeVanished(String username,
                             Set<String> serverHrefs,
                             List<CardDavRow> storedRows,
                             boolean bookChanged,
                             Set<Long> appliedRowIds) {
    int removed = 0;
    for (CardDavRow row : storedRows) {
      // A row this run just wrote belongs to a live server entry by definition
      // -- apply only ever writes what the server just returned. The snapshot
      // still shows the href the row USED to carry, so when the server files a
      // card under a new path (a rename, a move, or BlueMind renaming a
      // published card), the old href looks vanished here while the row it
      // names was updated moments ago. Judging it by the snapshot deleted a
      // user's contact in the same run that had just claimed it.
      if (appliedRowIds.contains(row.id())) {
        continue;
      }
      if (row.href() == null || serverHrefs.contains(row.href())) {
        continue;
      }
      boolean fromVCard = row.photoOrigin() == PhotoOrigin.VCARD;
      // A row missing from a DIFFERENT address book proves nothing: the entry was
      // never in this book to begin with. Those rows are demoted rather than
      // deleted, whatever their history, so repointing a connector costs the
      // bookkeeping and never the people. Only a row missing from the book it
      // actually came from is a real deletion.
      if (bookChanged || row.seenCount() > 0) {
        emailContactStorage.demoteCardDavRow(row.id(), fromVCard);
      } else {
        emailContactStorage.deleteContact(row.id());
        dropFavorite(row.id(), username);
      }
      removed++;
    }
    return removed;
  }

  /**
   * Drops the favorite of a row this sync just hard-deleted, fenced so a
   * favorites store having a bad day can never fail the reconciliation — a
   * demoted row keeps its favorite on purpose, the person is still there.
   *
   * @param contactId the deleted row's id
   * @param username the store owner
   */
  private void dropFavorite(long contactId, String username) {
    try {
      emailContactFavoriteService.removeFavorite(contactId, username);
    } catch (Exception e) {
      LOG.debug("The favorite of contact {} could not be dropped for user {}", contactId, username, e);
    }
  }

  /**
   * The parsed vCard in the store's own terms, addresses normalized the way every
   * other path normalizes them so a contact keyed by mail and one keyed by
   * address book are the same contact.
   *
   * @param card the parsed vCard
   * @return the data to store
   */
  private CardDavContactData toContactData(ParsedVCard card) {
    Set<String> addresses = new LinkedHashSet<>();
    card.emails().forEach(address -> {
      String normalized = EmailContactUtils.normalizeAddress(address);
      if (normalized != null) {
        addresses.add(normalized);
      }
    });
    List<String> all = new ArrayList<>(addresses);
    // Null when the entry carries none, which the store now holds happily.
    String primary = all.isEmpty() ? null : all.get(0);
    List<String> secondary = all.size() > 1 ? all.subList(1, all.size()) : List.of();
    String displayName = StringUtils.defaultIfBlank(card.formattedName(),
                                                    StringUtils.trimToNull(StringUtils.trimToEmpty(card.givenName()) + " "
                                                        + StringUtils.trimToEmpty(card.familyName())));
    return new CardDavContactData(primary,
                                  secondary,
                                  StringUtils.trimToNull(displayName),
                                  card.givenName(),
                                  card.familyName(),
                                  card.phones(),
                                  card.organization(),
                                  card.title(),
                                  card.birthday(),
                                  card.address(),
                                  card.note(),
                                  card.website(),
                                  card.uid(),
                                  card.photo(),
                                  card.photoMimeType());
  }

  /**
   * Whether this user's sync is paused after repeated failures, and still within
   * its cooldown.
   *
   * @param state the stored state
   * @return true when the run should be skipped
   */
  private boolean isBlocked(ContactSyncState state) {
    if (state.getStatus() != SyncStatus.BLOCKED) {
      return false;
    }
    Long last = state.getLastSyncStartDate();
    return last != null && new Date().getTime() - last < BLOCKED_COOLDOWN_MS;
  }

  /**
   * Records a run that got through.
   *
   * @param username the mailbox owner
   * @param state the state to update
   * @param ctag the version to remember, which is the old one after a partial run
   */
  private void succeed(String username, ContactSyncState state, String ctag) {
    state.setStatus(SyncStatus.SUCCESS);
    state.setFailedAttempts(0);
    state.setCtag(ctag);
    userEmailSettingService.setContactSyncState(state, username);
  }

  /**
   * Records a run that did not, escalating to a pause once a failure stops
   * looking like bad luck — a wrong password does not fix itself by being
   * retried every period.
   *
   * @param username the mailbox owner
   * @param state the state to update
   * @param cause what went wrong
   */
  private void fail(String username, ContactSyncState state, CardDavException cause) {
    int attempts = state.getFailedAttempts() + 1;
    state.setFailedAttempts(attempts);
    state.setStatus(attempts >= MAX_FAILED_ATTEMPTS ? SyncStatus.BLOCKED : SyncStatus.FAILURE);
    // The address book may have moved: forget where it was so the next attempt
    // discovers it again rather than knocking at a door that is gone.
    state.setAddressBookHref(null);
    userEmailSettingService.setContactSyncState(state, username);
    LOG.warn("Address book sync failed for user {} ({} consecutive)", username, attempts, cause);
  }
}
