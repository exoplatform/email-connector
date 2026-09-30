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
package org.exoplatform.emailConnector.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import org.exoplatform.commons.api.settings.SettingService;
import org.exoplatform.commons.api.settings.SettingValue;
import org.exoplatform.commons.api.settings.data.Context;

/**
 * The engines the administration screen chose are global settings, one per connector
 * and kind: kept lower-case, read back as kept, a blank one read as none.
 */
public class ConnectorEngineChoiceStorageTest {

  private final SettingService              settingService = mock(SettingService.class);

  private final ConnectorEngineChoiceStorage storage       = new ConnectorEngineChoiceStorage(settingService);

  /**
   * A choice is kept in the global context, under its kind and connector, lower-case.
   */
  @Test
  @SuppressWarnings({ "rawtypes", "unchecked" })
  public void aChoiceIsKeptGloballyPerConnectorAndKind() {
    storage.setChoice(ConnectorEngineChoiceStorage.RULES_ENGINE, 7L, " BlueMind ");

    ArgumentCaptor<SettingValue> value = ArgumentCaptor.forClass(SettingValue.class);
    verify(settingService).set(eq(Context.GLOBAL), eq(ConnectorEngineChoiceStorage.SCOPE), eq("rulesEngine.7"), value.capture());
    assertEquals("bluemind", value.getValue().getValue());
  }

  /**
   * A kept choice reads back lower-case; nothing kept, a null value and a blank one read
   * as none.
   */
  @Test
  @SuppressWarnings({ "rawtypes", "unchecked" })
  public void aChoiceReadsBackAndNothingReadsAsNone() {
    when(settingService.get(Context.GLOBAL, ConnectorEngineChoiceStorage.SCOPE, "aclEngine.7")).thenReturn((SettingValue) SettingValue.create("IMAP"));
    when(settingService.get(Context.GLOBAL, ConnectorEngineChoiceStorage.SCOPE, "aclEngine.8")).thenReturn((SettingValue) SettingValue.create(" "));

    assertEquals("imap", storage.getChoice(ConnectorEngineChoiceStorage.ACL_ENGINE, 7L));
    assertNull(storage.getChoice(ConnectorEngineChoiceStorage.ACL_ENGINE, 8L));
    assertNull(storage.getChoice(ConnectorEngineChoiceStorage.ACL_ENGINE, 9L));
    assertNull(storage.getChoice(ConnectorEngineChoiceStorage.RULES_ENGINE, 7L));
  }

  /**
   * A connector's choices are both forgotten.
   */
  @Test
  public void bothChoicesOfAConnectorAreForgotten() {
    storage.removeChoices(7L);

    verify(settingService).remove(Context.GLOBAL, ConnectorEngineChoiceStorage.SCOPE, "rulesEngine.7");
    verify(settingService).remove(Context.GLOBAL, ConnectorEngineChoiceStorage.SCOPE, "aclEngine.7");
  }
}
