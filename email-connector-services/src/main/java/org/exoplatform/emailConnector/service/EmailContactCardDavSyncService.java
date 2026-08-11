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
import java.util.Objects;
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
import org.exoplatform.emailConnector.carddav.CardDavPublishQueuedException;
import org.exoplatform.emailConnector.carddav.ContactResource;
import org.exoplatform.emailConnector.carddav.ParsedVCard;
import org.exoplatform.emailConnector.carddav.PutResult;
import org.exoplatform.emailConnector.carddav.VCardParser;
import org.exoplatform.emailConnector.model.CardDavContactData;
import org.exoplatform.emailConnector.model.CardDavRow;
import org.exoplatform.emailConnector.model.ContactPublishQueue;
import org.exoplatform.emailConnector.model.ContactPublishQueueEntry;
import org.exoplatform.emailConnector.model.ContactSyncState;
import org.exoplatform.emailConnector.model.EmailConnector;
import org.exoplatform.emailConnector.model.EmailContact;
import org.exoplatform.emailConnector.model.EmailContactSource;
import org.exoplatform.emailConnector.model.PhotoOrigin;
import org.exoplatform.emailConnector.model.PostalAddress;
import org.exoplatform.emailConnector.model.SyncStatus;
import org.exoplatform.emailConnector.model.UserEmailSetting;
import org.exoplatform.emailConnector.storage.EmailContactStorage;
import org.exoplatform.emailConnector.utils.EmailContactUtils;
import org.exoplatform.services.log.ExoLogger;
import org.exoplatform.services.log.Log;

