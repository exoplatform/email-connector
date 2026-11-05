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

import java.util.List;

import org.exoplatform.commons.upgrade.UpgradeProductPlugin;
import org.exoplatform.container.ExoContainerContext;
import org.exoplatform.container.xml.InitParams;
import org.exoplatform.container.xml.ValuesParam;
import org.exoplatform.services.log.ExoLogger;
import org.exoplatform.services.log.Log;

import io.meeds.mcp.server.model.SimpleToolDefinition;
import io.meeds.mcp.server.service.McpServerToolService;

/**
 * Turns on {@code require_approval} for the tools it is given, on an instance
 * that has already started once.
 * <p>
 * The MCP server keeps the definition it saved for a known tool over the one
 * this add-on ships, so a {@code require_approval} raised in
 * {@code ai-tool-definitions.json} reaches only a fresh install. This plugin
 * sets that one flag on the saved definitions and keeps every other value an
 * administrator may have edited (title, description, input schema, disabled).
 * A tool already gated is left alone, and a tool the server does not know is
 * skipped.
 * <p>
 * Declared asynchronous: the MCP server's tool service is a Spring bean, which
 * the Kernel holds only once every add-on's Spring context has started.
 */
public class ToolApprovalUpgradePlugin extends UpgradeProductPlugin {

  /** The values-param listing the tool names to gate. */
  static final String          TOOL_NAMES_PARAM = "tool.names";

  private static final Log     LOG              = ExoLogger.getLogger(ToolApprovalUpgradePlugin.class);

  private final List<String>   toolNames;

  private McpServerToolService mcpServerToolService;

  /**
   * @param initParams the plugin's declared parameters, the tool names among
   *          them
   */
  public ToolApprovalUpgradePlugin(InitParams initParams) {
    super(initParams);
    ValuesParam toolNamesParam = initParams.getValuesParam(TOOL_NAMES_PARAM);
    this.toolNames = toolNamesParam == null ? List.of() : List.copyOf(toolNamesParam.getValues());
  }

  /**
   * @param initParams the plugin's declared parameters
   * @param mcpServerToolService the MCP server's tool service
   */
  ToolApprovalUpgradePlugin(InitParams initParams, McpServerToolService mcpServerToolService) {
    this(initParams);
    this.mcpServerToolService = mcpServerToolService;
  }

  @Override
  public void processUpgrade(String oldVersion, String newVersion) {
    McpServerToolService toolService = getMcpServerToolService();
    if (toolService == null) {
      LOG.warn("No MCP server tool service: the approval flag of tools {} is left unchanged", toolNames);
      return;
    }
    for (String toolName : toolNames) {
      SimpleToolDefinition toolDefinition = toolService.getToolDefinition(toolName);
      if (toolDefinition == null) {
        LOG.info("Tool '{}' is not registered on the MCP server, its approval flag is not changed", toolName);
      } else if (!toolDefinition.isRequireApproval()) {
        toolService.updateToolDefinition(toolName,
                                         toolDefinition.getTitle(),
                                         toolDefinition.getDescription(),
                                         toolDefinition.getInputSchema(),
                                         true,
                                         toolDefinition.isDisabled());
        LOG.info("Tool '{}' now requires the user's approval", toolName);
      }
    }
  }

  /**
   * @return the tool names this plugin gates
   */
  List<String> getToolNames() {
    return toolNames;
  }

  private McpServerToolService getMcpServerToolService() {
    if (mcpServerToolService == null) {
      mcpServerToolService = ExoContainerContext.getService(McpServerToolService.class);
    }
    return mcpServerToolService;
  }

}
