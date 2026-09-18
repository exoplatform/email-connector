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
package org.exoplatform.emailConnector.upgrade;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Component;

import org.exoplatform.commons.api.settings.SettingService;
import org.exoplatform.commons.api.settings.SettingValue;
import org.exoplatform.commons.api.settings.data.Context;
import org.exoplatform.commons.api.settings.data.Scope;
import org.exoplatform.emailConnector.dao.EmailContactDAO;
import org.exoplatform.emailConnector.entity.EmailContactEntity;
import org.exoplatform.emailConnector.utils.EmailContactUtils;
import org.exoplatform.services.log.ExoLogger;
import org.exoplatform.services.log.Log;

import io.meeds.common.ContainerTransactional;

import jakarta.annotation.PostConstruct;

/**
 * Rewrites the stored sort key of the contacts that carry no structured names:
 * their key used to be the display name as typed ("JOHN DOE", filed under J),
 * it now follows the contact form's reading of that name ("DOE JOHN", filed
 * under D). The key is written once at save time and read by the list, its
 * paging and the letter rail, so existing rows keep the old key until
 * recomputed. Runs once, at startup, on its own thread with the container bound
 * by the transactional aspect of {@link #run()}, and records its completion in
 * the settings; a run that does not complete is retried at the next start.
 */
@Component
public class ContactSortNameBackfill {

  private static final Log    LOG       = ExoLogger.getLogger(ContactSortNameBackfill.class);

  private static final String DONE_KEY  = "emailContactSortNameBackfillDone";

  private static final Scope  SCOPE     = Scope.APPLICATION.id("EMAIL_CONNECTOR_SCOPE");

  private static final int    PAGE_SIZE = 200;

  @Autowired
  private EmailContactDAO     emailContactDAO;

  @Autowired
  private SettingService      settingService;

  @PostConstruct
  public void init() {
    CompletableFuture.runAsync(this::run);
  }

  @ContainerTransactional
  public void run() {
    backfill();
  }

  void backfill() {
    try {
      if (isDone()) {
        return;
      }
      int rewritten = recomputeSortNames();
      markDone();
      LOG.info("Contact sort name backfill: {} contacts refiled", rewritten);
    } catch (Exception e) {
      LOG.warn("The contact sort name backfill did not complete and will run again at next start", e);
    }
  }

  int recomputeSortNames() {
    int rewritten = 0;
    int page = 0;
    List<EmailContactEntity> contacts;
    do {
      contacts = emailContactDAO.findAll(PageRequest.of(page++, PAGE_SIZE, Sort.by("id"))).getContent();
      rewritten += recomputeSortNames(contacts);
    } while (contacts.size() == PAGE_SIZE);
    return rewritten;
  }

  int recomputeSortNames(List<EmailContactEntity> contacts) {
    List<EmailContactEntity> changed = new ArrayList<>();
    for (EmailContactEntity contact : contacts) {
      if (StringUtils.isNotBlank(contact.getGivenName())
          || StringUtils.isNotBlank(contact.getFamilyName())
          || StringUtils.isBlank(contact.getDisplayName())) {
        continue;
      }
      String sortName = EmailContactUtils.computeSortName(null,
                                                          null,
                                                          contact.getDisplayName(),
                                                          contact.getPrimaryEmail());
      if (StringUtils.equals(sortName, contact.getSortName())) {
        continue;
      }
      contact.setSortName(sortName);
      contact.setSortBucket(EmailContactUtils.sortBucketOf(sortName));
      changed.add(contact);
    }
    if (!changed.isEmpty()) {
      emailContactDAO.saveAll(changed);
    }
    return changed.size();
  }

  boolean isDone() {
    SettingValue<?> value = settingService.get(Context.GLOBAL, SCOPE, DONE_KEY);
    return value != null && value.getValue() != null && Boolean.parseBoolean(value.getValue().toString());
  }

  void markDone() {
    settingService.set(Context.GLOBAL, SCOPE, DONE_KEY, SettingValue.create("true"));
  }
}
