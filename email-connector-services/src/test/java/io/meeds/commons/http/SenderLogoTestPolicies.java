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
package io.meeds.commons.http;

import java.util.Collection;

/**
 * The sender-logo tests' way to the policy builder's package-private test seam:
 * {@link SafeFetchPolicyBuilder#exemptHosts} is closed to production code, so a
 * test outside commons reaches it through a helper of its own in this package.
 * Test sources only.
 */
public final class SenderLogoTestPolicies {

  /**
   * Not instantiable: a holder of static helpers.
   */
  private SenderLogoTestPolicies() {
  }

  /**
   * Reads the addresses of these host names as public on the given builder, so
   * that a stub on loopback answers for a "public" name while the same stub under
   * any other name stays refused.
   *
   * @param builder the policy builder
   * @param hosts the host names
   * @return the same builder
   */
  public static SafeFetchPolicyBuilder exemptHosts(SafeFetchPolicyBuilder builder, Collection<String> hosts) {
    return builder.exemptHosts(hosts);
  }
}
