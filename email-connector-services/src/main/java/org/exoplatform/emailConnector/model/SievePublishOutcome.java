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
package org.exoplatform.emailConnector.model;

import org.exoplatform.emailConnector.constant.SievePolicyCase;

/**
 * What a publish of eXo's Sieve script did.
 *
 * @param policyCase the case taken
 * @param activeScript the script now active
 * @param foreignScript the other script the wrapper includes, null when none
 * @param exoFirst whether the wrapper runs eXo's script first; false when no wrapper
 * @param scriptHash the SHA-256 of the text of eXo's script as stored
 */
public record SievePublishOutcome(SievePolicyCase policyCase,
                                  String activeScript,
                                  String foreignScript,
                                  boolean exoFirst,
                                  String scriptHash) {
}
