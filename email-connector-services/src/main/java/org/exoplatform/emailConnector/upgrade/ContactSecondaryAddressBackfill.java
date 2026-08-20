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

import java.util.List;
import java.util.concurrent.CompletableFuture;

import jakarta.annotation.PostConstruct;

import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
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
 * Moves the secondary addresses of existing contacts into the address table.
 * <p>
 * The Liquibase changeset alongside this one moves the primary address, which is
 * a single column and so a single {@code INSERT … SELECT}. The others live inside
 * a joined {@code type,value;type,value} string, and splitting that is not
 * something to ask SQL to do across four database vendors — so it happens here,
 * once, in the language that already owns the format.
 * <p>
 * Runs at startup, off the boot thread, and marks itself done. Re-running would
 * be harmless anyway: every write it makes is an address a contact already
 * claims, and the unique index refuses a duplicate.
 */
@Component
public class ContactSecondaryAddressBackfill {

  private static final Log             LOG          = ExoLogger.getLogger(ContactSecondaryAddressBackfill.class);

  private static final String          DONE_KEY     = "emailContactAddressBackfillDone";

  private static final Scope           SCOPE        = Scope.APPLICATION.id("EMAIL_CONNECTOR_SCOPE");

  private static final int             PAGE_SIZE    = 200;

  @Autowired
  private EmailContactDAO              emailContactDAO;

  @Autowired
  private EmailContactAddressDAO       emailContactAddressDAO;

  @Autowired
  private SettingService               settingService;

  /**
   * Schedules the backfill, off the boot thread so a slow one never delays the
   * server coming up.
   */
  @PostConstruct
  public void init() {
    CompletableFuture.runAsync(this::backfill);
  }

  /**
   * Reads every contact once and files the addresses that only exist inside its
   * joined string.
   */
  protected void backfill() {
    if (isDone()) {
      return;
    }
    // Created by hand: this runs on a thread of its own, where nothing has bound a
    // persistence context yet, and every read below would fail without one.
    RequestLifeCycle.begin(ExoContainerContext.getCurrentContainer());
    try {
      int filed = 0;
      int page = 0;
      List<EmailContactEntity> contacts;
      do {
        contacts = emailContactDAO.findAll(org.springframework.data.domain.PageRequest.of(page++, PAGE_SIZE)).getContent();
        for (EmailContactEntity contact : contacts) {
          filed += fileSecondaryAddresses(contact);
        }
      } while (contacts.size() == PAGE_SIZE);
      markDone();
      LOG.info("Contact address backfill: {} secondary addresses filed", filed);
    } catch (Exception e) {
      // Not marked done, so the next start tries again. The store still works
      // meanwhile: a contact is found by its primary address, which the changeset
      // already filed, and only the secondary ones are missing.
      LOG.warn("The contact address backfill did not complete and will run again at next start", e);
    } finally {
      RequestLifeCycle.end();
    }
  }

  /**
   * Files one contact's secondary addresses.
   *
   * @param contact the stored contact
   * @return how many addresses were filed
   */
  private int fileSecondaryAddresses(EmailContactEntity contact) {
    if (contact == null || CollectionUtils.isEmpty(contact.getEmails())) {
      return 0;
    }
    int filed = 0;
    for (String pair : contact.getEmails()) {
      // Stored as "type,value" — the value is what a contact is reached at, and the
      // type is presentation this table has no use for.
      String address = EmailContactUtils.normalizeAddress(StringUtils.substringAfterLast(pair, ","));
      if (address == null || emailContactAddressDAO.findByUserIdAndAddress(contact.getUserId(), address).isPresent()) {
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
   * Whether the backfill already ran.
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
