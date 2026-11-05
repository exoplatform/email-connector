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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import org.exoplatform.commons.upgrade.UpgradeProductPlugin;
import org.exoplatform.container.xml.InitParams;
import org.exoplatform.container.xml.ValueParam;
import org.exoplatform.container.xml.ValuesParam;

import io.meeds.mcp.server.model.SimpleToolDefinition;
import io.meeds.mcp.server.service.McpServerToolService;

/**
 * EXO-92488 -- the plugin raises {@code require_approval} on the saved
 * definitions of the tools it is given, and only that flag: an administrator's
 * title, description, input schema and disabled state stay as they are.
 */
@ExtendWith(MockitoExtension.class)
class ToolApprovalUpgradePluginTest {

  @Mock
  private McpServerToolService mcpServerToolService;

  @Test
  void raisesTheFlagOfAnUngatedToolAndKeepsItsOtherValues() {
    SimpleToolDefinition saved = new SimpleToolDefinition();
    saved.setName("mark_read");
    saved.setTitle("Admin title");
    saved.setDescription("Admin description");
    saved.setInputSchema("{\"type\":\"object\"}");
    saved.setRequireApproval(false);
    saved.setDisabled(true);
    when(mcpServerToolService.getToolDefinition("mark_read")).thenReturn(saved);

    newPlugin("mark_read").processUpgrade("7.3.0-M18", "7.3.0");

    verify(mcpServerToolService).updateToolDefinition("mark_read",
                                                      "Admin title",
                                                      "Admin description",
                                                      "{\"type\":\"object\"}",
                                                      true,
                                                      true);
  }

  @Test
  void leavesAnAlreadyGatedToolUntouched() {
    SimpleToolDefinition saved = new SimpleToolDefinition();
    saved.setName("mark_unread");
    saved.setRequireApproval(true);
    when(mcpServerToolService.getToolDefinition("mark_unread")).thenReturn(saved);

    newPlugin("mark_unread").processUpgrade("7.3.0-M18", "7.3.0");

    verify(mcpServerToolService, never()).updateToolDefinition(anyString(), any(), any(), any(), anyBoolean(), anyBoolean());
  }

  @Test
  void skipsAToolTheServerDoesNotKnowAndGoesOnWithTheNext() {
    SimpleToolDefinition saved = new SimpleToolDefinition();
    saved.setName("remove_email_category");
    saved.setRequireApproval(false);
    when(mcpServerToolService.getToolDefinition("unknown_tool")).thenReturn(null);
    when(mcpServerToolService.getToolDefinition("remove_email_category")).thenReturn(saved);

    newPlugin("unknown_tool", "remove_email_category").processUpgrade("7.3.0-M18", "7.3.0");

    verify(mcpServerToolService, never()).updateToolDefinition(eq("unknown_tool"), any(), any(), any(), anyBoolean(), anyBoolean());
    verify(mcpServerToolService).updateToolDefinition(eq("remove_email_category"), any(), any(), any(), eq(true), eq(false));
  }

  /**
   * A plugin that returns is recorded as done, and a run-once plugin never runs
   * again: without the tool service it must throw, so that it is attempted
   * again rather than leaving the tools ungated for good.
   */
  @Test
  void throwsWhenTheToolServiceIsMissing() {
    ToolApprovalUpgradePlugin plugin = newPluginWithService(null, "mark_read");
    assertThrows(IllegalStateException.class, () -> plugin.processUpgrade("7.3.0-M18", "7.3.0"));
  }

  @Test
  void readsItsToolNamesFromTheValuesParam() {
    assertEquals(List.of("mark_read", "mark_unread", "remove_email_category"),
                 newPlugin("mark_read", "mark_unread", "remove_email_category").getToolNames());
  }

  private ToolApprovalUpgradePlugin newPlugin(String... toolNames) {
    return newPluginWithService(mcpServerToolService, toolNames);
  }

  private ToolApprovalUpgradePlugin newPluginWithService(McpServerToolService toolService, String... toolNames) {
    InitParams initParams = new InitParams();
    ValueParam groupId = new ValueParam();
    groupId.setName(UpgradeProductPlugin.PRODUCT_GROUP_ID);
    groupId.setValue("org.exoplatform.platform");
    initParams.addParameter(groupId);
    ValuesParam names = new ValuesParam();
    names.setName(ToolApprovalUpgradePlugin.TOOL_NAMES_PARAM);
    names.setValues(new ArrayList<>(List.of(toolNames)));
    initParams.addParameter(names);
    return new ToolApprovalUpgradePlugin(initParams, () -> toolService);
  }

}
