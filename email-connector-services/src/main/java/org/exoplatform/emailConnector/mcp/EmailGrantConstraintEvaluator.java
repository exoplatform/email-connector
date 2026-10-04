/*
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
package org.exoplatform.emailConnector.mcp;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.commons.lang3.StringUtils;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import io.meeds.mcp.server.model.McpToolGrantConstraint;
import io.meeds.mcp.server.plugin.McpToolGrantConstraintEvaluator;

/**
 * The argument limits of standing approvals for the tools that send mail
 * ({@code send_email}, {@code reply_email}, {@code reply_all},
 * {@code forward_email}): "only to recipients in one domain"
 * ({@link McpToolGrantConstraint#EMAIL_DOMAIN_KIND}).
 * <p>
 * The rules, all failing closed:
 * <ul>
 * <li>No standing approval, limited or not, covers a mail sent from a shared
 * mailbox or in another person's name: {@code mailbox} must be blank and
 * {@code identity} blank or {@value EmailMcpTool#IDENTITY_ME}. Such a mail
 * always shows its card.</li>
 * <li>Every recipient of {@code to}, {@code cc} and {@code bcc} (bcc included,
 * and checked whatever the tool) must be a bare address
 * ({@link EmailAddressRules#isBareAddress}) holding exactly one {@code @}; a
 * display name, a group, two addresses in one entry or a value that is not a
 * list of strings means no match.</li>
 * <li>Domains are compared lower-cased and in their IDNA ASCII form, exactly: a
 * subdomain is another domain.</li>
 * <li>A call with no recipient at all matches nothing.</li>
 * </ul>
 * The checks read the arguments as the tool receives them and with the
 * tool's own address rules ({@link EmailAddressRules}), so what is checked is
 * what is sent: under a limited grant only bare addresses pass, and the tool
 * sends a bare address as it was given. A reply's recipients are worked out
 * from the original mail, and the reply tools refuse arguments that don't name
 * them, so checking the arguments checks the reply.
 */
@Service("emailGrantConstraintEvaluator")
@Profile("mcp-server")
public class EmailGrantConstraintEvaluator implements McpToolGrantConstraintEvaluator {

  /** The tools whose standing approvals this evaluator rules. */
  static final Set<String>  MAIL_TOOLS       = Set.of("send_email", "reply_email", "reply_all", "forward_email");

  /** The recipient arguments, as the tool methods name them. */
  private static final List<String> RECIPIENT_ARGUMENTS = List.of("to", "cc", "bcc");

  /**
   * @param toolName the MCP tool name
   * @return true for the tools that send mail
   */
  @Override
  public boolean supports(String toolName) {
    return MAIL_TOOLS.contains(toolName);
  }

  /**
   * A mail from a shared mailbox, or in another person's name, is never sent
   * under a standing approval.
   *
   * @param toolName  the MCP tool name
   * @param arguments the call arguments as the tool receives them
   * @return true when the mail goes from the user's own mailbox in their own
   *         name
   */
  @Override
  public boolean allowsStandingApproval(String toolName, Map<String, Object> arguments) {
    if (!supports(toolName) || arguments == null) {
      return false;
    }
    Object mailbox = arguments.get("mailbox");
    Object identity = arguments.get("identity");
    boolean ownMailbox = mailbox == null || (mailbox instanceof String value && StringUtils.isBlank(value));
    boolean ownName = identity == null
                      || (identity instanceof String value && (StringUtils.isBlank(value) || EmailMcpTool.IDENTITY_ME.equals(value)));
    return ownMailbox && ownName;
  }

  /**
   * Offers "only to recipients in &lt;domain&gt;" when every recipient is a
   * bare address of one same domain.
   *
   * @param toolName  the MCP tool name
   * @param arguments the call arguments as the tool receives them
   * @return the domain limit, or null when the call offers none
   */
  @Override
  public McpToolGrantConstraint proposeConstraint(String toolName, Map<String, Object> arguments) {
    if (!allowsStandingApproval(toolName, arguments)) {
      return null;
    }
    List<String> domains = recipientDomains(arguments);
    if (domains == null || domains.isEmpty() || domains.stream().distinct().count() != 1) {
      return null;
    }
    return new McpToolGrantConstraint(McpToolGrantConstraint.EMAIL_DOMAIN_KIND, domains.get(0));
  }

  /**
   * Tells whether every recipient of the call is in the grant's domain.
   *
   * @param toolName   the MCP tool name
   * @param arguments  the call arguments as the tool receives them
   * @param constraint the grant's constraint
   * @return true only when the call has recipients, all bare addresses of
   *         exactly that domain
   */
  @Override
  public boolean matches(String toolName, Map<String, Object> arguments, McpToolGrantConstraint constraint) {
    if (constraint == null
        || !McpToolGrantConstraint.EMAIL_DOMAIN_KIND.equals(constraint.kind())
        || !allowsStandingApproval(toolName, arguments)) {
      return false;
    }
    String domain = EmailAddressRules.normalisedDomain(constraint.value());
    if (domain == null) {
      return false;
    }
    List<String> domains = recipientDomains(arguments);
    return domains != null && !domains.isEmpty() && domains.stream().allMatch(domain::equals);
  }

  /**
   * Reads the domain of every recipient of {@code to}, {@code cc} and
   * {@code bcc}.
   *
   * @param arguments the call arguments
   * @return the domains, one per recipient, or null when any recipient
   *         argument isn't a list of strings or any entry has no domain
   */
  private static List<String> recipientDomains(Map<String, Object> arguments) {
    List<String> domains = new ArrayList<>();
    for (String argument : RECIPIENT_ARGUMENTS) {
      Object value = arguments.get(argument);
      if (value == null) {
        continue;
      }
      if (!(value instanceof List<?> entries)) {
        return null;
      }
      for (Object entry : entries) {
        String domain = entry instanceof String address ? EmailAddressRules.domainOf(address) : null;
        if (domain == null) {
          return null;
        }
        domains.add(domain);
      }
    }
    return domains;
  }

}