/**
 * Brings a user's CardDAV address book into their contact store.
 * <p>
 * Reads are the rule, server to us: an address-book contact is edited against
 * the server or not at all — which is why a CARDDAV row is refused edits
 * elsewhere in the service layer, and why that refusal is lifted only HERE,
 * where the edit is pushed before it is kept. The writes are
 * {@link #publishContact} — an explicit, per-contact, creates-only push of a
 * contact the user owns into their own book — and
 * {@link #updateAddressBookContact} — an edit merged into the server's own
 * card and stored only when the server accepts it. Either way the server copy
 * stays authoritative: an edit the server refuses is an edit this store
 * refuses too, never one it keeps quietly diverged.
 * <p>
 * A publish the server cannot take right now is not thrown away: it waits in a
 * small per-user queue ({@link ContactPublishQueue}, in settings beside the
 * sync state) and is retried after the next successful inbound run — the run
 * that proves the server reachable again. Capped retries, then parked with the
 * reason; the contact itself never moves, so the queue can only ever lose a
 * reminder, never a person.
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

  /**
   * Message code: the server could not take the publish right now, so it was
   * queued to retry after the next successful sync. Answered 202, never as an
   * error — the click is safe to walk away from.
   */
  public static final String       PUBLISH_QUEUED            = "emailConnector.contacts.publish.queued";

  /**
   * Drain attempts a queued publish gets before it is parked. Three, like the
   * sync's own {@link #MAX_FAILED_ATTEMPTS}: drains only run after a sync that
   * just SUCCEEDED, so three failures in a row are three times the server took
   * reads but refused this write — a shape retrying will not fix.
   */
  private static final int         MAX_PUBLISH_ATTEMPTS      = 3;

  /**
   * Entries a user's queue may hold. Slice 4's reviewed bulk publish is now a
   * sanctioned bulk caller — a user keeping a whole address book through a
   * provider switch may tick hundreds of contacts at once — so the cap is no
   * longer sized for "clicking during an outage" but for the store itself: 500
   * matches the browse page's own clamp, and an entry is a handful of numbers
   * in a settings document, so even full this stays kilobytes. The cap's job
   * is unchanged in kind: a stop against unbounded growth, never a working
   * limit — and {@link #queuePublishes} refuses a selection that will not fit
   * rather than quietly dropping part of what the user reviewed.
   */
  private static final int         MAX_QUEUE_SIZE            = 500;

  /** Message code: the queue cannot take the whole reviewed selection. */
  public static final String       PUBLISH_QUEUE_FULL        = "emailConnector.contacts.publish.queueFull";

  /** Message code: a selected id is not one of the caller's visible contacts. */
  public static final String       PUBLISH_UNKNOWN_CONTACT   = "emailConnector.contacts.publish.unknownContact";

  /** Message code: the edited contact is not an address-book row at all. */
  public static final String       UPDATE_NOT_ADDRESS_BOOK   = "emailConnector.contacts.update.notAddressBook";

  /** Message code: the row carries no server entry to push the edit to. */
  public static final String       UPDATE_NO_SERVER_ENTRY    = "emailConnector.contacts.update.noServerEntry";

  /** Message code: the entry is no longer on the server at all. */
  public static final String       UPDATE_ENTRY_GONE         = "emailConnector.contacts.update.entryGone";

  /** Message code: somebody changed the entry first, and their change won. */
  public static final String       UPDATE_CONFLICT           = "emailConnector.contacts.update.conflict";

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
   * @throws CardDavPublishQueuedException when the server could not be reached
   *           and the publish was queued to retry — a promise, not a failure
   * @throws CardDavException when the server erred AND the queue could not
   *           take the publish either
   */
  public EmailContact publishContact(String username, long contactId) {
    // Synchronous first, queue only as the fallback — the deliberate answer to
    // "enqueue everything instead?". A click whose publish lands keeps exactly
    // the behavior slices 1 and 2 shipped: the toast reports what the server
    // DID, and the card re-renders as an address-book row on the spot. Routing
    // every click through the queue would have traded that certainty for
    // "eventually", for no gain on the happy path. The queue exists for the one
    // case a watching user cannot fix by watching: the server being away.
    try {
      EmailContact published = doPublish(username, contactId);
      if (published != null) {
        // Whatever the queue was holding for this contact, the user just did it
        // by hand: the entry — pending or parked — is satisfied, and the manual
        // publish button is thereby also how a parked entry gets retried.
        dequeuePublish(username, contactId);
      }
      return published;
    } catch (CardDavException e) {
      // Only transport failures fall back to the queue. A guard refusal
      // (disabled, wrong source, exists-on-server, no verified book) passed
      // through above is a NO that retrying cannot turn into a yes, and queuing
      // it would just park it later with the same words it can have now.
      if (enqueuePublish(username, contactId, e.getMessage())) {
        throw new CardDavPublishQueuedException(e.getMessage(), e);
      }
      // A full queue keeps today's honest failure: reported, retryable by hand.
      throw e;
    }
  }

  /**
   * The publish itself, exactly as slices 1 and 2 built it — every guard, the
   * creates-only PUT, the binding of the row to the server's own name for the
   * entry. Kept free of any queue knowledge so the drain can call it per entry
   * and interpret the outcome itself, without the fallback re-enqueueing what
   * the drain is in the middle of retrying.
   *
   * @param username the mailbox owner
   * @param contactId the contact to publish
   * @return the contact re-read after the publish; or null when the caller has
   *         no such visible contact
   * @throws IllegalArgumentException with a message code when a guard refuses
   * @throws IllegalStateException with a message code when the row or the
   *           server says the card already exists
   * @throws CardDavException when the server cannot be reached or errors
   */
  private EmailContact doPublish(String username, long contactId) {
    if (!isPublishEnabled()) {
      throw new IllegalArgumentException(PUBLISH_DISABLED);
    }
    EmailContact contact = emailContactService.getContact(contactId, username);
    if (contact == null) {
      return null;
    }
    if (EmailContactSource.CARDDAV.equals(contact.getSource())) {
      // Not an error to force through: the row IS an address-book entry, and
      // changing it is updateAddressBookContact's job, not a second create.
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
   * The caller's publish queue as stored — what the settings screen turns into
   * "N contacts waiting to publish". Never null, entries never null.
   *
   * @param username the mailbox owner
   * @return the stored queue
   */
  public ContactPublishQueue getPublishQueue(String username) {
    return userEmailSettingService.getContactPublishQueue(username);
  }

  /**
   * Queues a REVIEWED selection of the caller's own contacts to be published
   * to their address book — the bulk half of the write-back, and deliberately
   * nothing more than bookkeeping: no card leaves this method. The entries go
   * out through {@link #drainPublishQueue} after the next successful sync,
   * which is also what lets the selection be made BEFORE the new account's
   * book has ever been reached — the queue is target-agnostic, publishing to
   * whatever book the binding proves out to. That is why no bound or
   * discovered book is required here, unlike the single publish: refusing
   * would break the provider-switch flow this exists for, where the checklist
   * is answered right after a rebind and the first sync has not run yet.
   * <p>
   * The guards are the checklist's own rules, re-checked server-side because
   * there is no undo past this point but the drain's own guards: only MANUAL
   * contacts may travel — a COLLECTED row was inferred, not vouched for, and a
   * DIRECTORY row is the platform's person, not the user's to copy out; the
   * human ticking a box IS the provenance this slice runs on, so what was not
   * tickable must not be enqueuable either. The whole selection is validated
   * before one entry is written, and any refusal refuses ALL of it: a
   * selection the user reviewed either happens as reviewed or not at all —
   * partially honoring it would mean silently dropping names out of the one
   * checkpoint that exists. A selected contact that turns out already
   * published is the lone exception, skipped as satisfied: the state the user
   * asked for already holds.
   * <p>
   * Re-selecting an already-queued contact resets its entry — same rule as
   * {@link #enqueuePublish}: asking again is the retry that un-parks a parked
   * entry.
   *
   * @param username the mailbox owner
   * @param contactIds the reviewed selection; ids are de-duplicated
   * @return the queue as stored after the selection joined it
   * @throws IllegalArgumentException with {@link #PUBLISH_DISABLED},
   *           {@link #PUBLISH_UNKNOWN_CONTACT} or
   *           {@link #PUBLISH_SOURCE_NOT_ALLOWED} — nothing was enqueued
   * @throws IllegalStateException with {@link #PUBLISH_QUEUE_FULL} when the
   *           selection cannot fit whole — nothing was enqueued
   */
  public ContactPublishQueue queuePublishes(String username, List<Long> contactIds) {
    if (!isPublishEnabled()) {
      throw new IllegalArgumentException(PUBLISH_DISABLED);
    }
    Set<Long> selection = new LinkedHashSet<>();
    if (contactIds != null) {
      contactIds.stream().filter(Objects::nonNull).forEach(selection::add);
    }
    // Validated whole before anything is written: past this loop every id is a
    // visible MANUAL contact of the caller's, or the call has already refused.
    List<Long> toQueue = new ArrayList<>();
    for (Long contactId : selection) {
      EmailContact contact = emailContactService.getContact(contactId, username);
      if (contact == null) {
        // A stranger's id and an absent one answer the same, as everywhere on
        // this surface: the refusal proves nothing about whose it was.
        throw new IllegalArgumentException(PUBLISH_UNKNOWN_CONTACT);
      }
      if (EmailContactSource.CARDDAV.equals(contact.getSource())) {
        // Already an address-book row: what the user asked for already holds,
        // so the entry is skipped rather than the review refused over a race.
        continue;
      }
      if (!EmailContactSource.MANUAL.equals(contact.getSource())) {
        throw new IllegalArgumentException(PUBLISH_SOURCE_NOT_ALLOWED);
      }
      toQueue.add(contactId);
    }
    ContactPublishQueue queue = userEmailSettingService.getContactPublishQueue(username);
    if (toQueue.isEmpty()) {
      return queue;
    }
    List<ContactPublishQueueEntry> entries = queue.getEntries();
    entries.removeIf(entry -> toQueue.contains(entry.getContactId()));
    if (entries.size() + toQueue.size() > MAX_QUEUE_SIZE) {
      throw new IllegalStateException(PUBLISH_QUEUE_FULL);
    }
    long now = new Date().getTime();
    toQueue.forEach(contactId -> entries.add(new ContactPublishQueueEntry(contactId, now, 0, false, null, null, null)));
    userEmailSettingService.setContactPublishQueue(queue, username);
    LOG.info("{} reviewed contact(s) of user {} queued to publish after the next successful address book sync",
             toQueue.size(),
             username);
    return queue;
  }

  /**
   * Puts a publish the server could not take aside, to retry after the next
   * successful sync. Only the contact's id is kept — the retry reads the
   * contact fresh, so nothing here can go stale, and losing the queue can lose
   * at most the reminder, never the contact.
   * <p>
   * Re-asking for a contact already queued resets its entry rather than adding
   * a second: the user pressing the button again is the retry that un-parks a
   * parked entry and gives a tired one a fresh count.
   *
   * @param username the mailbox owner
   * @param contactId the contact whose publish is postponed
   * @param error what the attempt just failed with, kept on the entry
   * @return true when the queue took the entry, false when it is full
   */
  private boolean enqueuePublish(String username, long contactId, String error) {
    ContactPublishQueue queue = userEmailSettingService.getContactPublishQueue(username);
    List<ContactPublishQueueEntry> entries = queue.getEntries();
    entries.removeIf(entry -> entry.getContactId() == contactId);
    if (entries.size() >= MAX_QUEUE_SIZE) {
      LOG.warn("The publish queue of user {} is full ({} entries); the failed publish of contact {} stays a plain failure",
               username,
               entries.size(),
               contactId);
      return false;
    }
    entries.add(new ContactPublishQueueEntry(contactId, new Date().getTime(), 0, false, null, error, null));
    userEmailSettingService.setContactPublishQueue(queue, username);
    LOG.info("The publish of contact {} for user {} was queued after a server failure", contactId, username);
    return true;
  }

  /**
   * Forgets the queue's entry for a contact whose publish just happened some
   * other way. Nothing to save when the contact was never queued, which is
   * almost every publish.
   *
   * @param username the mailbox owner
   * @param contactId the contact now published
   */
  private void dequeuePublish(String username, long contactId) {
    ContactPublishQueue queue = userEmailSettingService.getContactPublishQueue(username);
    if (queue.getEntries().removeIf(entry -> entry.getContactId() == contactId)) {
      userEmailSettingService.setContactPublishQueue(queue, username);
    }
  }

  /**
   * Retries every pending publish, called only after a sync run that just
   * SUCCEEDED — which is the whole scheduling story: a successful inbound run
   * proves the server reachable and the address book discovered, the two
   * preconditions a publish needs, and it happens on the mailbox job's own
   * rhythm, so the queue drains itself without a scheduler, a hot loop, or a
   * user watching. A run that failed never reaches this method, and a BLOCKED
   * book therefore pauses draining by construction — a server unreachable for
   * reads is unreachable for writes, and hammering it with queued PUTs while
   * the sync itself is backing off would defeat the pause.
   * <p>
   * Outcomes are judged per entry: a publish that lands — or turns out already
   * done, or whose contact the user deleted meanwhile — leaves the queue; a
   * refusal specific to the entry parks it with the refusal's own words, since
   * retrying cannot change a NO about the contact itself; a refusal about the
   * USER's situation (publishing switched off, the book unbound) stops the
   * drain and keeps every entry untouched, because flipping the switch back
   * should resume them without anyone re-asking; and a transport failure
   * counts one attempt against the entry it hit, then stops the whole drain —
   * a server that just dropped a write is shaky, and letting the loop continue
   * would burn every entry's attempts on one bad moment. Three attempts, then
   * parked with the reason: parked is still not lost — the contact sits local
   * and visible, and its publish button remains the retry.
   * <p>
   * Nothing thrown here escapes: a broken outbound must never bleed into the
   * inbound bookkeeping — the run already recorded its success, and the
   * FAILURE→BLOCKED counter belongs to reads alone.
   *
   * @param username the mailbox owner
   */
  private void drainPublishQueue(String username) {
    ContactPublishQueue snapshot = userEmailSettingService.getContactPublishQueue(username);
    if (snapshot.getEntries().isEmpty()) {
      return;
    }
    Set<Long> done = new HashSet<>();
    boolean changed = false;
    int published = 0;
    for (ContactPublishQueueEntry entry : snapshot.getEntries()) {
      if (entry.isParked()) {
        continue;
      }
      try {
        EmailContact contact = doPublish(username, entry.getContactId());
        // A null contact is the user having deleted it since: the queue must
        // not resurrect what they removed, so the entry simply leaves too.
        done.add(entry.getContactId());
        changed = true;
        published += contact != null ? 1 : 0;
      } catch (IllegalStateException e) {
        if (PUBLISH_ALREADY_PUBLISHED.equals(e.getMessage())) {
          // The row became an address-book entry some other way -- a manual
          // retry that worked, or the inbound run just claiming it by address.
          // The queue's promise is kept, whoever kept it.
          done.add(entry.getContactId());
        } else {
          park(entry, e.getMessage());
        }
        changed = true;
      } catch (IllegalArgumentException e) {
        if (isUserLevelRefusal(e.getMessage())) {
          break;
        }
        park(entry, e.getMessage());
        changed = true;
      } catch (CardDavException e) {
        entry.setAttempts(entry.getAttempts() + 1);
        entry.setLastError(e.getMessage());
        entry.setLastAttemptDate(new Date().getTime());
        if (entry.getAttempts() >= MAX_PUBLISH_ATTEMPTS) {
          park(entry, e.getMessage());
        }
        changed = true;
        break;
      } catch (Exception e) {
        // A failure of ours, not the server's: parked immediately, with its
        // words -- retrying a bug every sync period is the hot loop this queue
        // exists to never be.
        park(entry, e.getMessage());
        changed = true;
        LOG.warn("The queued publish of contact {} for user {} failed outside the protocol and was parked",
                 entry.getContactId(),
                 username,
                 e);
      }
    }
    if (changed) {
      saveDrainedQueue(username, snapshot, done);
      LOG.info("Publish queue drained for user {}: {} published, {} still waiting or parked",
               username,
               published,
               userEmailSettingService.getContactPublishQueue(username).getEntries().size());
    }
  }

  /**
   * Writes the drain's outcomes back without overwriting what happened while it
   * ran: the stored queue is re-read and the outcomes applied onto it, so an
   * entry a concurrent click enqueued mid-drain survives the save. The drain
   * holds no lock across its network calls on purpose — the only concurrent
   * writer is the same user clicking, and losing their fresh entry to a
   * wholesale save was the one way this bookkeeping could actually lose
   * something.
   *
   * @param username the mailbox owner
   * @param snapshot the queue as the drain worked on it, outcomes applied
   * @param done the contact ids whose entries are satisfied and leave
   */
  private void saveDrainedQueue(String username, ContactPublishQueue snapshot, Set<Long> done) {
    Map<Long, ContactPublishQueueEntry> outcomes = new java.util.HashMap<>();
    snapshot.getEntries().forEach(entry -> outcomes.put(entry.getContactId(), entry));
    ContactPublishQueue current = userEmailSettingService.getContactPublishQueue(username);
    List<ContactPublishQueueEntry> entries = current.getEntries();
    entries.removeIf(entry -> done.contains(entry.getContactId()));
    entries.replaceAll(entry -> outcomes.getOrDefault(entry.getContactId(), entry));
    userEmailSettingService.setContactPublishQueue(current, username);
  }

  /**
   * Parks an entry with why, which is the queue keeping its second promise:
   * never a hot loop, and never silent about giving up.
   *
   * @param entry the entry that stops being retried
   * @param reason the refusal or failure it keeps
   */
  private void park(ContactPublishQueueEntry entry, String reason) {
    entry.setParked(true);
    entry.setParkedReason(reason);
    entry.setLastError(reason);
    entry.setLastAttemptDate(new Date().getTime());
  }

  /**
   * Whether a refusal is about the user's situation rather than the one entry —
   * the kill switch off, no address book bound, the book not discovered. Those
   * stop the drain and leave the queue as it stands: the entries are fine, the
   * ground under them moved, and it may move back.
   *
   * @param messageCode the refusal's message code
   * @return true when the drain should stop rather than park anything
   */
  private boolean isUserLevelRefusal(String messageCode) {
    return PUBLISH_DISABLED.equals(messageCode)
        || PUBLISH_NO_ADDRESS_BOOK.equals(messageCode)
        || PUBLISH_NOT_DISCOVERED.equals(messageCode);
  }

  /**
   * Pushes an edit of one of the caller's address-book contacts to the CardDAV
   * server, and keeps it locally only once the server accepted it — the write
   * that lifts the CARDDAV-rows-are-read-only rule without ever relaxing it:
   * the path goes from "edit refused" straight to "edit pushed", and an edit
   * that cannot be pushed is still refused, never kept quietly diverged.
   * <p>
   * Deliberately WITHOUT the publish's queue fallback: an edit's merge base is
   * the server's card as fetched at save time, so "retry this edit later"
   * would mean holding the user's words for a merge against a card that has
   * meanwhile moved on — exactly the quiet divergence this method exists to
   * refuse. An edit the server cannot take right now fails visibly, the form
   * keeps the words, and the user retries when the server is back; only the
   * create-shaped publish, whose content is simply the local row, is safe to
   * postpone.
   * <p>
   * The order is the safety argument. The edit is validated first (usable
   * addresses, no address collision with another row) so nothing reaches the
   * server that could not land locally afterwards. Then the entry's card is
   * fetched FROM THE SERVER at save time and the edit is merged into that raw
   * card — only the form's own properties, only where they differ — because
   * the local model is lossy and serializing it back would strip everything it
   * has no slot for. The PUT goes out under {@code If-Match} with the etag
   * that same fetch answered, so the server accepts the write only if nobody
   * changed the entry since this code read it: the merge's base and the
   * precondition's version are the same read, which is what makes the merge
   * honest.
   * <p>
   * A 412 never overwrites anybody: the other change stays on the server, the
   * server's current card is re-read and becomes the local row (the new
   * baseline the user re-edits from), and the refusal is answered as a
   * conflict so the form can keep the user's words on screen. On success the
   * merged card itself is parsed back into the row, exactly as the inbound
   * sync would store it, with whatever etag the server answered the PUT —
   * null included, which simply makes the next sync re-read the entry.
   *
   * @param username the mailbox owner
   * @param contactId the address-book contact being edited
   * @param edited the fields as the form saved them — same three-state
   *          contracts as the ordinary update for secondaryEmails and phones
   * @return the contact re-read after the push; or null when the caller has
   *         no such visible contact
   * @throws IllegalArgumentException with a message code when a guard refuses
   *           the edit before anything is written
   * @throws IllegalStateException with {@link #UPDATE_CONFLICT},
   *           {@link #UPDATE_ENTRY_GONE}, {@link #UPDATE_NO_SERVER_ENTRY} or
   *           {@code CONTACT_ALREADY_EXISTS} — refusals, each leaving the
   *           server untouched
   * @throws CardDavException when the server cannot be reached, errors, or
   *           answers a card too broken to merge safely
   */
  public EmailContact updateAddressBookContact(String username, long contactId, EmailContact edited) {
    if (!isPublishEnabled()) {
      // The same kill switch as the publish: it means "this connector does not
      // write to address books", and an edit is a write.
      throw new IllegalArgumentException(PUBLISH_DISABLED);
    }
    EmailContact contact = emailContactService.getContact(contactId, username);
    if (contact == null) {
      return null;
    }
    if (!EmailContactSource.CARDDAV.equals(contact.getSource())) {
      // Every other source has its ordinary update; routing it through the
      // server push would invent a server entry it does not have.
      throw new IllegalArgumentException(UPDATE_NOT_ADDRESS_BOOK);
    }
    UserEmailSetting setting = userEmailSettingService.getUserEmailSetting(username);
    Long connectorId = boundConnectorId(setting);
    EmailConnector connector = connectorId == null ? null : emailConnectorService.getEmailConnector(connectorId);
    if (connector == null || StringUtils.isBlank(connector.getCarddavUrl())) {
      throw new IllegalArgumentException(PUBLISH_NO_ADDRESS_BOOK);
    }
    ContactSyncState state = userEmailSettingService.getContactSyncState(username);
    if (state == null || StringUtils.isBlank(state.getAddressBookHref())) {
      // Same rule as the publish: never write to a book no sync has verified.
      throw new IllegalArgumentException(PUBLISH_NOT_DISCOVERED);
    }
    CardDavRow row = emailContactStorage.getCardDavRowById(contactId);
    if (row == null || StringUtils.isBlank(row.href())) {
      // A CARDDAV row without its bookkeeping cannot be pushed anywhere, so
      // its edit stays refused; the next sync is what repairs such a row.
      throw new IllegalStateException(UPDATE_NO_SERVER_ENTRY);
    }
    // Everything refusable about the edit itself is refused HERE, before any
    // network: past this point the only refusals left are the server's own.
    ParsedVCard editedCard = toEditedCard(contact, edited, username, contactId);
    String previousHref = state.getAddressBookHref();
    AddressBook addressBook = resolveAddressBook(setting, connector, state);
    if (!StringUtils.equals(previousHref, addressBook.url())) {
      userEmailSettingService.setContactSyncState(state, username);
    }
    String entryUrl = entryUrlOf(addressBook, row.href());
    ContactResource fetched = cardDavClient.fetchVCard(entryUrl, setting.getEmailAddress(), setting.getEmailPassword());
    if (fetched == null) {
      // The entry is gone from the server; the next sync will demote or delete
      // the row, and editing it further would recreate what somebody deleted.
      throw new IllegalStateException(UPDATE_ENTRY_GONE);
    }
    String merged = vCardParser.merge(fetched.vcard(), editedCard);
    if (merged == null) {
      // Cannot merge safely means cannot push, and cannot push means the edit
      // stays refused -- the rule is never relaxed ahead of the push working.
      throw new CardDavException("The server's card for contact " + contactId
          + " could not be read for merging, so the edit was refused");
    }
    if (StringUtils.isBlank(fetched.etag())) {
      // Rare, and worth a trace: without an etag from the fetch there is no
      // precondition to send, so the PUT rides only on the freshness of the
      // fetch the merge was based on.
      LOG.debug("The address book server answered no etag for {}; the edit of contact {} is pushed unguarded",
                entryUrl,
                contactId);
    }
    PutResult result = cardDavClient.updateVCard(entryUrl,
                                                 merged,
                                                 fetched.etag(),
                                                 setting.getEmailAddress(),
                                                 setting.getEmailPassword());
    if (result.preconditionFailed()) {
      // Somebody changed the entry between this code's fetch and its PUT, and
      // their change stays: the server's card becomes the local baseline, and
      // the refusal travels to the form still holding the user's words.
      adoptServerCard(username, row, connector.getId(), entryUrl, setting, fetched);
      throw new IllegalStateException(UPDATE_CONFLICT);
    }
    applyCard(username, row, connector.getId(), merged, result.etag());
    LOG.info("Contact {} of user {} was edited and pushed to the address book of connector {}",
             contactId,
             username,
             connector.getId());
    return emailContactService.getContact(contactId, username);
  }

  /**
   * The edit as the vCard fields the merge patches with, validated to the same
   * rules the ordinary update applies — because whatever lands on the server
   * must be storable back here in the same breath.
   * <p>
   * The list fields keep their three-state contracts: an absent
   * secondaryEmails keeps the stored other addresses and demotes a moved
   * primary into them instead of orphaning it; an absent phones list keeps the
   * stored numbers. Every address the edit leaves on the contact is checked
   * against the store first — pushing a card the local save would then refuse
   * as a collision would desynchronize the two on purpose.
   *
   * @param stored the contact as it reads today, for the keep-on-silence rules
   * @param edited the fields as the caller sent them
   * @param username the store owner
   * @param contactId the contact being edited, exempt from its own collisions
   * @return the fields the merge may patch with
   * @throws IllegalArgumentException with {@code CONTACT_INVALID_EMAIL} or
   *           {@code CONTACT_INVALID_BIRTHDAY}
   * @throws IllegalStateException with {@code CONTACT_ALREADY_EXISTS}
   */
  private ParsedVCard toEditedCard(EmailContact stored, EmailContact edited, String username, long contactId) {
    String primary = EmailContactUtils.normalizeAddress(edited == null ? null : edited.getPrimaryEmail());
    if (primary == null) {
      throw new IllegalArgumentException(EmailContactService.CONTACT_INVALID_EMAIL);
    }
    Set<String> addresses = new LinkedHashSet<>();
    addresses.add(primary);
    List<String> requested = new ArrayList<>();
    if (edited.getSecondaryEmails() != null) {
      requested.addAll(edited.getSecondaryEmails());
    } else {
      if (stored.getSecondaryEmails() != null) {
        requested.addAll(stored.getSecondaryEmails());
      }
      if (stored.getPrimaryEmail() != null && !stored.getPrimaryEmail().equals(primary)) {
        requested.add(stored.getPrimaryEmail());
      }
    }
    for (String address : requested) {
      if (StringUtils.isBlank(address)) {
        continue;
      }
      String normalized = EmailContactUtils.normalizeAddress(address);
      if (normalized == null) {
        throw new IllegalArgumentException(EmailContactService.CONTACT_INVALID_EMAIL);
      }
      addresses.add(normalized);
    }
    for (String address : addresses) {
      CardDavRow other = emailContactStorage.getCardDavRowByAddress(username, address);
      if (other != null && other.id() != contactId) {
        throw new IllegalStateException(EmailContactService.CONTACT_ALREADY_EXISTS);
      }
    }
    String given = StringUtils.trimToNull(edited.getGivenName());
    String family = StringUtils.trimToNull(edited.getFamilyName());
    // The same naming rule as the ordinary update: naming the person
    // explicitly wins, whether as the pair or as the whole; saying nothing
    // about the name leaves the card's name alone (the merge skips a null FN).
    String formattedName = StringUtils.isNotBlank(given) || StringUtils.isNotBlank(family)
                                                                                           ? StringUtils.trim(StringUtils.trimToEmpty(given)
                                                                                               + " "
                                                                                               + StringUtils.trimToEmpty(family))
                                                                                           : StringUtils.trimToNull(edited.getDisplayName());
    String birthday = null;
    if (StringUtils.isNotBlank(edited.getBirthday())) {
      birthday = EmailContactUtils.normalizeBirthday(edited.getBirthday());
      if (birthday == null) {
        throw new IllegalArgumentException(EmailContactService.CONTACT_INVALID_BIRTHDAY);
      }
    }
    List<String> phones = edited.getPhones() != null ? edited.getPhones()
                                                     : stored.getPhones() == null ? List.of() : stored.getPhones();
    PostalAddress postalAddress = edited.getPostalAddress() == null ? null
                                                                    : PostalAddress.orNull(edited.getPostalAddress().street(),
                                                                                           edited.getPostalAddress().city(),
                                                                                           edited.getPostalAddress().region(),
                                                                                           edited.getPostalAddress()
                                                                                                 .postalCode(),
                                                                                           edited.getPostalAddress().country());
    return new ParsedVCard(null,
                           formattedName,
                           given,
                           family,
                           new ArrayList<>(addresses),
                           phones,
                           StringUtils.trimToNull(edited.getOrganization()),
                           // The form has no job-title field, and the merge owns
                           // no TITLE either way.
                           null,
                           birthday,
                           postalAddress,
                           EmailContactUtils.truncateNote(edited.getNote()),
                           StringUtils.trimToNull(edited.getWebsite()),
                           null,
                           null);
  }

  /**
   * The absolute URL of a bound entry — the stored href is the server-relative
   * path exactly as PROPFIND lists it, resolved here against the verified
   * book, never against anything configured-but-unverified.
   *
   * @param addressBook the resolved address book
   * @param href the row's stored entry path
   * @return the URL to GET and PUT
   */
  private String entryUrlOf(AddressBook addressBook, String href) {
    if (StringUtils.startsWithIgnoreCase(href, "http")) {
      return href;
    }
    try {
      return new URI(addressBook.url()).resolve(href).toString();
    } catch (URISyntaxException e) {
      throw new CardDavException("The stored entry path could not be resolved against the address book: " + href, e);
    }
  }

  /**
   * Makes the server's current card the local row after a refused PUT — the
   * half of "412 never overwrites" that faces the store: the other person's
   * change is what the row shows from now on, so the user re-edits from the
   * truth instead of from the copy that just lost the race. Re-fetched because
   * the 412 proves the save-time fetch is already behind; when the re-fetch
   * itself fails, that fetch is still newer than the row and is applied
   * instead. Best effort throughout: however this goes, the conflict is
   * reported and the next sync converges on the server.
   *
   * @param username the store owner
   * @param row the row being edited
   * @param connectorId the bound connector
   * @param entryUrl the entry's absolute URL
   * @param setting the user's mail binding
   * @param fetchedAtSave the card the save-time fetch answered
   */
  private void adoptServerCard(String username,
                               CardDavRow row,
                               Long connectorId,
                               String entryUrl,
                               UserEmailSetting setting,
                               ContactResource fetchedAtSave) {
    ContactResource latest = fetchedAtSave;
    try {
      latest = cardDavClient.fetchVCard(entryUrl, setting.getEmailAddress(), setting.getEmailPassword());
    } catch (CardDavException e) {
      LOG.debug("The conflicting card at {} could not be re-read; the save-time copy stands in", entryUrl, e);
    }
    if (latest != null) {
      applyCard(username, row, connectorId, latest.vcard(), latest.etag());
    }
    // A null re-fetch means the entry was deleted rather than changed: nothing
    // to adopt, and the next sync will demote or delete the row.
  }

  /**
   * Stores a card the server now holds onto the row, through the same write
   * the inbound sync uses — so a pushed edit and a synced entry are
   * indistinguishable afterwards. The photo is deliberately left untouched:
   * the merge never writes PHOTO, so the card's picture is not news.
   *
   * @param username the store owner
   * @param row the row to write onto
   * @param connectorId the bound connector
   * @param cardText the card as the server holds it
   * @param etag the version to record, or null to make the next sync re-read
   */
  private void applyCard(String username, CardDavRow row, Long connectorId, String cardText, String etag) {
    ParsedVCard card = vCardParser.parse(cardText);
    if (card == null) {
      // The write happened; only the local echo failed. The row keeps its old
      // etag, which no longer matches the server's listing, so the next sync
      // re-reads the entry and converges.
      LOG.warn("The card just written for contact {} of user {} could not be read back; the next sync will settle it",
               row.id(),
               username);
      return;
    }
    emailContactStorage.saveCardDavContact(username,
                                           row.id(),
                                           connectorId,
                                           row.href(),
                                           etag,
                                           toContactData(card),
                                           row.photoFileId(),
                                           false);
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
      } else {
        boolean complete = reconcile(username, setting, connector, addressBook, bookChanged);
        // The version is only recorded when the run saw everything. Recording it
        // after a partial run would make the next run's cheap check skip exactly
        // the entries this one missed, permanently and without a trace.
        succeed(username, state, complete ? currentCtag : state.getCtag());
      }
    } catch (CardDavException e) {
      fail(username, state, e);
    }
    // After the catch, never inside the try: whatever draining does or throws,
    // this run's own verdict is already written, so a broken OUTBOUND can never
    // count against the INBOUND failure counter -- a publish the server keeps
    // refusing must not stop anybody reading their address book. And only on
    // success: a run that failed proved nothing reachable, and a BLOCKED book
    // pausing its reads thereby pauses these writes too.
    if (state.getStatus() == SyncStatus.SUCCESS) {
      drainPublishQueue(username);
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
   * Every row is KEPT — this method deletes nobody, by design. It used to
   * delete the rows nothing but the old book vouched for, which is how one
   * rebind silently removed 489 contacts with no prompt: an event listener has
   * no way to ask, so it must not be the place a destructive answer is
   * assumed. Deletion now belongs exclusively to the provider-switch
   * start-fresh path, where the user chose it and holds the .vcf backup first
   * ({@link EmailContactVCardService#exportContactsThenStartFresh}). Here a
   * row only changes what it claims to be: one with correspondence behind it
   * becomes COLLECTED — the mailbox earned that — and one with none becomes
   * MANUAL, because "collected" would claim a mail history that never existed,
   * and the one thing still true is that the user has this person. Photos
   * stay with their rows: the person is being kept, and their picture is part
   * of them. On a rebind the cleanup listener's collected-release then runs
   * (its ordering against this one is pinned, see
   * {@code ContactBookReleaseListener}) and hands the COLLECTED rows over to
   * MANUAL too, since the mailbox their history came from is gone with the
   * binding.
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

    int toCollected = 0;
    int toManual = 0;
    for (CardDavRow row : rows) {
      if (row.seenCount() > 0) {
        emailContactStorage.demoteCardDavRow(row.id(), false, EmailContactSource.COLLECTED);
        toCollected++;
      } else {
        emailContactStorage.demoteCardDavRow(row.id(), false, EmailContactSource.MANUAL);
        toManual++;
      }
    }
    // The discovered book belonged to the binding that has just gone, so a later sync
    // must discover again rather than ask the new provider for the old book's URL.
    userEmailSettingService.setContactSyncState(new ContactSyncState(), username);
    // The queued publishes named that book too. The entries go -- their target no
    // longer exists -- but the contacts they pointed at are rows of the store and
    // stay exactly as local, visible and publishable as before the queue knew them.
    userEmailSettingService.clearContactPublishQueue(username);
    LOG.info("Address book released for user {}: {} kept as collected, {} kept as manual, none removed",
             username,
             toCollected,
             toManual);
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
        emailContactStorage.demoteCardDavRow(row.id(), fromVCard, EmailContactSource.COLLECTED);
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
