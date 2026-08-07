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

import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import org.exoplatform.emailConnector.carddav.AddressBook;
import org.exoplatform.emailConnector.carddav.CardDavClient;
import org.exoplatform.emailConnector.carddav.CardDavException;
import org.exoplatform.emailConnector.carddav.ContactResource;
import org.exoplatform.emailConnector.carddav.ParsedVCard;
import org.exoplatform.emailConnector.carddav.VCardParser;
import org.exoplatform.emailConnector.model.CardDavContactData;
import org.exoplatform.emailConnector.model.CardDavRow;
import org.exoplatform.emailConnector.model.ContactSyncState;
import org.exoplatform.emailConnector.model.EmailConnector;
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
 * One direction only, server to us, and read-only here: an address-book contact
 * is edited where it lives, and the next sync carries the change over. That is
 * why nothing in this class writes back, and why a CARDDAV row is refused edits
 * elsewhere in the service layer.
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

  @Autowired
  private EmailContactStorage      emailContactStorage;

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
      ContactSyncState state = userEmailSettingService.getContactSyncState(username);
      if (!requested && isBlocked(state)) {
        LOG.debug("Address book sync for user {} is paused after repeated failures", username);
        return;
      }
      run(username, setting, connector, state);
    } catch (Exception e) {
      LOG.warn("Address book sync failed for user {}", username, e);
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
   * One run, from discovery to reconciliation.
   *
   * @param username the mailbox owner
   * @param setting the user's mail binding, whose credentials the address book shares
   * @param connector the provider preset carrying the CardDAV URL
   * @param state where the last run got to
   */
  private void run(String username, UserEmailSetting setting, EmailConnector connector, ContactSyncState state) {
    state.setStatus(SyncStatus.IN_PROGRESS);
    state.setLastSyncStartDate(new Date().getTime());
    userEmailSettingService.setContactSyncState(state, username);
    try {
      AddressBook addressBook = resolveAddressBook(setting, connector, state);
      String currentCtag = cardDavClient.getCtag(addressBook, setting.getEmailAddress(), setting.getEmailPassword());
      if (currentCtag != null && currentCtag.equals(state.getCtag())) {
        // Nothing in the address book moved. This is the common case, and it is
        // why the job can run often without costing anything.
        succeed(username, state, state.getCtag());
        return;
      }
      boolean complete = reconcile(username, setting, connector, addressBook);
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
    if (StringUtils.isNotBlank(state.getAddressBookHref())) {
      return new AddressBook(state.getAddressBookHref(), null, state.getCtag());
    }
    AddressBook discovered = cardDavClient.discoverAddressBook(connector.getCarddavUrl(),
                                                               setting.getEmailAddress(),
                                                               setting.getEmailPassword());
    state.setAddressBookHref(discovered.url());
    return discovered;
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
  private boolean reconcile(String username, UserEmailSetting setting, EmailConnector connector, AddressBook addressBook) {
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
    for (int start = 0; start < toFetch.size(); start += FETCH_BATCH_SIZE) {
      List<String> batch = toFetch.subList(start, Math.min(start + FETCH_BATCH_SIZE, toFetch.size()));
      try {
        for (ContactResource resource : cardDavClient.multiget(addressBook,
                                                               batch,
                                                               setting.getEmailAddress(),
                                                               setting.getEmailPassword())) {
          written += apply(username, connector, resource, storedByHref.get(resource.href())) ? 1 : 0;
        }
      } catch (CardDavException e) {
        // One batch failing costs those entries this run, not the whole sync: the
        // rest of the address book still lands, and the version is not recorded,
        // so the next run picks these up.
        complete = false;
        LOG.warn("A batch of address book entries could not be read for user {}", username, e);
      }
    }
    int removed = removeVanished(serverEtags.keySet(), storedRows);
    LOG.info("Address book sync for user {}: {} entries on the server, {} written, {} no longer there",
             username,
             serverEtags.size(),
             written,
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
   * @return true when something was written
   */
  private boolean apply(String username, EmailConnector connector, ContactResource resource, CardDavRow sameHref) {
    ParsedVCard card = vCardParser.parse(resource.vcard());
    if (card == null || card.emails().isEmpty()) {
      // The store is keyed on an address; an entry without one has no identity
      // here. Counted in the log rather than treated as a failure.
      LOG.debug("An address book entry of user {} carries no usable address and was skipped", username);
      return false;
    }
    CardDavContactData data = toContactData(card);
    CardDavRow target = sameHref != null ? sameHref : emailContactStorage.getCardDavRowByAddress(username, data.primaryEmail());
    if (target != null && sameHref == null && !claimable(target)) {
      // A contact the user typed themselves, or a legacy link to a colleague's
      // profile. Claiming it would flip it read-only and overwrite what they
      // wrote; the address book is not more right about a person than they are.
      LOG.debug("An address book entry of user {} matches a contact that is not the sync's to claim", username);
      return false;
    }
    boolean writePhoto = target == null || target.photoOrigin() != PhotoOrigin.USER;
    emailContactStorage.saveCardDavContact(username,
                                           target == null ? null : target.id(),
                                           connector.getId(),
                                           resource.href(),
                                           resource.etag(),
                                           data,
                                           target == null ? null : target.photoFileId(),
                                           writePhoto);
    return true;
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
   * Deals with the rows whose entries are no longer on the server.
   * <p>
   * A contact with correspondence behind it is demoted back to collected rather
   * than deleted: the address book dropping an entry says nothing about whether
   * the user still writes to that person, and the mailbox earned that history.
   * One with none is deleted outright — it only ever existed because the address
   * book said so.
   *
   * @param serverHrefs every entry the server still has
   * @param storedRows the rows this address book wrote
   * @return how many rows were demoted or deleted
   */
  private int removeVanished(Set<String> serverHrefs, List<CardDavRow> storedRows) {
    int removed = 0;
    for (CardDavRow row : storedRows) {
      if (row.href() == null || serverHrefs.contains(row.href())) {
        continue;
      }
      boolean fromVCard = row.photoOrigin() == PhotoOrigin.VCARD;
      if (row.seenCount() > 0) {
        emailContactStorage.demoteCardDavRow(row.id(), fromVCard);
      } else {
        emailContactStorage.deleteContact(row.id());
      }
      removed++;
    }
    return removed;
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
    String primary = all.get(0);
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
