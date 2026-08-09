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
package org.exoplatform.emailConnector.upgrade;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import jakarta.annotation.PostConstruct;

import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

import org.exoplatform.commons.api.settings.SettingService;
import org.exoplatform.commons.api.settings.SettingValue;
import org.exoplatform.commons.api.settings.data.Context;
import org.exoplatform.commons.api.settings.data.Scope;
import org.exoplatform.container.ExoContainerContext;
import org.exoplatform.container.component.RequestLifeCycle;
import org.exoplatform.emailConnector.dao.EmailContactAddressDAO;
import org.exoplatform.emailConnector.dao.EmailContactDAO;
import org.exoplatform.emailConnector.entity.EmailContactAddressEntity;
import org.exoplatform.emailConnector.entity.EmailContactEntity;
import org.exoplatform.emailConnector.utils.EmailContactUtils;
import org.exoplatform.services.log.ExoLogger;
import org.exoplatform.services.log.Log;

/**
 * Gives every contact its rows in the address table.
 * <p>
 * Addresses moved into their own table, but the CardDAV write path never wrote
 * to it — so a synced contact could not be found by their own address. Mail from
 * them was collected as somebody new, clicking their name in a mail header
 * offered to create them, and importing the same address book as a file
 * duplicated the lot.
 * <p>
 * The write path is fixed; this heals the rows written before it was. It fills
 * only what is missing, so it is safe to run against a store that is already
 * whole, and an address another contact already claims is left alone rather than
 * fought over — the unique index is the authority on who owns it.
 */
@Component
public class ContactAddressRepair {

  private static final Log       LOG            = ExoLogger.getLogger(ContactAddressRepair.class);

  /** Marks the repair done, per deployment. */
  private static final String    DONE_KEY       = "emailContactAddressRepairDone";

  /** The add-on's settings scope, as the rest of the module uses it. */
  private static final Scope     SCOPE          = Scope.APPLICATION.id("EMAIL_CONNECTOR_SCOPE");

  /** How many contacts are read at a time. */
  private static final int       PAGE_SIZE      = 200;

  @Autowired
  private EmailContactDAO        emailContactDAO;

  @Autowired
  private EmailContactAddressDAO emailContactAddressDAO;

  @Autowired
  private SettingService         settingService;

  /**
   * Schedules the repair off the boot thread, so a large store never delays the
   * server coming up.
   */
  @PostConstruct
  public void init() {
    CompletableFuture.runAsync(this::repair);
  }

  /**
   * Walks every contact once and files the addresses it can be reached at.
   */
  protected void repair() {
    if (isDone()) {
      return;
    }
    // Created by hand: this runs on a thread of its own, where nothing has bound
    // a persistence context yet.
    RequestLifeCycle.begin(ExoContainerContext.getCurrentContainer());
    try {
      int filed = 0;
      int page = 0;
      List<EmailContactEntity> contacts;
      do {
        contacts = emailContactDAO.findAll(PageRequest.of(page++, PAGE_SIZE)).getContent();
        for (EmailContactEntity contact : contacts) {
          filed += fileAddresses(contact);
        }
      } while (contacts.size() == PAGE_SIZE);
      markDone();
      LOG.info("Contact address repair: {} addresses filed", filed);
    } catch (Exception e) {
      // Not marked done, so the next start tries again. Meanwhile the store
      // works, only lookups by address stay blind for the rows not yet filed.
      LOG.warn("The contact address repair did not complete and will run again at next start", e);
    } finally {
      RequestLifeCycle.end();
    }
  }

  /**
   * Files one contact's addresses, skipping what is already there.
   *
   * @param contact the stored contact
   * @return how many addresses were filed
   */
  private int fileAddresses(EmailContactEntity contact) {
    if (contact == null) {
      return 0;
    }
    int filed = 0;
    for (String address : addressesOf(contact)) {
      if (emailContactAddressDAO.findByUserIdAndAddress(contact.getUserId(), address).isPresent()) {
        continue;
      }
      EmailContactAddressEntity entity = new EmailContactAddressEntity();
      entity.setContactId(contact.getId());
      entity.setUserId(contact.getUserId());
      entity.setAddress(address);
      emailContactAddressDAO.save(entity);
      filed++;
    }
    return filed;
  }

  /**
   * The addresses a stored contact carries, normalized.
   *
   * @param contact the stored contact
   * @return the addresses, never null
   */
  private List<String> addressesOf(EmailContactEntity contact) {
    List<String> addresses = new ArrayList<>();
    String primary = EmailContactUtils.normalizeAddress(contact.getPrimaryEmail());
    if (primary != null) {
      addresses.add(primary);
    }
    if (StringUtils.isNotBlank(contact.getEmails())) {
      for (String pair : StringUtils.split(contact.getEmails(), ';')) {
        // Stored as "type,value" -- the value is what a contact is reached at.
        String secondary = EmailContactUtils.normalizeAddress(StringUtils.substringAfterLast(pair, ","));
        if (secondary != null && !addresses.contains(secondary)) {
          addresses.add(secondary);
        }
      }
    }
    return addresses;
  }

  /**
   * Whether the repair already ran.
   *
   * @return true when it has
   */
  private boolean isDone() {
    SettingValue<?> value = settingService.get(Context.GLOBAL, SCOPE, DONE_KEY);
    return value != null && value.getValue() != null && Boolean.parseBoolean(value.getValue().toString());
  }

  /**
   * Records that it did.
   */
  private void markDone() {
    settingService.set(Context.GLOBAL, SCOPE, DONE_KEY, SettingValue.create("true"));
  }
}
