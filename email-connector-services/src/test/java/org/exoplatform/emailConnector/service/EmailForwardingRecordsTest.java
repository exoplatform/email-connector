/**
 * Copyright (C) 2026 eXo Platform SAS.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License
 * as published by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program. If not, see <gnu.org/licenses>.
 */
package org.exoplatform.emailConnector.service;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import org.exoplatform.commons.api.settings.SettingService;
import org.exoplatform.commons.api.settings.SettingValue;
import org.exoplatform.commons.api.settings.data.Context;
import org.exoplatform.emailConnector.service.rules.ForwardingGuard;

/**
 * What eXo's own records say it set as forwards for a user (EXO-90793): read from the
 * settings eXo keeps, never from the mail server, and an unreadable record reads as
 * set, so that an engine switch errs towards refusing.
 */
@ExtendWith(MockitoExtension.class)
public class EmailForwardingRecordsTest {

  private static final String    USER = "mary";

  @Mock
  private SettingService         settingService;

  @InjectMocks
  private EmailForwardingService service;

  /**
   * Nothing recorded by default.
   */
  @BeforeEach
  public void nothingRecorded() {
    lenient().when(settingService.get(eq(Context.GLOBAL), eq(ForwardingGuard.FORWARDING_SCOPE), anyString())).thenReturn(null);
  }

  /**
   * No record, no forward; eXo's own server forward is one, whatever else; one eXo
   * did not set, or none on the server, is not.
   */
  @Test
  public void aForwardIsEXosWhenItsLastStatusSaysSo() {
    assertFalse(service.hasExoForward(USER));

    record(EmailForwardingService.STATUS_KEY_PREFIX + USER, "{\"state\":\"SERVER_FORWARD\",\"managedByExo\":true}");
    assertTrue(service.hasExoForward(USER));

    record(EmailForwardingService.STATUS_KEY_PREFIX + USER, "{\"state\":\"SERVER_FORWARD\",\"managedByExo\":false}");
    assertFalse(service.hasExoForward(USER));

    record(EmailForwardingService.STATUS_KEY_PREFIX + USER, "{\"state\":\"NONE\",\"managedByExo\":true}");
    assertFalse(service.hasExoForward(USER));

    record(EmailForwardingService.STATUS_KEY_PREFIX + USER, "{not json");
    assertTrue(service.hasExoForward(USER), "an unreadable status reads as a forward");
  }

  /**
   * On an engine without a script, the destination eXo set is the record: kept while
   * the forward is on, blank once eXo removed it.
   */
  @Test
  public void theDestinationEXoSetIsARecordToo() {
    record(EmailForwardingService.WRITTEN_KEY_PREFIX + USER, "partner@example.com");
    assertTrue(service.hasExoForward(USER));

    record(EmailForwardingService.WRITTEN_KEY_PREFIX + USER, "");
    assertFalse(service.hasExoForward(USER));
  }

  /**
   * A rule eXo wrote that forwards is recorded; an empty record, and none, are not; an
   * unreadable one reads as one.
   */
  @Test
  public void aRuleThatForwardsIsRecordedByTheRulesSeenLast() {
    assertFalse(service.hasExoRuleForwards(USER));

    record(EmailForwardingService.RULES_KEY_PREFIX + USER, "{\"rules\":{\"r1\":\"partner@example.com\"},\"names\":{\"r1\":\"Partners\"}}");
    assertTrue(service.hasExoRuleForwards(USER));

    record(EmailForwardingService.RULES_KEY_PREFIX + USER, "{\"rules\":{},\"names\":{}}");
    assertFalse(service.hasExoRuleForwards(USER));

    record(EmailForwardingService.RULES_KEY_PREFIX + USER, "{not json");
    assertTrue(service.hasExoRuleForwards(USER));
  }

  /**
   * Keeps one forwarding record.
   *
   * @param key the key
   * @param value the value
   */
  @SuppressWarnings({ "rawtypes", "unchecked" })
  private void record(String key, String value) {
    when(settingService.get(Context.GLOBAL, ForwardingGuard.FORWARDING_SCOPE, key)).thenReturn((SettingValue) SettingValue.create(value));
  }
}
