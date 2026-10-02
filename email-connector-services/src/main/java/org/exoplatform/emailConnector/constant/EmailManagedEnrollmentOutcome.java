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
package org.exoplatform.emailConnector.constant;

/**
 * What a login-time enrolment attempt came to: one value per branch of the
 * rules of {@code EmailManagedEnrollmentService} and their failures.
 */
public enum EmailManagedEnrollmentOutcome {
  NOT_MANAGED, ALREADY_CONFIGURED, ATTACHED, SWITCHED, REFUSED, FAILED, DETACHED
}
